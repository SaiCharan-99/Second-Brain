package com.secondbrain.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The design board's own palette (artifacts/Second Brain UI.html), already
 * proven once in [com.secondbrain.voice.harness.PttWindow] — "borrows the
 * design board's colour tokens so the states read the same way they will in
 * the real UI." Same five colours, same meaning, now the real UI.
 *
 * The brief extracted from that board is explicit: "light, quiet,
 * desktop-native — system type, hairline rules, no brand color beyond one
 * system blue." Red is used exactly once, since Step 5: the final,
 * irreversible Send/Create button in `ProposalWindow`. Nothing else in the
 * app ever uses it — a colour with more than one caller stops meaning
 * "irreversible."
 */
object AppColors {
    val Canvas = Color(0xF7, 0xF7, 0xF5)
    val Ink = Color(0x17, 0x17, 0x1A)
    val Muted = Color(0x8A, 0x8A, 0x90)
    val Blue = Color(0x2E, 0x6B, 0xE6)
    val Green = Color(0x3E, 0xA7, 0x6B)
    val Amber = Color(0xD6, 0x9A, 0x2B)
    val Red = Color(0xC7, 0x3E, 0x3E)
    val Surface = Color(0xFF, 0xFF, 0xFF)

    /** Hairline dividers and card borders — the design board never uses a heavy rule. */
    val Border = Ink.copy(alpha = 0.10f)
    val BorderStrong = Ink.copy(alpha = 0.16f)

    /** A dangling wikilink's underline and badge. Distinct from [Amber]'s "thinking" use. */
    val Dangling = Color(0xB4, 0x72, 0x0A)
}

private val AppTypography = Typography().let { base ->
    val family = FontFamily.SansSerif
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

/** Monospace, for paths, ledger-shaped values and IDs — the design board's own distinction. */
val MonoTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private val AppColorScheme = lightColorScheme(
    primary = AppColors.Blue,
    onPrimary = Color.White,
    secondary = AppColors.Amber,
    onSecondary = AppColors.Ink,
    tertiary = AppColors.Green,
    onTertiary = Color.White,
    background = AppColors.Canvas,
    onBackground = AppColors.Ink,
    surface = AppColors.Surface,
    onSurface = AppColors.Ink,
    surfaceVariant = AppColors.Canvas,
    onSurfaceVariant = AppColors.Muted,
    outline = AppColors.Border,
    outlineVariant = AppColors.Border,
    error = AppColors.Dangling,
    onError = Color.White,
)

/** No dark-mode branch. The design brief commits to one look, deliberately. */
@Composable
fun SecondBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, typography = AppTypography, content = content)
}
