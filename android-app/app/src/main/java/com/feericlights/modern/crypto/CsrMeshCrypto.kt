package com.feericlights.modern.crypto

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Kotlin NIST P-192 (`prime192v1` / `secp192r1`) elliptic curve math for CSRMesh MASP ECDH.
 */
object P192Curve {
    val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFF", 16)
    val A: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFC", 16)
    val B: BigInteger = BigInteger("64210519E59C80E70FA7E9AB72243049FEB8DEECC146B9B1", 16)
    val GX: BigInteger = BigInteger("188DA80EB03090F67CBF20EB43A18800F4FF0AFD82FF1012", 16)
    val GY: BigInteger = BigInteger("07192B95FFC8DA78631011ED6B24CDD573F977A11E794811", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFF99DEF836146BC9B1B4D22831", 16)

    data class Point(val x: BigInteger, val y: BigInteger)

    val G = Point(GX, GY)

    private fun mod(x: BigInteger, m: BigInteger = P): BigInteger {
        val r = x.remainder(m)
        return if (r.signum() < 0) r.add(m) else r
    }

    private fun modInverse(a: BigInteger, m: BigInteger = P): BigInteger =
        mod(a, m).modPow(m.subtract(BigInteger.valueOf(2)), m)

    fun pointDouble(pt: Point?): Point? {
        if (pt == null || pt.y == BigInteger.ZERO) return null
        val threeX2PlusA = pt.x.multiply(pt.x).multiply(BigInteger.valueOf(3)).add(A)
        val twoYInv = modInverse(pt.y.multiply(BigInteger.valueOf(2)))
        val slope = mod(threeX2PlusA.multiply(twoYInv))
        val x3 = mod(slope.multiply(slope).subtract(pt.x.multiply(BigInteger.valueOf(2))))
        val y3 = mod(slope.multiply(pt.x.subtract(x3)).subtract(pt.y))
        return Point(x3, y3)
    }

    fun pointAdd(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x) {
            return if (mod(p1.y.add(p2.y)) == BigInteger.ZERO) null else pointDouble(p1)
        }
        val slope = mod(p2.y.subtract(p1.y).multiply(modInverse(p2.x.subtract(p1.x))))
        val x3 = mod(slope.multiply(slope).subtract(p1.x).subtract(p2.x))
        val y3 = mod(slope.multiply(p1.x.subtract(x3)).subtract(p1.y))
        return Point(x3, y3)
    }

    fun scalarMult(k: BigInteger, pt: Point): Point? {
        var result: Point? = null
        var addend: Point? = pt
        var scalar = k
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) {
                result = pointAdd(result, addend)
            }
            addend = pointDouble(addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    fun to24BytesLE(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val le = ByteArray(24)
        // Strip leading sign byte if present
        val start = if (be.size > 24 && be[0] == 0.toByte()) be.size - 24 else 0
        val count = minOf(24, be.size - start)
        for (i in 0 until count) {
            le[i] = be[be.size - 1 - i]
        }
        return le
    }

    fun from24BytesLE(le24: ByteArray): BigInteger {
        val be = ByteArray(24)
        for (i in 0 until 24) {
            be[i] = le24[23 - i]
        }
        return BigInteger(1, be)
    }
}

/**
 * Clean, zero-dependency Kotlin implementation of both:
 * 1. Qualcomm/CSR CSRMesh MCP cryptographic layer (`com.csr.mesh.j`, `a`, `e`, `f`, `LightModelApi`)
 * 2. Qualcomm/CSR CSRMesh MASP association layer (`com.csr.mesh.d`, `g`) for re-associating
 *    factory-reset garlands (when double-clicking the physical button makes them blink blue/purple).
 */
object CsrMeshCrypto {

    /** Default passphrase hardcoded in MainActivity.onConnected() (line 1966) */
    const val DEFAULT_PASSPHRASE = "8888"

