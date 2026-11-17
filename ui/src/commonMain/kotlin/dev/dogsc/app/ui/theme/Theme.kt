package dev.dogsc.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.dogsc.ui.resources.Res
import dev.dogsc.ui.resources.AlibabaPuHuiTi_3_55_Regular
import dev.dogsc.ui.resources.AlibabaPuHuiTi_3_65_Medium
import dev.dogsc.ui.resources.AlibabaPuHuiTi_3_75_SemiBold
import org.jetbrains.compose.resources.Font

/** DogSC "Soft Silver / Graphite" workspace palette, switchable between light and dark. */
object DogScColors {
    private var darkMode by androidx.compose.runtime.mutableStateOf(false)

    fun applyTheme(dark: Boolean) {
        darkMode = dark
    }

    val isDark: Boolean get() = darkMode

    private val LightBackground = Color(0xFFECEDEF)
    private val LightPanel = Color(0xFFFDFDFE)
    private val LightRaised = Color(0xFFF4F4F6)
    private val LightPrimaryText = Color(0xFF1A1A1A)
    private val LightSecondaryText = Color(0xFF66686D)
    private val LightTertiaryText = Color(0xFF94969C)
    private val LightAccent = Color(0xFF1F1F1F)
    private val LightControlWell = Color(0x00000000).copy(alpha = 0.05f)
    private val LightSelectionWash = Color(0x00000000).copy(alpha = 0.075f)
    private val LightHairline = Color(0x00000000).copy(alpha = 0.065f)

    // Dark elevation ladder: each surface step is clearly lighter than the one below so cards
    // and panels read as distinct layers instead of black-on-black.
    private val DarkBackgroundColor = Color(0xFF0B0C0F)
    private val DarkPanelColor = Color(0xFF16181D)
    private val DarkRaisedColor = Color(0xFF21242B)
    private val DarkPrimaryTextColor = Color(0xFFF2F3F5)
    private val DarkSecondaryTextColor = Color(0xFFA7A9B0)
    private val DarkTertiaryTextColor = Color(0xFF7C7E86)
    private val DarkAccent = Color(0xFFF2F3F5)
    private val DarkControlWell = Color(0xFFFFFFFF).copy(alpha = 0.09f)
    private val DarkSelectionWash = Color(0xFFFFFFFF).copy(alpha = 0.14f)
    private val DarkHairline = Color(0xFFFFFFFF).copy(alpha = 0.12f)

    val Background: Color get() = if (darkMode) DarkBackgroundColor else LightBackground
    val Panel: Color get() = if (darkMode) DarkPanelColor else LightPanel
    val Raised: Color get() = if (darkMode) DarkRaisedColor else LightRaised
    val Stage: Color get() = if (darkMode) DarkBackgroundColor else LightBackground
    val StageLight: Color get() = if (darkMode) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.72f)
    val CardFill: Color get() = if (darkMode) Color.White.copy(alpha = 0.08f) else Color(0xFF1F2126).copy(alpha = 0.045f)
    val PrimaryText: Color get() = if (darkMode) DarkPrimaryTextColor else LightPrimaryText
    val SecondaryText: Color get() = if (darkMode) DarkSecondaryTextColor else LightSecondaryText
    val TertiaryText: Color get() = if (darkMode) DarkTertiaryTextColor else LightTertiaryText
    val Accent: Color get() = if (darkMode) DarkAccent else LightAccent
    val OnAccent: Color get() = if (darkMode) Color(0xFF101114) else Color.White
    val ControlWell: Color get() = if (darkMode) DarkControlWell else LightControlWell
    val SelectionWash: Color get() = if (darkMode) DarkSelectionWash else LightSelectionWash
    val Hairline: Color get() = if (darkMode) DarkHairline else LightHairline
    val Outline: Color get() = if (darkMode) Color.White.copy(alpha = 0.14f) else Color(0xFF1A1F2E).copy(alpha = 0.075f)
    val Recording: Color get() = Color(0xFFFF453A)
    val Amber: Color get() = Color(0xFFF2AE47)
    val Success: Color get() = Color(0xFF85D142)
    val Mint: Color get() = if (darkMode) Color(0xFF26331C) else Color(0xFFDCEBC8)
    val MintInk: Color get() = if (darkMode) Color(0xFFA9D07C) else Color(0xFF5C7C34)
    val Playhead: Color get() = if (darkMode) Color(0xFFFF6B6B) else Color(0xFFF25C5C)
    val ZoomClip: Color get() = if (darkMode) Color(0xFF26331C) else Color(0xFFDCEBC8)
    val ZoomInk: Color get() = if (darkMode) Color(0xFFA9D07C) else Color(0xFF5C7C34)
    val ScreenClip: Color get() = if (darkMode) Color(0xFF26282D) else Color(0xFFE3E4E7)
    val CameraClip: Color get() = if (darkMode) Color(0xFF1E2636) else Color(0xFFDCE3F4)
    val CameraInk: Color get() = if (darkMode) Color(0xFF9DB4E0) else Color(0xFF465E8C)
    val StickerClip: Color get() = if (darkMode) Color(0xFF33291D) else Color(0xFFF2E3D0)
    val StickerInk: Color get() = if (darkMode) Color(0xFFD8B07A) else Color(0xFF8A6435)
    val MosaicClip: Color get() = if (darkMode) Color(0xFF2A2434) else Color(0xFFE8E0F0)
    val MosaicInk: Color get() = if (darkMode) Color(0xFFC0A8E0) else Color(0xFF6B568C)
    val FocusClip: Color get() = if (darkMode) Color(0xFF332D1D) else Color(0xFFF0E6D2)
    val FocusInk: Color get() = if (darkMode) Color(0xFFD8C07A) else Color(0xFF7A6335)
    val Danger: Color get() = if (darkMode) Color(0xFFFF6B63) else Color(0xFFD94A44)
    val Scrim: Color get() = if (darkMode) Color.Black.copy(alpha = 0.60f) else Color(0xFF10131A).copy(alpha = 0.42f)

    // Retained for the Material color scheme construction below.
    val DarkBackground: Color get() = DarkBackgroundColor
    val DarkPanel: Color get() = DarkPanelColor
    val DarkRaised: Color get() = DarkRaisedColor
    val DarkCard: Color get() = Color.White.copy(alpha = 0.06f)
    val DarkPrimaryText: Color get() = DarkPrimaryTextColor
    val DarkSecondaryText: Color get() = DarkSecondaryTextColor
}

