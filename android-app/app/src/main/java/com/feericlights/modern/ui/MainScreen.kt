package com.feericlights.modern.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feericlights.modern.ble.BleConnectionState
import com.feericlights.modern.ble.DiscoveredGarland
import com.feericlights.modern.model.BrightnessPreset
import com.feericlights.modern.model.GarlandColor
import com.feericlights.modern.model.GarlandMode
import com.feericlights.modern.model.GarlandTimer
import com.feericlights.modern.model.LedBankMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val NightSkyBg = Color(0xFF070B19)
private val AuroraCardBg = Color(0xFF111830)
private val AuroraCardBorder = Color(0xFF23315C)
private val AccentGold = Color(0xFFFFD54F)
private val AccentCyan = Color(0xFF00E5FF)
private val AccentGreen = Color(0xFF00E676)
private val AccentCrimson = Color(0xFFFF1744)
private val TextPrimary = Color(0xFFF4F7FF)
private val TextSecondary = Color(0xFF9AA8C7)

private val FeericColorScheme = darkColorScheme(
    primary = AccentGold,
    secondary = AccentCyan,
    background = NightSkyBg,
    surface = AuroraCardBg,
    onPrimary = Color(0xFF070B19),
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun MainScreen(
    viewModel: GarlandViewModel,
    onRequestBlePermissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedAddress by viewModel.connectedAddress.collectAsState()
    val connectedName by viewModel.connectedName.collectAsState()
    val rssi by viewModel.rssi.collectAsState()
    val discoveredGarlands by viewModel.discoveredGarlands.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val lastPacketHex by viewModel.lastSentPacketHex.collectAsState()

    MaterialTheme(colorScheme = FeericColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = NightSkyBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0A1128),
                                Color(0xFF070B19),
                                Color(0xFF050711)
                            )
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Top Title + Power Toggle Button
                TopHeaderBar(
                    isPoweredOn = uiState.isPoweredOn,
                    activeColor = uiState.selectedColor.displayColor,
                    onTogglePower = { viewModel.togglePower() }
                )

                // 2. Real-time Animated Garland Preview Card
                AnimatedGarlandHeroCard(
                    uiState = uiState
                )

                // 3. Bluetooth Connection & Instant Reconnect Card
                BleConnectionCard(
                    connectionState = connectionState,
                    connectedName = connectedName,
                    connectedAddress = connectedAddress,
                    rssi = rssi,
                    statusMessage = statusMessage,
                    discoveredGarlands = discoveredGarlands,
                    onRequestPermissions = onRequestBlePermissions,
                    onReconnectOrScan = { viewModel.reconnectOrScan() },
                    onStartScan = { viewModel.startScan() },
                    onSelectGarland = { viewModel.connectToGarland(it) },
                    onDisconnect = { viewModel.disconnect() },
                    onForgetSaved = { viewModel.forgetSavedGarland() }
                )

                // 4. 7 Hardware Colors Selector (Command = 5, 3-bit RGB mask) + Rainbow Mode
                HardwareColorPickerCard(
                    selectedColor = uiState.selectedColor,
                    isMultiColor = uiState.isMultiColorPreview,
                    isRainbowActive = uiState.isRainbowActive,
                    onSelectColor = { viewModel.selectColor(it) },
                    onToggleRainbow = { viewModel.toggleRainbowCycle() }
                )

                // 5. Animation Modes Card (Command = 1)
                AnimationModesCard(
                    selectedMode = uiState.selectedMode,
                    isPoweredOn = uiState.isPoweredOn,
                    activeColor = uiState.selectedColor.displayColor,
                    onSelectMode = { viewModel.selectMode(it) }
                )

                // 6. Brightness & Speed Card (Command = 2, 1..127)
                BrightnessControlCard(
                    brightness = uiState.brightness,
                    activeColor = uiState.selectedColor.displayColor,
                    onBrightnessDrag = { viewModel.setBrightness(it, sendImmediately = false) },
                    onBrightnessCommit = { viewModel.setBrightness(it, sendImmediately = true) },
                    onSelectPreset = { viewModel.selectBrightnessPreset(it) }
                )

                // 7. Timer Card (Command = 3)
                TimerControlCard(
                    selectedTimer = uiState.selectedTimer,
                    onSelectTimer = { viewModel.selectTimer(it) }
                )

                // 8. Expandable Labo / Hidden Features & Protocol Inspector
                ExperimentalLaboCard(
                    uiState = uiState,
                    lastPacketHex = lastPacketHex,
                    onToggleExpand = { viewModel.toggleLaboSection() },
                    onSelectExperimentalMode = { viewModel.selectMode(it) },
                    onSelectLedBank = { viewModel.selectLedBank(it) },
                    onSendRawRgb = { r, g, b -> viewModel.sendExperimentalRawRgb(r, g, b) }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TopHeaderBar(
    isPoweredOn: Boolean,
    activeColor: Color,
    onTogglePower: () -> Unit
) {
    val powerBg by animateColorAsState(
        targetValue = if (isPoweredOn) AccentCrimson else Color(0xFF1D2746),
        label = "powerBg"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Feeric Lights",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentGold.copy(alpha = 0.16f))
                        .border(1.dp, AccentGold.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "PIXEL EDITION",
                        color = AccentGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "Contrôleur Bluetooth CSRMesh • Clé 8888",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Tactile Power ON/OFF Pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(powerBg)
                .border(
                    width = 1.dp,
                    color = if (isPoweredOn) Color.White.copy(alpha = 0.35f) else AuroraCardBorder,
                    shape = RoundedCornerShape(50)
                )
                .clickable { onTogglePower() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Allumer ou éteindre",
                tint = if (isPoweredOn) Color.White else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPoweredOn) "ON" else "OFF",
                color = if (isPoweredOn) Color.White else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Interactive animated Christmas garland canvas reflecting the current mode, color, and brightness.
 */
@Composable
private fun AnimatedGarlandHeroCard(uiState: GarlandUiState) {
    val infiniteTransition = rememberInfiniteTransition(label = "garlandAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val allColors = remember { GarlandColor.entries.map { it.displayColor } }
    val brightnessFactor = (uiState.brightness.coerceIn(10, 127) / 127f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF141E3C),
                        Color(0xFF0C1328)
                    )
                )
            )
            .border(1.dp, AuroraCardBorder, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (!uiState.isPoweredOn) {
                        "Guirlande éteinte"
                    } else {
                        "${uiState.selectedMode.titleFr} • ${
                            if (uiState.isMultiColorPreview) "Multi-Couleurs" else uiState.selectedColor.nameFr
                        }"
                    },
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Intensité ${((uiState.brightness / 127f) * 100).toInt()}%",
                    color = AccentGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
            ) {
                val width = size.width
                val height = size.height
                val bulbCount = 13

                // Draw festive draped wire across 2 arcs
                val wirePath = Path().apply {
                    moveTo(0f, height * 0.22f)
                    quadraticTo(width * 0.25f, height * 0.72f, width * 0.5f, height * 0.26f)
                    quadraticTo(width * 0.75f, height * 0.72f, width, height * 0.22f)
                }
                drawPath(
                    path = wirePath,
                    color = Color(0xFF2E436E),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )

                for (i in 0 until bulbCount) {
                    val t = (i + 0.5f) / bulbCount.toFloat()
                    // Compute y along the two quadratic arcs
                    val localT = if (t < 0.5f) t * 2f else (t - 0.5f) * 2f
                    val y0 = if (t < 0.5f) height * 0.22f else height * 0.26f
                    val y1 = height * 0.72f
                    val y2 = if (t < 0.5f) height * 0.26f else height * 0.22f
                    val oneMinus = 1f - localT
                    val wireY = oneMinus * oneMinus * y0 + 2f * oneMinus * localT * y1 + localT * localT * y2
                    val bulbX = width * t
                    val bulbY = wireY + 12f

                    // Determine bulb color
                    val baseColor = if (uiState.isMultiColorPreview ||
                        (uiState.selectedMode == GarlandMode.COMBINATION && phase > 0.5f)
                    ) {
                        allColors[i % allColors.size]
                    } else {
                        uiState.selectedColor.displayColor
                    }

                    // Determine animation intensity for bulb i
                    val animAlpha = if (!uiState.isPoweredOn) {
                        0.06f
                    } else {
                        when (uiState.selectedMode) {
                            GarlandMode.STEADY_ON -> 1.0f
                            GarlandMode.TWINKLE -> {
                                val wave = sin((phase * 2f * PI + i * 1.7f)).toFloat()
                                0.25f + 0.75f * ((wave + 1f) * 0.5f)
                            }
                            GarlandMode.SEQUENTIAL -> {
                                val activeIndex = (phase * bulbCount).toInt()
                                val dist = abs(i - activeIndex)
                                when (dist) {
                                    0 -> 1.0f
                                    1 -> 0.65f
                                    2 -> 0.35f
                                    else -> 0.15f
                                }
                            }
                            GarlandMode.COMBINATION -> {
                                val wave = sin((phase * 4f * PI - i * 0.65f)).toFloat()
                                0.3f + 0.7f * ((wave + 1f) * 0.5f)
                            }
                            GarlandMode.MODE_3_EXP -> {
                                val evenGroup = (i % 2 == 0)
                                val firstHalf = phase < 0.5f
                                if (evenGroup == firstHalf) 1.0f else 0.2f
                            }
                            GarlandMode.MODE_5_EXP -> {
                                val breath = sin(phase * 2f * PI).toFloat()
                                0.2f + 0.8f * ((breath + 1f) * 0.5f)
                            }
                        } * brightnessFactor
                    }

                    // Socket cap
                    drawCircle(
                        color = Color(0xFF455A89),
                        radius = 4.5f,
                        center = Offset(bulbX, wireY + 3f)
                    )

                    // Outer glow halo when lit
                    if (uiState.isPoweredOn && animAlpha > 0.15f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    baseColor.copy(alpha = (animAlpha * 0.55f).coerceIn(0f, 1f)),
                                    baseColor.copy(alpha = 0f)
                                ),
                                center = Offset(bulbX, bulbY),
                                radius = 34f
                            ),
                            radius = 34f,
                            center = Offset(bulbX, bulbY)
                        )
                    }

                    // Bulb body
                    drawCircle(
                        color = baseColor.copy(alpha = animAlpha.coerceIn(0.12f, 1f)),
                        radius = 9.5f,
                        center = Offset(bulbX, bulbY)
                    )

                    // Specular white highlight inside lit bulb
                    if (uiState.isPoweredOn && animAlpha > 0.35f) {
                        drawCircle(
                            color = Color.White.copy(alpha = (animAlpha * 0.75f).coerceIn(0f, 1f)),
                            radius = 3.5f,
                            center = Offset(bulbX - 2.5f, bulbY - 2.5f)
                        )
                    }
                }
            }

            Text(
                text = uiState.lastCommandSummary,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BleConnectionCard(
    connectionState: BleConnectionState,
    connectedName: String?,
    connectedAddress: String?,
    rssi: Int?,
    statusMessage: String,
    discoveredGarlands: List<DiscoveredGarland>,
    onRequestPermissions: () -> Unit,
    onReconnectOrScan: () -> Unit,
    onStartScan: () -> Unit,
    onSelectGarland: (String) -> Unit,
    onDisconnect: () -> Unit,
    onForgetSaved: () -> Unit
) {
    val badgeColor = when (connectionState) {
        BleConnectionState.CONNECTED -> AccentGreen
        BleConnectionState.SCANNING, BleConnectionState.CONNECTING -> AccentGold
        BleConnectionState.BLUETOOTH_OFF, BleConnectionState.PERMISSION_REQUIRED -> AccentCrimson
        BleConnectionState.DISCONNECTED -> TextSecondary
    }

    val icon = when (connectionState) {
        BleConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
        BleConnectionState.SCANNING, BleConnectionState.CONNECTING -> Icons.AutoMirrored.Filled.BluetoothSearching
        BleConnectionState.BLUETOOTH_OFF, BleConnectionState.PERMISSION_REQUIRED -> Icons.Default.BluetoothDisabled
        BleConnectionState.DISCONNECTED -> Icons.Default.Bluetooth
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.16f))
                        .border(1.dp, badgeColor.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (connectionState) {
                                BleConnectionState.CONNECTED -> connectedName ?: "Guirlande Connectée"
                                BleConnectionState.CONNECTING -> "Connexion GATT en cours..."
                                BleConnectionState.SCANNING -> "Scan Bluetooth LE..."
                                BleConnectionState.PERMISSION_REQUIRED -> "Permission requise"
                                BleConnectionState.BLUETOOTH_OFF -> "Bluetooth désactivé"
                                BleConnectionState.DISCONNECTED -> "Guirlande non connectée"
                            },
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        if (rssi != null && connectionState == BleConnectionState.CONNECTED) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${rssi} dBm",
                                color = AccentGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text(
                        text = statusMessage,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    if (connectedAddress != null) {
                        Text(
                            text = "MAC mémorisée : $connectedAddress (Reconnexion <1s)",
                            color = AccentCyan.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            if (connectedAddress != null && connectionState != BleConnectionState.CONNECTED) {
                IconButton(onClick = onForgetSaved) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Oublier l'adresse MAC",
                        tint = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (connectionState == BleConnectionState.PERMISSION_REQUIRED) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold)
                ) {
                    Text("Autoriser le Bluetooth (Pixel 10 Pro)", fontWeight = FontWeight.Bold)
                }
            } else if (connectionState == BleConnectionState.CONNECTED) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Déconnecter", color = TextSecondary)
                }
                Button(
                    onClick = onStartScan,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2D5A))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rescanner", color = TextPrimary)
                }
            } else {
                Button(
                    onClick = onReconnectOrScan,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (connectedAddress != null) "Reconnecter" else "Scanner la guirlande",
                        fontWeight = FontWeight.Bold
                    )
                }
                if (connectedAddress != null) {
                    OutlinedButton(
                        onClick = onStartScan,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Nouveau Scan", color = AccentCyan)
                    }
                }
            }
        }

        // Discovered devices list if multiple garlands or scanning
        AnimatedVisibility(visible = discoveredGarlands.isNotEmpty() && connectionState != BleConnectionState.CONNECTED) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Appareils CSRMesh détectés :",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                discoveredGarlands.forEach { garland ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF192344))
                            .clickable { onSelectGarland(garland.address) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = garland.name,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = garland.address,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "${garland.rssi} dBm • Connecter",
                            color = AccentGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 7 Hardware Preset Colors (`Command = 5, Param = 1..7`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HardwareColorPickerCard(
    selectedColor: GarlandColor,
    isMultiColor: Boolean,
    isRainbowActive: Boolean,
    onSelectColor: (GarlandColor) -> Unit,
    onToggleRainbow: () -> Unit
) {
    SectionCard {
        SectionHeader(
            icon = Icons.Default.Palette,
            title = "7 Couleurs Matérielles & Mode Arc-en-Ciel",
            subtitle = "Couleurs fixes (Commande 5 • Param 1 à 7) ou cycle Arc-en-Ciel automatique"
        )

        Spacer(modifier = Modifier.height(14.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GarlandColor.entries.forEach { colorItem ->
                val isSelected = !isMultiColor && (selectedColor == colorItem)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectColor(colorItem) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        colorItem.glowColor,
                                        colorItem.displayColor
                                    )
                                )
                            )
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (colorItem == GarlandColor.WHITE || colorItem == GarlandColor.GOLD) {
                                            Color(0xFF070B19)
                                        } else {
                                            Color.White
                                        }
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = colorItem.nameFr,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = colorItem.binaryMask,
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onToggleRainbow,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRainbowActive) AccentCrimson else Color(0xFF4A2574)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = AccentGold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRainbowActive) "⏹ Arrêter le Mode Arc-en-Ciel" else "🌈 Activer le Mode Arc-en-Ciel Fluide",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * 4 Standard Animation Modes (`Command = 1, Param = 1, 2, 4, 6`).
 */
@Composable
private fun AnimationModesCard(
    selectedMode: GarlandMode,
    isPoweredOn: Boolean,
    activeColor: Color,
    onSelectMode: (GarlandMode) -> Unit
) {
    SectionCard {
        SectionHeader(
            icon = Icons.Default.AutoAwesome,
            title = "Modes d'Animation",
            subtitle = "Effets lumineux intégrés au boîtier (Commande 1)"
        )

        Spacer(modifier = Modifier.height(12.dp))

        val modes = GarlandMode.standardModes
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (rowIndex in modes.indices step 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (colIndex in 0..1) {
                        val idx = rowIndex + colIndex
                        if (idx < modes.size) {
                            val mode = modes[idx]
                            val isSelected = isPoweredOn && (selectedMode == mode)
                            ModeTile(
                                mode = mode,
                                isSelected = isSelected,
                                activeColor = activeColor,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectMode(mode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTile(
    mode: GarlandMode,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) activeColor else AuroraCardBorder
    val bgColor = if (isSelected) activeColor.copy(alpha = 0.16f) else Color(0xFF172140)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mode.titleFr,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = "#${mode.param}",
                color = if (isSelected) AccentGold else TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = mode.subtitleFr,
            color = TextSecondary,
            fontSize = 11.sp,
            minLines = 2,
            maxLines = 2
        )
    }
}

/**
 * Brightness Slider + 4 Quick Presets (`Command = 2, Param = 1..127`).
 */
@Composable
private fun BrightnessControlCard(
    brightness: Int,
    activeColor: Color,
    onBrightnessDrag: (Int) -> Unit,
    onBrightnessCommit: (Int) -> Unit,
    onSelectPreset: (BrightnessPreset) -> Unit
) {
    val percentage = ((brightness / 127f) * 100).toInt().coerceIn(1, 100)

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                icon = Icons.Default.BrightnessHigh,
                title = "Luminosité & Intensité",
                subtitle = "Échelle matérielle de 1 à 127 (0x0A à 0x7F)"
            )
            Text(
                text = "$percentage%",
                color = AccentGold,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = brightness.toFloat(),
            onValueChange = { onBrightnessDrag(it.toInt()) },
            onValueChangeFinished = { onBrightnessCommit(brightness) },
            valueRange = 1f..127f,
            colors = SliderDefaults.colors(
                thumbColor = AccentGold,
                activeTrackColor = activeColor,
                inactiveTrackColor = Color(0xFF23315C)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BrightnessPreset.entries.forEach { preset ->
                val isSelected = (brightness == preset.param)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) AccentGold.copy(alpha = 0.22f) else Color(0xFF172140)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AccentGold else AuroraCardBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectPreset(preset) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preset.labelPercent,
                        color = if (isSelected) AccentGold else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Hardware Timer Selector (`Command = 3, Param = 0, 1, 2`).
 */
@Composable
private fun TimerControlCard(
    selectedTimer: GarlandTimer,
    onSelectTimer: (GarlandTimer) -> Unit
) {
    SectionCard {
        SectionHeader(
            icon = Icons.Default.Timer,
            title = "Minuterie Automatique (Timer)",
            subtitle = "Programmation autonome dans la guirlande (Commande 3)"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GarlandTimer.entries.forEach { timer ->
                val isSelected = (selectedTimer == timer)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) AccentCyan.copy(alpha = 0.15f) else Color(0xFF172140)
                        )
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) AccentCyan else AuroraCardBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectTimer(timer) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = timer.titleFr,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = timer.scheduleFr,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = if (isSelected) "ACTIF" else "Choisir",
                        color = if (isSelected) AccentCyan else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Expandable Labo section to test hidden features discovered during reverse-engineering:
 * - Hidden LED bank selector (`Command = 4`: Single A / Single B / Multi)
 * - Unwired animation modes `Param = 3` and `Param = 5` (`Command = 1`)
 * - Raw CSRMesh RGB packet tester (`BLUE != 1`)
 * - Live 24-byte encrypted MTL hex inspector
 */
@Composable
private fun ExperimentalLaboCard(
    uiState: GarlandUiState,
    lastPacketHex: String?,
    onToggleExpand: () -> Unit,
    onSelectExperimentalMode: (GarlandMode) -> Unit,
    onSelectLedBank: (LedBankMode) -> Unit,
    onSendRawRgb: (Int, Int, Int) -> Unit
) {
    var rawRed by remember { mutableFloatStateOf(255f) }
    var rawGreen by remember { mutableFloatStateOf(128f) }
    var rawBlue by remember { mutableFloatStateOf(64f) }

    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                icon = Icons.Default.Science,
                title = "Labo & Fonctions Cachées",
                subtitle = "Options masquées dans le code d'origine & inspecteur CSRMesh"
            )
            Icon(
                imageVector = if (uiState.showLaboSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "Afficher ou masquer le labo",
                tint = AccentGold
            )
        }

        AnimatedVisibility(visible = uiState.showLaboSection) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Hidden Command = 4 (Single / Multi LED bank)
                Text(
                    text = "1. Rampes LED cachées (Commande 4 — visibility=\"gone\" dans l'APK) :",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LedBankMode.entries.forEach { bank ->
                        val isSelected = uiState.selectedLedBank == bank
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) AccentGold.copy(alpha = 0.2f) else Color(0xFF172140)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) AccentGold else AuroraCardBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelectLedBank(bank) }
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = bank.titleFr,
                                color = if (isSelected) AccentGold else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = bank.descriptionFr,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // 2. Hidden Modes Param 3 and Param 5
                Text(
                    text = "2. Effets d'animation sautés dans le code (Commande 1 • Param 3 & 5) :",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GarlandMode.experimentalModes.forEach { expMode ->
                        val isSelected = uiState.selectedMode == expMode
                        ModeTile(
                            mode = expMode,
                            isSelected = isSelected,
                            activeColor = AccentGold,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelectExperimentalMode(expMode) }
                        )
                    }
                }

                // 3. Standard CSRMesh Free RGB Test
                Text(
                    text = "3. Testeur RGB CSRMesh libre (Teste si le hardware accepte du PWM hors 7 couleurs) :",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF172140))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "R: ${rawRed.toInt()} • G: ${rawGreen.toInt()} • B: ${rawBlue.toInt()}",
                        color = Color(rawRed.toInt(), rawGreen.toInt(), rawBlue.toInt()),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Slider(
                        value = rawRed,
                        onValueChange = { rawRed = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = AccentCrimson, activeTrackColor = AccentCrimson)
                    )
                    Slider(
                        value = rawGreen,
                        onValueChange = { rawGreen = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = AccentGreen, activeTrackColor = AccentGreen)
                    )
                    Slider(
                        value = rawBlue,
                        onValueChange = { rawBlue = it },
                        valueRange = 2f..255f,
                        colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan)
                    )
                    Button(
                        onClick = {
                            onSendRawRgb(rawRed.toInt(), rawGreen.toInt(), rawBlue.toInt())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text(
                            text = "Envoyer trame RGB CSRMesh brute",
                            color = Color(0xFF070B19),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 4. Live 24-byte encrypted CSRMesh packet inspector
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF090E1E))
                        .border(1.dp, AuroraCardBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Dernier paquet CSRMesh MTL chiffré (24 octets → 20B + 4B GATT) :",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = lastPacketHex ?: "Aucune trame générée",
                        color = AccentGold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AuroraCardBg)
            .border(1.dp, AuroraCardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentGold,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}