    /** Controller source ID used by CSRMesh library (`0x8000`) */
    private const val CONTROLLER_SOURCE_ID = 0x8000

    /** Default broadcast target (`0x0000` = "All items") and first associated device ID (`0x8001`) */
    const val BROADCAST_DEVICE_ID = 0x0000
    const val DEFAULT_ASSIGNED_DEVICE_ID = 0x8001

    /** Default Mesh TTL (`0x37` = 55 for MCP, `0xFF` = 255 for MASP) */
    private const val DEFAULT_TTL: Byte = 0x37
    private const val MASP_TTL: Byte = 0xFF.toByte()

    /** CSRMesh LightModel opcodes (`LightModelApi.setRgb` unacknowledged) */
    private const val OPCODE_LIGHT_MODEL: Byte = 0x8A.toByte()
    private const val SUBOPCODE_SET_RGB_NO_ACK: Byte = 0x02

    /** 25-byte static XOR mask for MASP messages (`d.smali` lines 102-129) */
    private val MASP_XOR_MASK = byteArrayOf(
        0x52, 0x20, 0x2A, 0x39, 0x40, 0x18, 0xC4.toByte(), 0x41,
        0xAA.toByte(), 0xD1.toByte(), 0x7D, 0x26, 0x05, 0xD5.toByte(), 0x7F, 0xAE.toByte(),
        0x2C, 0xB3.toByte(), 0x0E, 0xF5.toByte(), 0xC5.toByte(), 0x2D, 0x06, 0x24, 0x15
    )

    /** 24-bit packet sequence counter initialized with SecureRandom just like `com.csr.mesh.e` */
    private val sequenceCounter = AtomicInteger(SecureRandom().nextInt() and 0xFFFFFF)

    private fun deriveKeyWithSuffix(phrase: String, suffix: String): ByteArray {
        val input = (phrase + "\u0000" + suffix).toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val key = digest.copyOfRange(digest.size - 16, digest.size)
        key.reverse()
        return key
    }

    /**
     * Derives the 16-byte CSRMesh `NETKEY` from a passphrase (`j.a(String, String)`):
     * `SHA-256(passphrase + "\u0000MCP")` last 16 bytes reversed.
     */
    fun deriveNetworkKey(passphrase: String = DEFAULT_PASSPHRASE): ByteArray {
        require(passphrase.length <= 64) { "Passphrase length out of bounds" }
        return deriveKeyWithSuffix(passphrase, "MCP")
    }

    /**
     * Derives the 16-byte CSRMesh `MASPKEY` (`SHA-256("\u0000MASP")` last 16 bytes reversed).
     */
    fun deriveMaspKey(): ByteArray = deriveKeyWithSuffix("", "MASP")

    /**
     * Matches `j.a(byte[] data, byte[] key, int len)`:
     * Computes `HMAC-SHA256(key, data)`, takes the last `len` bytes, and reverses them.
     */
    fun hmacSha256TruncReversed(data: ByteArray, key: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val full = mac.doFinal(data)
        val truncated = full.copyOfRange(full.size - len, full.size)
        truncated.reverse()
        return truncated
    }

    fun computePacketHmac8(key16: ByteArray, body: ByteArray): ByteArray {
        val preImage = ByteArray(8 + body.size)
        System.arraycopy(body, 0, preImage, 8, body.size)
        return hmacSha256TruncReversed(preImage, key16, 8)
    }

    /**
     * Encrypts and signs a plaintext MASP message into an MTL packet (`f.d(Packet)` & `d.a(byte[])`).
     */
    fun wrapMaspPacket(plainMasp: ByteArray, ttl: Byte = MASP_TTL): ByteArray {
        val enc = ByteArray(plainMasp.size)
        for (i in plainMasp.indices) {
            val maskIdx = minOf(i, MASP_XOR_MASK.size - 1)
            enc[i] = (plainMasp[i].toInt() xor MASP_XOR_MASK[maskIdx].toInt()).toByte()
        }
        val maspKey = deriveMaspKey()
        val mac8 = computePacketHmac8(maspKey, enc)
        val mtl = ByteArray(enc.size + 9)
        System.arraycopy(enc, 0, mtl, 0, enc.size)
        System.arraycopy(mac8, 0, mtl, enc.size, 8)
        mtl[mtl.size - 1] = ttl
        return mtl
    }

