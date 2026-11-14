package com.feericlights.modern.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.feericlights.modern.crypto.CsrMeshCrypto
import com.feericlights.modern.crypto.P192Curve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    BLUETOOTH_OFF,
    PERMISSION_REQUIRED
}

data class DiscoveredGarland(
    val name: String,
    val address: String,
    val rssi: Int
)

/**
 * Modern, rock-solid BLE GATT manager for CSRMesh garlands (`0xFEF1`).
 *
 * Includes:
 * 1. Consistent `"ble.address"` preference key for instant <1s direct GATT reconnection.
 * 2. No `-55 dBm` RSSI artificial block.
 * 3. Automatic **CSRMesh MASP Association** (`P-192` ECDH + `MASPKEY`) if the garland was
 *    factory-reset by double-clicking the physical controller button (blinking blue/purple mode).
 * 4. Dual-target command delivery (`0x0000` broadcast + `0x8001` associated Device ID).
 */
@SuppressLint("MissingPermission")
class CsrMeshBleManager(context: Context) {

    companion object {
        /** CSRMesh BLE Service UUID (`LeBearer.smali` line 176) */
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FEF1-0000-1000-8000-00805F9B34FB")

        /** Standard CSRMesh MTL Control Point characteristics (`LeBearer.smali` lines 183-195) */
        val MTL_COMPLETE_CP_UUID: UUID = UUID.fromString("C4EDC000-9DAF-11E3-8003-00025B000B00")     // Index 1 (first 20B)
        val MTL_CONTINUATION_CP_UUID: UUID = UUID.fromString("C4EDC000-9DAF-11E3-8004-00025B000B00") // Index 0 (last bytes / short pkt)

        /** Alternative/Legacy CSRMesh characteristics (`LeBearer.smali` lines 202-214) */
        val MTL_LEGACY_0_UUID: UUID = UUID.fromString("0000D011-D102-11E1-9B23-00025B00A5A5")
        val MTL_LEGACY_1_UUID: UUID = UUID.fromString("0000D012-D102-11E1-9B23-00025B00A5A5")

        /** Client Characteristic Configuration Descriptor */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val PREFS_NAME = "feeric_lights_prefs"
        private const val KEY_SAVED_MAC = "ble.address"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private val MAC_ADDRESS_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var isScanning = false
    private var useLegacyCharacteristics = false

    private data class GattWriteStep(
        val characteristicUuid: UUID,
        val bytes: ByteArray
    )

    private val writeQueue = ConcurrentLinkedQueue<GattWriteStep>()
    @Volatile
    private var isWritingCharacteristic = false

    /** Reassembly buffer for incoming 2-part BLE notifications (`8003` + `8004`) */
    private var pendingNotifyLowChunk: ByteArray? = null
    private val rxMaspQueue = LinkedBlockingQueue<ByteArray>()
    private val isAssociatingMasp = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(getSavedMacAddress())
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _discoveredGarlands = MutableStateFlow<List<DiscoveredGarland>>(emptyList())
    val discoveredGarlands: StateFlow<List<DiscoveredGarland>> = _discoveredGarlands.asStateFlow()

    private val _statusMessage = MutableStateFlow("Prêt à connecter la guirlande")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _lastSentPacketHex = MutableStateFlow<String?>(null)
    val lastSentPacketHex: StateFlow<String?> = _lastSentPacketHex.asStateFlow()

    fun getSavedMacAddress(): String? {
        val raw = prefs.getString(KEY_SAVED_MAC, null) ?: return null
        return if (MAC_ADDRESS_REGEX.matches(raw)) raw else null
    }

    private fun saveMacAddress(address: String) {
        if (MAC_ADDRESS_REGEX.matches(address)) {
            prefs.edit().putString(KEY_SAVED_MAC, address).apply()
            _connectedDeviceAddress.value = address
        }
    }

    fun clearSavedMacAddress() {
        prefs.edit().remove(KEY_SAVED_MAC).apply()
        _connectedDeviceAddress.value = null
        _statusMessage.value = "Mémoire d'appareil effacée"
    }

    fun markPermissionRequired() {
        _connectionState.value = BleConnectionState.PERMISSION_REQUIRED
        _statusMessage.value = "Autorisation Bluetooth requise (Android 12+)"
    }

    fun autoConnectOrScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.BLUETOOTH_OFF
            _statusMessage.value = "Veuillez activer le Bluetooth"
            return
        }

        if (_connectionState.value == BleConnectionState.CONNECTED) {
            return
        }

