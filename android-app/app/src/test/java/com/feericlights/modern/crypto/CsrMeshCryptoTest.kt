package com.feericlights.modern.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CsrMeshCryptoTest {

    @Test
    fun `deriveNetworkKey matches exact SHA256 reversed vector for 8888`() {
        val key = CsrMeshCrypto.deriveNetworkKey("8888")
        val expected = byteArrayOf(
            0x25, 0xE6.toByte(), 0x3C, 0x5E, 0x27, 0x94.toByte(), 0x6F, 0xD0.toByte(),
            0x8E.toByte(), 0xFD.toByte(), 0x46, 0x31, 0x61, 0x7F, 0x55, 0xEE.toByte()
        )
        assertArrayEquals(expected, key)
    }

    @Test
    fun `buildFeericCommandPacket produces 24-byte MTL packet and splits into 20+4 bytes`() {
        val packet = CsrMeshCrypto.buildFeericCommandPacket(command = 5, param = 4)
        assertEquals(24, packet.size)
        // Last byte is TTL = 0x37 (CSRMesh MCP default TTL)
        assertEquals(0x37.toByte(), packet[23])
        // Source ID at bytes 3..4 is 0x8000 in little-endian (0x00, 0x80)
        assertEquals(0x00.toByte(), packet[3])
        assertEquals(0x80.toByte(), packet[4])

        val (first, second) = CsrMeshCrypto.splitForGattWrite(packet)
        assertEquals(20, first.size)
        assertEquals(4, second?.size)
    }
}