    /**
     * Verifies and decrypts an incoming MTL packet if it is signed with `MASPKEY`.
     * Returns the decrypted MASP payload, or `null` if it is not a valid MASP packet.
     */
    fun tryUnwrapMaspPacket(mtlPacket: ByteArray): ByteArray? {
        if (mtlPacket.size < 10) return null
        val bodyLen = mtlPacket.size - 9
        val body = mtlPacket.copyOfRange(0, bodyLen)
        val rxMac = mtlPacket.copyOfRange(bodyLen, bodyLen + 8)
        val expectedMac = computePacketHmac8(deriveMaspKey(), body)
        if (!rxMac.contentEquals(expectedMac)) return null

        val plain = ByteArray(bodyLen)
        for (i in 0 until bodyLen) {
            val maskIdx = minOf(i, MASP_XOR_MASK.size - 1)
            plain[i] = (body[i].toInt() xor MASP_XOR_MASK[maskIdx].toInt()).toByte()
        }
        return plain
    }

    /**
     * Extracts the 4-byte `uuidHash31` from a `MASP_DEVICE_APPEARANCE` (`0x00`) or
     * `MASP_DEVICE_IDENTIFICATION` (`0x01`) beacon.
     */
    fun extractUuidHash4FromMaspBeacon(plainMasp: ByteArray): ByteArray? {
        if (plainMasp.isEmpty()) return null
        val opcode = plainMasp[0].toInt() and 0xFF
        return when {
            opcode == 0x00 && plainMasp.size >= 5 -> plainMasp.copyOfRange(1, 5)
            opcode == 0x01 && plainMasp.size >= 17 -> {
                val uuid16 = plainMasp.copyOfRange(1, 17)
                val digest = MessageDigest.getInstance("SHA-256").digest(uuid16)
                digest[28] = (digest[28].toInt() and 0x7F).toByte()
                byteArrayOf(digest[28], digest[29], digest[30], digest[31])
            }
            else -> null
        }
    }

    /**
     * Builds an unacknowledged 15-byte plaintext CSRMesh `LIGHT_SET_RGB` MCP packet
     * (`LightModelApi.setRgb` lines 434-485).
     */
    fun buildPlainSetRgbPacket(
        deviceId: Int = BROADCAST_DEVICE_ID,
        red: Int,
        green: Int,
        blue: Int,
        level: Int = 0xFF,
        duration: Int = 0,
        seqNumber: Int = nextSequenceNumber()
    ): ByteArray {
        val packet = ByteArray(15)
        // Bytes 0..2: 24-bit sequence number (little-endian)
        packet[0] = (seqNumber and 0xFF).toByte()
        packet[1] = ((seqNumber ushr 8) and 0xFF).toByte()
        packet[2] = ((seqNumber ushr 16) and 0xFF).toByte()

        // Bytes 3..4: 16-bit source ID (0x8000 in little-endian -> 0x00, 0x80)
        packet[3] = (CONTROLLER_SOURCE_ID and 0xFF).toByte()
        packet[4] = ((CONTROLLER_SOURCE_ID ushr 8) and 0xFF).toByte()

        // Bytes 5..6: 16-bit destination device/group ID (little-endian)
        packet[5] = (deviceId and 0xFF).toByte()
        packet[6] = ((deviceId ushr 8) and 0xFF).toByte()

        // Byte 7: Model opcode (0x8A = Light Model)
        packet[7] = OPCODE_LIGHT_MODEL
        // Byte 8: Sub-opcode (0x02 = SET_RGB unacknowledged)
        packet[8] = SUBOPCODE_SET_RGB_NO_ACK
        // Byte 9: Level (0xFF)
        packet[9] = (level and 0xFF).toByte()
        // Byte 10: Red (Feeric command ID 1..5)
        packet[10] = (red and 0xFF).toByte()
        // Byte 11: Green (Feeric command parameter)
        packet[11] = (green and 0xFF).toByte()
        // Byte 12: Blue (Hardcoded 0x01 for Feeric vendor commands)
        packet[12] = (blue and 0xFF).toByte()
        // Bytes 13..14: Color duration (16-bit little-endian, 0x0000)
        packet[13] = (duration and 0xFF).toByte()
        packet[14] = ((duration ushr 8) and 0xFF).toByte()

        return packet
    }

