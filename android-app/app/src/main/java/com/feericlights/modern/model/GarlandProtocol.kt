package com.feericlights.modern.model

import androidx.compose.ui.graphics.Color

/**
 * Complete reverse-engineered protocol definition for the Feeric Lights & Christmas RGB LED garland.
 *
 * All standard commands go through `MainActivity.doCommand(command, param)` which sends:
 * `LightModelApi.setRgb(deviceId, red = command, green = param, blue = 1, level = 0xFF, duration = 0, ack = false)`
 */
object GarlandProtocol {
    const val CMD_MODE_AND_POWER = 1
    const val CMD_BRIGHTNESS = 2
    const val CMD_TIMER = 3
    const val CMD_LED_CHANNEL_MODE = 4
    const val CMD_COLOR_PRESET = 5
}

/**
 * Command = 1: Animation Modes & Power OFF (`FunctionFragment.smali`)
 */
enum class GarlandMode(
    val param: Int,
    val titleFr: String,
    val subtitleFr: String,
    val isExperimental: Boolean = false
) {
    COMBINATION(
        param = 1,
        titleFr = "Combinaison",
        subtitleFr = "Cycle automatique de tous les effets"
    ),
    SEQUENTIAL(
        param = 2,
        titleFr = "Séquentiel",
        subtitleFr = "Défilement rythmé le long de la guirlande"
    ),
    TWINKLE(
        param = 4,
        titleFr = "Scintillement",
        subtitleFr = "Étoiles clignotantes douces (Twinkle)"
    ),
    STEADY_ON(
        param = 6,
        titleFr = "Fixe Continu",
        subtitleFr = "Éclairage permanent sans clignotement"
    ),
    MODE_3_EXP(
        param = 3,
        titleFr = "Effet #3 (Caché)",
        subtitleFr = "Opcode 1, Param 3 (non câblé dans l'appli d'origine)",
        isExperimental = true
    ),
    MODE_5_EXP(
        param = 5,
        titleFr = "Effet #5 (Caché)",
        subtitleFr = "Opcode 1, Param 5 (non câblé dans l'appli d'origine)",
        isExperimental = true
    );

    companion object {
        const val PARAM_POWER_OFF = 7
        val standardModes = entries.filter { !it.isExperimental }
        val experimentalModes = entries.filter { it.isExperimental }
    }
}

/**
 * Command = 5: Hardware 3-bit RGB Colors (`BrightnessFragment.smali`)
 *
 * Notice how `param` (1..7) is a 3-bit binary mask `R G B`:
 * - Bit 2 (4) = Red LED channel
 * - Bit 1 (2) = Green LED channel
 * - Bit 0 (1) = Blue LED channel
 */
enum class GarlandColor(
    val param: Int,
    val binaryMask: String,
    val nameFr: String,
    val displayColor: Color,
    val glowColor: Color
) {
    BLUE(
        param = 1,
        binaryMask = "001 (B)",
        nameFr = "Bleu Nuit",
        displayColor = Color(0xFF2962FF),
        glowColor = Color(0xFF448AFF)
    ),
    GREEN(
        param = 2,
        binaryMask = "010 (G)",
        nameFr = "Vert Sapin",
        displayColor = Color(0xFF00C853),
        glowColor = Color(0xFF69F0AE)
    ),
    CYAN(
        param = 3,
        binaryMask = "011 (G+B)",
        nameFr = "Bleu Glacier",
        displayColor = Color(0xFF00E5FF),
        glowColor = Color(0xFF84FFFF)
    ),
    RED(
        param = 4,
        binaryMask = "100 (R)",
        nameFr = "Rouge Noël",
        displayColor = Color(0xFFFF1744),
        glowColor = Color(0xFFFF5252)
    ),
    MAGENTA(
        param = 5,
        binaryMask = "101 (R+B)",
        nameFr = "Rose Boréal",
        displayColor = Color(0xFFF50057),
        glowColor = Color(0xFFFF4081)
    ),
    GOLD(
        param = 6,
        binaryMask = "110 (R+G)",
        nameFr = "Or / Jaune",
        displayColor = Color(0xFFFFD600),
        glowColor = Color(0xFFFFFF00)
    ),
    WHITE(
        param = 7,
        binaryMask = "111 (R+G+B)",
        nameFr = "Blanc Pur",
        displayColor = Color(0xFFF5F7FA),
        glowColor = Color(0xFFFFFFFF)
    )
}

/**
 * Command = 2: Brightness presets (`BrightnessFragment.smali`)
 * Values range from 1 to 127 (`0x7F`).
 */
enum class BrightnessPreset(
    val param: Int,
    val labelPercent: String
) {
    P25(param = 0x0A, labelPercent = "25%"),   // 10
    P50(param = 0x32, labelPercent = "50%"),   // 50
    P75(param = 0x5A, labelPercent = "75%"),   // 90
    P100(param = 0x7F, labelPercent = "100%")  // 127
}

/**
 * Command = 3: Hardware Timer modes (`TimerFragment.smali`)
 */
enum class GarlandTimer(
    val param: Int,
    val titleFr: String,
    val scheduleFr: String
) {
    OFF(
        param = 0,
        titleFr = "Désactivé",
        scheduleFr = "Aucun arrêt programmé"
    ),
    TIMER_1(
        param = 1,
        titleFr = "Cycle Soirée (6h / 18h)",
        scheduleFr = "6h Allumé • 18h Éteint"
    ),
    TIMER_2(
        param = 2,
        titleFr = "Double Cycle (Soir + Matin)",
        scheduleFr = "6h ON • 6h OFF • 3h ON • 9h OFF"
    )
}

/**
 * Command = 4: Hidden LED Bank / Multi-Color Mode (`FunctionFragment$2.smali`)
 * Hidden in original XML (`visibility="gone"`), but wired in bytecode.
 */
enum class LedBankMode(
    val param: Int,
    val titleFr: String,
    val descriptionFr: String
) {
    SINGLE_A(
        param = 1,
        titleFr = "Single A",
        descriptionFr = "Rampe LED 1"
    ),
    SINGLE_B(
        param = 2,
        titleFr = "Single B",
        descriptionFr = "Rampe LED 2"
    ),
    MULTI(
        param = 3,
        titleFr = "Multi-Couleurs",
        descriptionFr = "Toutes les rampes combinées"
    )
}
