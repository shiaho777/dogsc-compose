package dev.dogsc.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

/** DogSC "Soft Silver / Graphite" workspace palette. */
object DogScColors {
    val Background = Color(0xFFECEDEF)
    val Panel = Color(0xFFFDFDFE)
    val Raised = Color(0xFFF4F4F6)
    val Stage = Color(0xFFECEDEF)
    val StageLight = Color(0xFFFFFFFF).copy(alpha = 0.72f)
    val CardFill = Color(0xFF1F2126).copy(alpha = 0.045f)
    val PrimaryText = Color(0xFF1A1A1A)
    val SecondaryText = Color(0xFF66686D)
    val TertiaryText = Color(0xFF94969C)
    val Accent = Color(0xFF1F1F1F)
    val OnAccent = Color(0xFFFFFFFF)
    val ControlWell = Color(0xFF000000).copy(alpha = 0.05f)
    val SelectionWash = Color(0xFF000000).copy(alpha = 0.075f)
    val Hairline = Color(0xFF000000).copy(alpha = 0.065f)
    val Outline = Color(0xFF1A1F2E).copy(alpha = 0.075f)
    val Recording = Color(0xFFFF453A)
    val Amber = Color(0xFFF2AE47)
    val Success = Color(0xFF85D142)
    val Mint = Color(0xFFDCEBC8)
    val MintInk = Color(0xFF5C7C34)
    val Playhead = Color(0xFFF25C5C)
    val ZoomClip = Color(0xFFDCEBC8)
    val ZoomInk = Color(0xFF5C7C34)
    val ScreenClip = Color(0xFFE3E4E7)
    val CameraClip = Color(0xFFDCE3F4)
    val CameraInk = Color(0xFF465E8C)
    val StickerClip = Color(0xFFF2E3D0)
    val StickerInk = Color(0xFF8A6435)
    val MosaicClip = Color(0xFFE8E0F0)
    val MosaicInk = Color(0xFF6B568C)
    val FocusClip = Color(0xFFF0E6D2)
    val FocusInk = Color(0xFF7A6335)
    val Danger = Color(0xFFD94A44)
    val Scrim = Color(0xFF10131A).copy(alpha = 0.42f)

    val DarkBackground = Color(0xFF08080A)
    val DarkPanel = Color(0xFF101114)
    val DarkRaised = Color(0xFF191A1E)
    val DarkCard = Color(0xFFFFFFFF).copy(alpha = 0.06f)
    val DarkPrimaryText = Color(0xFFF5F5F7)
    val DarkSecondaryText = Color(0xFFA9ABB3)
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