    /**
     * Encrypts a 15-byte MCP plaintext packet and appends the 8-byte truncated HMAC-SHA256
     * and 1-byte TTL, producing the final 24-byte MTL packet ready to send over BLE GATT
     * (`com.csr.mesh.a` and `com.csr.mesh.f.d(Packet)`).
     */
    fun encryptAndSignMcpPacket(
        plainMcp: ByteArray,
        netKey: ByteArray = deriveNetworkKey(),
        ttl: Byte = DEFAULT_TTL
    ): ByteArray {
        require(plainMcp.size >= 6) { "MCP packet too short" }
        require(netKey.size == 16) { "CSRMesh NETKEY must be 16 bytes" }

        val iv = ByteArray(16)
        System.arraycopy(plainMcp, 0, iv, 0, 3)
        System.arraycopy(plainMcp, 3, iv, 4, 2)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(netKey, "AES"))
        val keystream = cipher.doFinal(iv)

        val encryptedMcp = plainMcp.copyOf()
        val payloadLength = plainMcp.size - 5
        for (i in 0 until payloadLength) {
            encryptedMcp[5 + i] = (plainMcp[5 + i].toInt() xor keystream[i].toInt()).toByte()
        }

        val truncatedMac = computePacketHmac8(netKey, encryptedMcp)

        val mtlPacket = ByteArray(encryptedMcp.size + 9)
        System.arraycopy(encryptedMcp, 0, mtlPacket, 0, encryptedMcp.size)
        System.arraycopy(truncatedMac, 0, mtlPacket, encryptedMcp.size, 8)
        mtlPacket[mtlPacket.size - 1] = ttl

        return mtlPacket
    }

    /**
     * Convenience helper that builds a signed 24-byte Feeric Lights `doCommand(command, param)` packet.
     */
    fun buildFeericCommandPacket(
        command: Int,
        param: Int,
        deviceId: Int = BROADCAST_DEVICE_ID,
        passphrase: String = DEFAULT_PASSPHRASE
    ): ByteArray {
        require(command in 0..255) { "Command must be an unsigned byte (0..255)" }
        require(param in 0..255) { "Param must be an unsigned byte (0..255)" }
        val plain = buildPlainSetRgbPacket(
            deviceId = deviceId,
            red = command,
            green = param,
            blue = 1,
            level = 0xFF,
            duration = 0
        )
        return encryptAndSignMcpPacket(plain, deriveNetworkKey(passphrase))
    }

    /**
     * Builds a standard CSRMesh `setRgb` packet (for the experimental Labo RGB tester).
     */
    fun buildRawRgbPacket(
        red: Int,
        green: Int,
        blue: Int,
        level: Int = 0xFF,
        deviceId: Int = BROADCAST_DEVICE_ID,
        passphrase: String = DEFAULT_PASSPHRASE
    ): ByteArray {
        val plain = buildPlainSetRgbPacket(
            deviceId = deviceId,
            red = red.coerceIn(0, 255),
            green = green.coerceIn(0, 255),
            blue = blue.coerceIn(0, 255),
            level = level.coerceIn(0, 255),
            duration = 0
        )
        return encryptAndSignMcpPacket(plain, deriveNetworkKey(passphrase))
    }