private fun dogscTypography(regular: FontFamily, medium: FontFamily, semibold: FontFamily): Typography =
    Typography(
        displayLarge = TextStyle(fontFamily = semibold, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.6).sp),
        displayMedium = TextStyle(fontFamily = semibold, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
        headlineLarge = TextStyle(fontFamily = semibold, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineMedium = TextStyle(fontFamily = semibold, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
        titleLarge = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
        titleMedium = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        titleSmall = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = TextStyle(fontFamily = regular, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = regular, fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontFamily = regular, fontSize = 11.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = TextStyle(fontFamily = medium, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp),
    )

@Composable
fun DogScTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.SideEffect { DogScColors.applyTheme(darkTheme) }
    val regular = FontFamily(Font(Res.font.AlibabaPuHuiTi_3_55_Regular, FontWeight.Normal))
    val medium = FontFamily(Font(Res.font.AlibabaPuHuiTi_3_65_Medium, FontWeight.Medium))
    val semibold = FontFamily(Font(Res.font.AlibabaPuHuiTi_3_75_SemiBold, FontWeight.SemiBold))
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = DogScColors.DarkPrimaryText,
            onPrimary = Color(0xFF101114),
            background = DogScColors.DarkBackground,
            onBackground = DogScColors.DarkPrimaryText,
            surface = DogScColors.DarkPanel,
            onSurface = DogScColors.DarkPrimaryText,
            surfaceVariant = DogScColors.DarkRaised,
            onSurfaceVariant = DogScColors.DarkSecondaryText,
            outline = Color.White.copy(alpha = 0.12f),
            error = DogScColors.Danger,
        )
    } else {
        lightColorScheme(
            primary = DogScColors.Accent,
            onPrimary = DogScColors.OnAccent,
            background = DogScColors.Background,
            onBackground = DogScColors.PrimaryText,
            surface = DogScColors.Panel,
            onSurface = DogScColors.PrimaryText,
            surfaceVariant = DogScColors.Raised,
            onSurfaceVariant = DogScColors.SecondaryText,
            outline = DogScColors.Outline,
            error = DogScColors.Danger,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = dogscTypography(regular, medium, semibold),
        content = content,
    )
}

val DogScFontRegular: FontFamily
    @Composable get() = FontFamily(Font(Res.font.AlibabaPuHuiTi_3_55_Regular, FontWeight.Normal))

val DogScFontMedium: FontFamily
    @Composable get() = FontFamily(Font(Res.font.AlibabaPuHuiTi_3_65_Medium, FontWeight.Medium))

val DogScFontSemibold: FontFamily
    @Composable get() = FontFamily(Font(Res.font.AlibabaPuHuiTi_3_75_SemiBold, FontWeight.SemiBold))
