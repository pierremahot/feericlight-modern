package com.feericlights.modern.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feericlights.modern.ble.BleConnectionState
import com.feericlights.modern.ble.CsrMeshBleManager
import com.feericlights.modern.ble.DiscoveredGarland
import com.feericlights.modern.model.BrightnessPreset
import com.feericlights.modern.model.GarlandColor
import com.feericlights.modern.model.GarlandMode
import com.feericlights.modern.model.GarlandProtocol
import com.feericlights.modern.model.GarlandTimer
import com.feericlights.modern.model.LedBankMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GarlandUiState(
    val isPoweredOn: Boolean = true,
    val selectedMode: GarlandMode = GarlandMode.STEADY_ON,
    val selectedColor: GarlandColor = GarlandColor.GOLD,
    val brightness: Int = BrightnessPreset.P100.param, // 1..127
    val selectedTimer: GarlandTimer = GarlandTimer.OFF,
    val selectedLedBank: LedBankMode = LedBankMode.MULTI,
    val isMultiColorPreview: Boolean = false,
    val isRainbowActive: Boolean = false,
    val showLaboSection: Boolean = false,
    val lastCommandSummary: String = "Aucune commande envoyée"
)

class GarlandViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = CsrMeshBleManager(application)

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val connectedAddress: StateFlow<String?> = bleManager.connectedDeviceAddress
    val connectedName: StateFlow<String?> = bleManager.connectedDeviceName
    val rssi: StateFlow<Int?> = bleManager.rssi
    val discoveredGarlands: StateFlow<List<DiscoveredGarland>> = bleManager.discoveredGarlands
    val statusMessage: StateFlow<String> = bleManager.statusMessage
    val lastSentPacketHex: StateFlow<String?> = bleManager.lastSentPacketHex

    private val _uiState = MutableStateFlow(GarlandUiState())
    val uiState: StateFlow<GarlandUiState> = _uiState.asStateFlow()
    private var rainbowJob: Job? = null

    private fun stopRainbowCycle() {
        rainbowJob?.cancel()
        rainbowJob = null
        if (_uiState.value.isRainbowActive) {
            _uiState.value = _uiState.value.copy(isRainbowActive = false, isMultiColorPreview = false)
        }
    }

    fun toggleRainbowCycle() {
        if (_uiState.value.isRainbowActive) {
            stopRainbowCycle()
            _uiState.value = _uiState.value.copy(lastCommandSummary = "Mode Arc-en-Ciel arrêté")
            return
        }
        stopRainbowCycle()
        val rainbowOrder = listOf(
            GarlandColor.RED,
            GarlandColor.GOLD,
            GarlandColor.GREEN,
            GarlandColor.CYAN,
            GarlandColor.BLUE,
            GarlandColor.MAGENTA
        )
        _uiState.value = _uiState.value.copy(
            isPoweredOn = true,
            isRainbowActive = true,
            isMultiColorPreview = true,
            lastCommandSummary = "🌈 Mode Arc-en-Ciel actif"
        )
        rainbowJob = viewModelScope.launch {
            bleManager.sendFeericCommand(GarlandProtocol.CMD_LED_CHANNEL_MODE, LedBankMode.MULTI.param)
            bleManager.sendFeericCommand(GarlandProtocol.CMD_MODE_AND_POWER, GarlandMode.STEADY_ON.param)
            var step = 0
            while (isActive) {
                val col = rainbowOrder[step % rainbowOrder.size]
                bleManager.sendFeericCommand(GarlandProtocol.CMD_COLOR_PRESET, col.param)
                _uiState.value = _uiState.value.copy(
                    selectedColor = col,
                    lastCommandSummary = "🌈 Arc-en-Ciel : ${col.nameFr} → doCommand(5, ${col.param})"
                )
                step++
                delay(650L)
            }
        }
    }

    fun onPermissionsGranted() {
        bleManager.autoConnectOrScan()
    }

    fun onPermissionsDenied() {
        bleManager.markPermissionRequired()
    }

    fun startScan() {
        bleManager.startBleScan()
    }

    fun reconnectOrScan() {
        bleManager.autoConnectOrScan()
    }

    fun connectToGarland(address: String) {
        bleManager.connectByAddress(address)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun forgetSavedGarland() {
        bleManager.clearSavedMacAddress()
    }

    fun togglePower() {
        stopRainbowCycle()
        val current = _uiState.value
        if (current.isPoweredOn) {
            // Send Power OFF: doCommand(1, 7)
            bleManager.sendFeericCommand(
                command = GarlandProtocol.CMD_MODE_AND_POWER,
                param = GarlandMode.PARAM_POWER_OFF
            )
            _uiState.value = current.copy(
                isPoweredOn = false,
                lastCommandSummary = "Power OFF → doCommand(1, 7)"
            )
        } else {
            // Restore active mode: doCommand(1, selectedMode.param)
            val mode = current.selectedMode
            bleManager.sendFeericCommand(
                command = GarlandProtocol.CMD_MODE_AND_POWER,
                param = mode.param
            )
            _uiState.value = current.copy(
                isPoweredOn = true,
                lastCommandSummary = "Power ON (${mode.titleFr}) → doCommand(1, ${mode.param})"
            )
        }
    }

    fun selectMode(mode: GarlandMode) {
        stopRainbowCycle()
        bleManager.sendFeericCommand(
            command = GarlandProtocol.CMD_MODE_AND_POWER,
            param = mode.param
        )
        _uiState.value = _uiState.value.copy(
            isPoweredOn = true,
            selectedMode = mode,
            lastCommandSummary = "Mode ${mode.titleFr} → doCommand(1, ${mode.param})"
        )
    }

    fun selectColor(color: GarlandColor) {
        stopRainbowCycle()
        if (!_uiState.value.isPoweredOn) {
            bleManager.sendFeericCommand(
                command = GarlandProtocol.CMD_MODE_AND_POWER,
                param = _uiState.value.selectedMode.param
            )
        }
        bleManager.sendFeericCommand(
            command = GarlandProtocol.CMD_COLOR_PRESET,
            param = color.param
        )
        _uiState.value = _uiState.value.copy(
            isPoweredOn = true,
            selectedColor = color,
            isMultiColorPreview = false,
            lastCommandSummary = "Couleur ${color.nameFr} [${color.binaryMask}] → doCommand(5, ${color.param})"
        )
    }

    fun setBrightness(value: Int, sendImmediately: Boolean = true) {
        val clamped = value.coerceIn(1, 127)
        if (sendImmediately) {
            bleManager.sendFeericCommand(
                command = GarlandProtocol.CMD_BRIGHTNESS,
                param = clamped
            )
        }
        val pct = ((clamped / 127f) * 100).toInt()
        _uiState.value = _uiState.value.copy(
            brightness = clamped,
            lastCommandSummary = if (sendImmediately) {
                "Luminosité $pct% (0x${"%02X".format(clamped)}) → doCommand(2, $clamped)"
            } else {
                _uiState.value.lastCommandSummary
            }
        )
    }

    fun selectBrightnessPreset(preset: BrightnessPreset) {
        bleManager.sendFeericCommand(
            command = GarlandProtocol.CMD_BRIGHTNESS,
            param = preset.param
        )
        _uiState.value = _uiState.value.copy(
            brightness = preset.param,
            lastCommandSummary = "Luminosité ${preset.labelPercent} → doCommand(2, ${preset.param})"
        )
    }

    fun selectTimer(timer: GarlandTimer) {
        bleManager.sendFeericCommand(
            command = GarlandProtocol.CMD_TIMER,
            param = timer.param
        )
        _uiState.value = _uiState.value.copy(
            selectedTimer = timer,
            lastCommandSummary = "Timer ${timer.titleFr} → doCommand(3, ${timer.param})"
        )
    }

    fun selectLedBank(bank: LedBankMode) {
        bleManager.sendFeericCommand(
            command = GarlandProtocol.CMD_LED_CHANNEL_MODE,
            param = bank.param
        )
        _uiState.value = _uiState.value.copy(
            selectedLedBank = bank,
            isMultiColorPreview = (bank == LedBankMode.MULTI),
            lastCommandSummary = "Rampe LED ${bank.titleFr} → doCommand(4, ${bank.param})"
        )
    }

    fun sendExperimentalRawRgb(red: Int, green: Int, blue: Int) {
        // Ensure blue != 1 when testing standard CSRMesh RGB so the garland doesn't confuse it with doCommand
        val safeBlue = if (blue == 1) 2 else blue
        bleManager.sendRawRgb(red = red, green = green, blue = safeBlue)
        _uiState.value = _uiState.value.copy(
            isPoweredOn = true,
            lastCommandSummary = "Test CSRMesh RGB($red, $green, $safeBlue) → opcode 0x8A 0x02"
        )
    }

    fun toggleLaboSection() {
        _uiState.value = _uiState.value.copy(
            showLaboSection = !_uiState.value.showLaboSection
        )
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