    /**
     * Builds an unacknowledged 19-byte CSRMesh `LightModelApi.setLevel` packet (`0x8A 0x00 <level>`)
     * to ensure the hardware PWM level of the CSR101x controller is at 100% (`0xFF`).
     */
    fun buildLightSetLevelPacket(
        deviceId: Int = DEFAULT_ASSIGNED_DEVICE_ID,
        level: Int = 0xFF,
        passphrase: String = DEFAULT_PASSPHRASE
    ): ByteArray {
        val seqNumber = nextSequenceNumber()
        val pdu = byteArrayOf(
            (seqNumber and 0xFF).toByte(),
            ((seqNumber ushr 8) and 0xFF).toByte(),
            ((seqNumber ushr 16) and 0xFF).toByte(),
            (CONTROLLER_SOURCE_ID and 0xFF).toByte(),
            ((CONTROLLER_SOURCE_ID ushr 8) and 0xFF).toByte(),
            (deviceId and 0xFF).toByte(),
            ((deviceId ushr 8) and 0xFF).toByte(),
            OPCODE_LIGHT_MODEL,
            0x00, // SUBOPCODE_SET_LEVEL_NO_ACK
            (level and 0xFF).toByte()
        )
        return encryptAndSignMcpPacket(pdu, deriveNetworkKey(passphrase))
    }

    /**
     * Builds a signed 23-byte CSRMesh `GroupModelApi.setModelGroupId` packet (`0x10`)
     * to subscribe a model (e.g. `LightModel = 0x14`, `PowerModel = 0x13`) to Group `0x0000`.
     */
    fun buildGroupSetModelGroupIdPacket(
        deviceId: Int = DEFAULT_ASSIGNED_DEVICE_ID,
        modelNo: Int,
        groupIndex: Int = 0,
        instance: Int = 0,
        groupId: Int = BROADCAST_DEVICE_ID,
        tid: Int = 1,
        passphrase: String = DEFAULT_PASSPHRASE
    ): ByteArray {
        val seqNumber = nextSequenceNumber()
        val pdu = byteArrayOf(
            (seqNumber and 0xFF).toByte(),
            ((seqNumber ushr 8) and 0xFF).toByte(),
            ((seqNumber ushr 16) and 0xFF).toByte(),
            (CONTROLLER_SOURCE_ID and 0xFF).toByte(),
            ((CONTROLLER_SOURCE_ID ushr 8) and 0xFF).toByte(),
            (deviceId and 0xFF).toByte(),
            ((deviceId ushr 8) and 0xFF).toByte(),
            0x10,
            (modelNo and 0xFF).toByte(),
            (groupIndex and 0xFF).toByte(),
            (instance and 0xFF).toByte(),
            (groupId and 0xFF).toByte(),
            ((groupId ushr 8) and 0xFF).toByte(),
            (tid and 0xFF).toByte()
        )
        return encryptAndSignMcpPacket(pdu, deriveNetworkKey(passphrase))
    }

    /**
     * Splits a CSRMesh MTL packet into the BLE GATT writes required by `LeBearer.sendPacket`:
     * - If `<= 20` bytes (e.g., `MASP_ID_DISTRIBUTE` = 17B): single write to `8004` (`second == null`)
     * - If `> 20` bytes: first 20 bytes to `8003`, remaining bytes to `8004`
     */
    fun splitForGattWrite(mtlPacket: ByteArray): Pair<ByteArray, ByteArray?> {
        return if (mtlPacket.size <= 20) {
            Pair(mtlPacket, null)
        } else {
            val first = mtlPacket.copyOfRange(0, 20)
            val second = mtlPacket.copyOfRange(20, mtlPacket.size)
            Pair(first, second)
        }
    }

    fun toHexString(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    private fun nextSequenceNumber(): Int =
        sequenceCounter.incrementAndGet() and 0xFFFFFF
}