        val savedMac = getSavedMacAddress()
        if (savedMac != null && BluetoothAdapter.checkBluetoothAddress(savedMac)) {
            try {
                val device = adapter.getRemoteDevice(savedMac)
                _connectionState.value = BleConnectionState.CONNECTING
                _statusMessage.value = "Reconnexion rapide à $savedMac..."
                connectToDevice(device)
                mainHandler.postDelayed({
                    if (_connectionState.value != BleConnectionState.CONNECTED) {
                        startBleScan()
                    }
                }, 3_000L)
                return
            } catch (_: IllegalArgumentException) {
            }
        }

        startBleScan()
    }

    fun startBleScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.BLUETOOTH_OFF
            _statusMessage.value = "Veuillez activer le Bluetooth"
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: return
        if (isScanning) {
            stopBleScan()
        }

        _discoveredGarlands.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING
        _statusMessage.value = "Recherche de la guirlande Feeric Lights (0xFEF1)..."
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (_: Exception) {
            isScanning = false
            _connectionState.value = BleConnectionState.DISCONNECTED
            _statusMessage.value = "Impossible de démarrer le scan BLE"
            return
        }

        mainHandler.removeCallbacks(stopScanRunnable)
        mainHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
    }

    fun stopBleScan() {
        mainHandler.removeCallbacks(stopScanRunnable)
        if (isScanning) {
            isScanning = false
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
        }
    }

    private val stopScanRunnable = Runnable {
        if (isScanning) {
            stopBleScan()
            if (_connectionState.value == BleConnectionState.SCANNING) {
                _connectionState.value = BleConnectionState.DISCONNECTED
                _statusMessage.value = if (_discoveredGarlands.value.isEmpty()) {
                    "Aucune guirlande détectée à proximité"
                } else {
                    "Sélectionnez votre guirlande dans la liste"
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val scanRecord = result.scanRecord
            val serviceUuids: List<ParcelUuid>? = scanRecord?.serviceUuids
            val deviceName = device.name ?: scanRecord?.deviceName ?: "Feeric Garland"

            val advertisesCsrMesh = serviceUuids?.any { it.uuid == MESH_SERVICE_UUID } == true
            val isSavedDevice = address.equals(getSavedMacAddress(), ignoreCase = true)
            val nameLooksLikeGarland = deviceName.contains("CSR", ignoreCase = true) ||
                deviceName.contains("Mesh", ignoreCase = true) ||
                deviceName.contains("Feeric", ignoreCase = true) ||
                deviceName.contains("Light", ignoreCase = true) ||
                deviceName.contains("iSmart", ignoreCase = true)

            if (!advertisesCsrMesh && !isSavedDevice && !nameLooksLikeGarland) {
                return
            }

            val entry = DiscoveredGarland(
                name = deviceName,
                address = address,
                rssi = result.rssi
            )
            val currentList = _discoveredGarlands.value.toMutableList()
            val existingIdx = currentList.indexOfFirst { it.address == address }
            if (existingIdx >= 0) {
                currentList[existingIdx] = entry
            } else {
                currentList.add(entry)
            }
            _discoveredGarlands.value = currentList.sortedByDescending { it.rssi }

            if ((advertisesCsrMesh || isSavedDevice || nameLooksLikeGarland) &&
                _connectionState.value != BleConnectionState.CONNECTING &&
                _connectionState.value != BleConnectionState.CONNECTED
            ) {
                stopBleScan()
                connectToDevice(device)
            }
        }
    }

    fun connectByAddress(address: String) {
        if (!MAC_ADDRESS_REGEX.matches(address)) return
        val adapter = bluetoothAdapter ?: return
        stopBleScan()
        try {
            val device = adapter.getRemoteDevice(address)
            connectToDevice(device)
        } catch (_: Exception) {
            _statusMessage.value = "Adresse Bluetooth invalide"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        activeGatt?.let { oldGatt ->
            try {
                oldGatt.disconnect()
                oldGatt.close()
            } catch (_: Exception) {
            }
            activeGatt = null
        }

        writeQueue.clear()
        isWritingCharacteristic = false
        pendingNotifyLowChunk = null
        rxMaspQueue.clear()
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = device.name ?: "Feeric Lights"
        _statusMessage.value = "Connexion GATT à ${device.address}..."

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    fun disconnect() {
        stopBleScan()
        writeQueue.clear()
        isWritingCharacteristic = false
        pendingNotifyLowChunk = null
        rxMaspQueue.clear()
        activeGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Exception) {
            }
        }
        activeGatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        _statusMessage.value = "Déconnecté"
    }

    /**
     * Encrypts and sends a Feeric Lights command (`command`, `param`) over the CSRMesh GATT bridge.
     * Sends to both `0x0000` (broadcast) and `0x8001` (first associated device ID) for maximum compatibility.
     */
    fun sendFeericCommand(command: Int, param: Int) {
        val broadcastPacket = CsrMeshCrypto.buildFeericCommandPacket(
            command = command,
            param = param,
            deviceId = CsrMeshCrypto.BROADCAST_DEVICE_ID
        )
        val unicastPacket = CsrMeshCrypto.buildFeericCommandPacket(
            command = command,
            param = param,
            deviceId = CsrMeshCrypto.DEFAULT_ASSIGNED_DEVICE_ID
        )
        enqueueMtlPacket(broadcastPacket)
        enqueueMtlPacket(unicastPacket)
    }

    /**
     * Encrypts and sends a raw CSRMesh RGB packet (`RED`, `GREEN`, `BLUE`) for the experimental Labo.
     */
    fun sendRawRgb(red: Int, green: Int, blue: Int, level: Int = 0xFF) {
        val broadcastPacket = CsrMeshCrypto.buildRawRgbPacket(
            red = red,
            green = green,
            blue = blue,
            level = level,
            deviceId = CsrMeshCrypto.BROADCAST_DEVICE_ID
        )
        val unicastPacket = CsrMeshCrypto.buildRawRgbPacket(
            red = red,
            green = green,
            blue = blue,
            level = level,
            deviceId = CsrMeshCrypto.DEFAULT_ASSIGNED_DEVICE_ID
        )
        enqueueMtlPacket(broadcastPacket)
        enqueueMtlPacket(unicastPacket)
    }

    private fun enqueueMtlPacket(mtlPacket: ByteArray) {
        _lastSentPacketHex.value = CsrMeshCrypto.toHexString(mtlPacket)

        if (_connectionState.value != BleConnectionState.CONNECTED || activeGatt == null) {
            _statusMessage.value = "Non connecté — Trame préparée (${mtlPacket.size} octets)"
            return
        }

        val (firstChunk, secondChunk) = CsrMeshCrypto.splitForGattWrite(mtlPacket)
        if (secondChunk == null) {
            val uuid = if (useLegacyCharacteristics) MTL_LEGACY_1_UUID else MTL_CONTINUATION_CP_UUID
            writeQueue.add(GattWriteStep(uuid, firstChunk))
        } else {
            val firstUuid = if (useLegacyCharacteristics) MTL_LEGACY_0_UUID else MTL_COMPLETE_CP_UUID
            val secondUuid = if (useLegacyCharacteristics) MTL_LEGACY_1_UUID else MTL_CONTINUATION_CP_UUID
            writeQueue.add(GattWriteStep(firstUuid, firstChunk))
            writeQueue.add(GattWriteStep(secondUuid, secondChunk))
        }

        drainWriteQueue()
    }

    @Synchronized
    private fun drainWriteQueue() {
        if (isWritingCharacteristic) return
        val gatt = activeGatt ?: return
        val nextStep = writeQueue.poll() ?: return

        val service = gatt.getService(MESH_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(nextStep.characteristicUuid) ?: return

        isWritingCharacteristic = true
        val initiated = writeCharacteristicCompat(gatt, characteristic, nextStep.bytes)
        if (!initiated) {
            isWritingCharacteristic = false
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun handleIncomingMtlPacket(mtlPacket: ByteArray) {
        val plainMasp = CsrMeshCrypto.tryUnwrapMaspPacket(mtlPacket) ?: return
        val opcode = plainMasp[0].toInt() and 0xFF

        if (opcode == 0x00 || opcode == 0x01) {
            // Unassociated garland beacon detected! Automatically associate it if not already running.
            val uuidHash4 = CsrMeshCrypto.extractUuidHash4FromMaspBeacon(plainMasp) ?: return
            if (isAssociatingMasp.compareAndSet(false, true)) {
                Thread {
                    try {
                        runAutoMaspAssociation(uuidHash4)
                    } finally {
                        isAssociatingMasp.set(false)
                    }
                }.start()
            }
        } else {
            rxMaspQueue.offer(plainMasp)
        }
    }

    private fun sendMaspAndWaitForOpcode(
        plainReq: ByteArray,
        expectedOpcode: Int,
        expectedByte5: Int = -1,
        maxRetries: Int = 5
    ): ByteArray? {
        val rng = SecureRandom()
        var seqByte = rng.nextInt(256).toByte()

        for (attempt in 1..maxRetries) {
            plainReq[plainReq.size - 1] = seqByte
            seqByte = (seqByte + 1).toByte()

            val wrapped = CsrMeshCrypto.wrapMaspPacket(plainReq)
            enqueueMtlPacket(wrapped)

            val deadline = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < deadline) {
                val rx = rxMaspQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val op = rx[0].toInt() and 0xFF
                if (rx.size >= 5 && op == expectedOpcode) {
                    if (expectedByte5 < 0 || (rx.size > 5 && (rx[5].toInt() and 0xFF) == expectedByte5)) {
                        return rx
                    }
                }
            }
        }
        return null
    }

    /**
     * Automatically provisions a factory-reset garland (`MASP_DEVICE_APPEARANCE` / `0x00`)
     * with `DeviceID = 0x8001` and `NETKEY("8888")` so it stops blinking and accepts commands.
     */
    private fun runAutoMaspAssociation(uuidHash4: ByteArray) {
        _statusMessage.value = "Guirlande réinitialisée détectée — Association MASP en cours (0/6)..."
        rxMaspQueue.clear()

        val rng = SecureRandom()
        val privBytes = ByteArray(24)
        rng.nextBytes(privBytes)
        privBytes[23] = (privBytes[23].toInt() and 0x7F).toByte()
        var privK = P192Curve.from24BytesLE(privBytes)
        if (privK <= BigInteger.ONE || privK >= P192Curve.N) {
            privK = BigInteger.valueOf(1234567890123456789L)
        }

        val pubPt = P192Curve.scalarMult(privK, P192Curve.G) ?: return
        val pubX_LE = P192Curve.to24BytesLE(pubPt.x)
        val pubY_LE = P192Curve.to24BytesLE(pubPt.y)

        val localPubKey48 = ByteArray(48)
        System.arraycopy(pubY_LE, 0, localPubKey48, 0, 24)
        System.arraycopy(pubX_LE, 0, localPubKey48, 24, 24)

        // Step 1: MASP_ASSOC_REQUEST (0x02) -> MASP_ASSOC_RESPONSE (0x03)
        val assocReq = ByteArray(15)
        assocReq[0] = 0x02
        System.arraycopy(uuidHash4, 0, assocReq, 1, 4)
        assocReq[5] = 0x00
        assocReq[6] = 0x01

        val assocResp = sendMaspAndWaitForOpcode(assocReq, 0x03) ?: run {
            _statusMessage.value = "Échec association MASP (étape 1)"
            return
        }
        val assocRole = assocResp[5].toInt() and 0xFF

        // Step 2: Public Key exchange (6 chunks)
        val remY_LE = ByteArray(24)
        val remX_LE = ByteArray(24)
        for (h in 0 until 6) {
            _statusMessage.value = "Association MASP : échange clé ECDH (${h + 1}/6)..."
            val pubKeyReq = ByteArray(15)
            pubKeyReq[0] = 0x04
            System.arraycopy(uuidHash4, 0, pubKeyReq, 1, 4)
            pubKeyReq[5] = h.toByte()
            System.arraycopy(localPubKey48, h * 8, pubKeyReq, 6, 8)

            val pubKeyResp = sendMaspAndWaitForOpcode(pubKeyReq, 0x05, h) ?: run {
                _statusMessage.value = "Échec association MASP (clé ECDH $h)"
                return
            }
            if (h < 3) {
                System.arraycopy(pubKeyResp, 6, remY_LE, h * 8, 8)
            } else {
                System.arraycopy(pubKeyResp, 6, remX_LE, (h - 3) * 8, 8)
            }
        }

        val remX = P192Curve.from24BytesLE(remX_LE)
        val remY = P192Curve.from24BytesLE(remY_LE)
        val sharedPt = P192Curve.scalarMult(privK, P192Curve.Point(remX, remY)) ?: return
        val sharedSecret24 = P192Curve.to24BytesLE(sharedPt.x)

        // Step 3: MASP_CONFIRM_REQUEST (0x06) -> MASP_CONFIRM_RESPONSE (0x07)
        val localRandom8 = ByteArray(8)
        rng.nextBytes(localRandom8)
        val confirmData = if (assocRole == 0) {
            ByteArray(56).also { System.arraycopy(localPubKey48, 0, it, 0, 48) }
        } else {
            ByteArray(66).also {
                it[0] = 0x01
                it[8] = 0x01
                it[9] = 0x00
                System.arraycopy(localPubKey48, 0, it, 10, 48)
            }
        }
        val localConfirm8 = CsrMeshCrypto.hmacSha256TruncReversed(confirmData, localRandom8, 8)

        val confirmReq = ByteArray(14)
        confirmReq[0] = 0x06
        System.arraycopy(uuidHash4, 0, confirmReq, 1, 4)
        System.arraycopy(localConfirm8, 0, confirmReq, 5, 8)
        sendMaspAndWaitForOpcode(confirmReq, 0x07) ?: run {
            _statusMessage.value = "Échec association MASP (confirmation)"
            return
        }

        // Step 4: MASP_RANDOM_REQUEST (0x08) -> MASP_RANDOM_RESPONSE (0x09)
        val randomReq = ByteArray(14)
        randomReq[0] = 0x08
        System.arraycopy(uuidHash4, 0, randomReq, 1, 4)
        System.arraycopy(localRandom8, 0, randomReq, 5, 8)
        sendMaspAndWaitForOpcode(randomReq, 0x09) ?: run {
            _statusMessage.value = "Échec association MASP (random)"
            return
        }

        // Step 5: MASP_ID_DISTRIBUTE (0x0A, 17B written directly to 8004) -> MASP_ID_DISTRIBUTE_ACK (0x0B)
        _statusMessage.value = "Association MASP : attribution ID 0x8001..."
        val devId = CsrMeshCrypto.DEFAULT_ASSIGNED_DEVICE_ID
        val devIdLe = byteArrayOf((devId and 0xFF).toByte(), ((devId ushr 8) and 0xFF).toByte())
        val devIdMask = CsrMeshCrypto.hmacSha256TruncReversed(
            "DeviceID".toByteArray(StandardCharsets.US_ASCII),
            sharedSecret24,
            2
        )
        val idDistReq = ByteArray(8)
        idDistReq[0] = 0x0A
        System.arraycopy(uuidHash4, 0, idDistReq, 1, 4)
        idDistReq[5] = (devIdLe[0].toInt() xor devIdMask[0].toInt()).toByte()
        idDistReq[6] = (devIdLe[1].toInt() xor devIdMask[1].toInt()).toByte()
        sendMaspAndWaitForOpcode(idDistReq, 0x0B) ?: run {
            _statusMessage.value = "Échec association MASP (Device ID)"
            return
        }

        // Step 6: MASP_KEY_DISTRIBUTE (0x0C, 2 chunks) -> MASP_KEY_DISTRIBUTE_ACK (0x0D)
        _statusMessage.value = "Association MASP : envoi clé réseau 8888..."
        val netKey16 = CsrMeshCrypto.deriveNetworkKey(CsrMeshCrypto.DEFAULT_PASSPHRASE)
        val netKeyMask = CsrMeshCrypto.hmacSha256TruncReversed(
            "NetworkKey".toByteArray(StandardCharsets.US_ASCII),
            sharedSecret24,
            16
        )
        val encNetKey16 = ByteArray(16) { i -> (netKey16[i].toInt() xor netKeyMask[i].toInt()).toByte() }

        for (h in 0 until 2) {
            val keyDistReq = ByteArray(15)
            keyDistReq[0] = 0x0C
            System.arraycopy(uuidHash4, 0, keyDistReq, 1, 4)
            keyDistReq[5] = h.toByte()
            System.arraycopy(encNetKey16, h * 8, keyDistReq, 6, 8)
            sendMaspAndWaitForOpcode(keyDistReq, 0x0D, h) ?: run {
                _statusMessage.value = "Échec association MASP (NetworkKey $h)"
                return
            }
        }

        // Assign Group 0 (0x0000 = Broadcast / All items) to LightModel (0x14) and PowerModel (0x13)
        enqueueMtlPacket(CsrMeshCrypto.buildGroupSetModelGroupIdPacket(modelNo = 0x14, tid = 0x03))
        enqueueMtlPacket(CsrMeshCrypto.buildGroupSetModelGroupIdPacket(modelNo = 0x13, tid = 0x04))
        enqueueMtlPacket(CsrMeshCrypto.buildLightSetLevelPacket(deviceId = CsrMeshCrypto.DEFAULT_ASSIGNED_DEVICE_ID, level = 0xFF))
        enqueueMtlPacket(CsrMeshCrypto.buildLightSetLevelPacket(deviceId = CsrMeshCrypto.BROADCAST_DEVICE_ID, level = 0xFF))

        _statusMessage.value = "Guirlande associée avec succès (Clé 8888 & ID 0x8001) !"
        // Initialize all LED ramps (cmd 4 = 3 MULTI), 100% brightness (cmd 2 = 0x7F), Steady On (cmd 1 = 6)
        sendFeericCommand(4, 3)
        sendFeericCommand(2, 0x7F)
        sendFeericCommand(1, 6)
        sendFeericCommand(5, 6)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    saveMacAddress(address)
                    _connectedDeviceName.value = gatt.device.name ?: "Feeric Lights"
                    _statusMessage.value = "Découverte du service CSRMesh (0xFEF1)..."
                    gatt.readRemoteRssi()
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeQueue.clear()
                    isWritingCharacteristic = false
                    pendingNotifyLowChunk = null
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (activeGatt == gatt) {
                        activeGatt = null
                    }
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    _statusMessage.value = if (status == 133) {
                        "Lien BLE interrompu (code 133) — Appuyez pour reconnecter"
                    } else {
                        "Guirlande déconnectée"
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssiValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssiValue
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val meshService = gatt.getService(MESH_SERVICE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || meshService == null) {
                _statusMessage.value = "Service CSRMesh (0xFEF1) introuvable sur cet appareil"
                gatt.disconnect()
                return
            }

            useLegacyCharacteristics = meshService.getCharacteristic(MTL_LEGACY_0_UUID) != null

            val notifyUuid0 = if (useLegacyCharacteristics) MTL_LEGACY_0_UUID else MTL_CONTINUATION_CP_UUID
            val char0 = meshService.getCharacteristic(notifyUuid0)
            if (char0 != null) {
                enableNotification(gatt, char0)
            } else {
                onBridgeReady(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val meshService = gatt.getService(MESH_SERVICE_UUID) ?: return
            val notifyUuid0 = if (useLegacyCharacteristics) MTL_LEGACY_0_UUID else MTL_CONTINUATION_CP_UUID
            val notifyUuid1 = if (useLegacyCharacteristics) MTL_LEGACY_1_UUID else MTL_COMPLETE_CP_UUID

            if (descriptor.characteristic.uuid == notifyUuid0) {
                val char1 = meshService.getCharacteristic(notifyUuid1)
                if (char1 != null) {
                    enableNotification(gatt, char1)
                } else {
                    onBridgeReady(gatt)
                }
            } else {
                onBridgeReady(gatt)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onCharacteristicNotification(characteristic.uuid, value.copyOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onCharacteristicNotification(characteristic.uuid, value.copyOf())
        }

        private fun onCharacteristicNotification(uuid: UUID, bytes: ByteArray) {
            val isLowPart = uuid == MTL_COMPLETE_CP_UUID || uuid == MTL_LEGACY_0_UUID
            if (isLowPart) {
                pendingNotifyLowChunk = bytes
            } else {
                val low = pendingNotifyLowChunk
                pendingNotifyLowChunk = null
                val full = if (low != null) low + bytes else bytes
                handleIncomingMtlPacket(full)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(this@CsrMeshBleManager) {
                isWritingCharacteristic = false
                drainWriteQueue()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                onBridgeReady(gatt)
            }
        } catch (_: Exception) {
            onBridgeReady(gatt)
        }
    }

    private fun onBridgeReady(gatt: BluetoothGatt) {
        _connectionState.value = BleConnectionState.CONNECTED
        _statusMessage.value = "Connecté à ${gatt.device.name ?: gatt.device.address} (Clé 8888 active)"
        drainWriteQueue()
        // If already associated (no MASP beacon arrives within 2.5s), ensure group 0x0000 & full brightness on all LED ramps
        Thread {
            try {
                Thread.sleep(2500)
                if (_connectionState.value == BleConnectionState.CONNECTED && !isAssociatingMasp.get()) {
                    enqueueMtlPacket(CsrMeshCrypto.buildGroupSetModelGroupIdPacket(modelNo = 0x14, tid = 0x03))
                    enqueueMtlPacket(CsrMeshCrypto.buildLightSetLevelPacket(deviceId = CsrMeshCrypto.DEFAULT_ASSIGNED_DEVICE_ID, level = 0xFF))
                    sendFeericCommand(4, 3)
                    sendFeericCommand(2, 0x7F)
                    sendFeericCommand(1, 6)
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
