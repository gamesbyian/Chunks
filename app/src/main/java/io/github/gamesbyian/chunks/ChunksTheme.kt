package io.github.gamesbyian.chunks

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Chunks colour tokens. Accents are the printed "brand essence" values in
 * docs/design/svg/palettes/palette-tokens.json (orange/red from the type
 * hierarchy sheet). Every accent fill carries [OnAccent] text, which passes
 * WCAG AA on all of them (contrast-audit.md); plain surfaces carry [Text].
 * Colour never stands alone for state: labels and semantics stay.
 */
internal object ChunksColors {
    val Pink = Color(0xFFFF4EC4)
    val Purple = Color(0xFFA56BFF)
    /** Text-safe purple (5.7:1 even on [Muted]); filled controls carry [OnAccent]. */
    val PurpleLight = Color(0xFFC4A2FF)
    val Blue = Color(0xFF3B82FF)
    val Yellow = Color(0xFFFFD93D)
    val Green = Color(0xFF22D97A)
    val Cyan = Color(0xFF00D4FF)
    val Orange = Color(0xFFFF8A3D)
    val Red = Color(0xFFFF4E5C)

    val Background = Color(0xFF0B0F17)
    /** Recessed fill behind segmented progress. */
    val Trough = Color(0xFF05070C)
    val Surface = Color(0xFF1A1F2B)
    val SurfaceRaised = Color(0xFF232A39)
    val Muted = Color(0xFF2E3647)
    val Outline = Color(0xFF4A5468)

    val Text = Color(0xFFF8FAFC)
    /** 10.5:1 on [Background], 9.0:1 on [Surface], 6.6:1 on [Muted]: AA everywhere. */
    val TextSecondary = Color(0xFFB8C0CF)
    val OnAccent = Color(0xFF0B0F17)
}

internal val ChunksDisplayFamily = FontFamily(Font(R.font.chunks_extrabold, weight = FontWeight.ExtraBold))

private fun display(size: Int, line: Int) = TextStyle(
    fontFamily = ChunksDisplayFamily, fontWeight = FontWeight.ExtraBold,
    fontSize = size.sp, lineHeight = line.sp,
    // Measured optical preset in chunks-font-metrics.json (800/display).
    letterSpacing = (-0.0067).em
)

/**
 * Headings use the Chunks display face; running text and dense labels keep
 * the platform sans so long sentences, paths and numbers stay readable at
 * 200% text.
 */
private val ChunksTypography = Typography().let { base ->
    base.copy(
        displayLarge = display(48, 54), displayMedium = display(40, 46), displaySmall = display(34, 40),
        headlineLarge = display(30, 36), headlineMedium = display(26, 32), headlineSmall = display(23, 29),
        titleLarge = display(21, 27), titleMedium = display(17, 23),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold)
    )
}

private val ChunksShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val ChunksColorScheme = darkColorScheme(
    primary = ChunksColors.PurpleLight, onPrimary = ChunksColors.OnAccent,
    primaryContainer = ChunksColors.Purple, onPrimaryContainer = ChunksColors.OnAccent,
    secondary = ChunksColors.Cyan, onSecondary = ChunksColors.OnAccent,
    secondaryContainer = ChunksColors.Muted, onSecondaryContainer = ChunksColors.Text,
    tertiary = ChunksColors.Green, onTertiary = ChunksColors.OnAccent,
    tertiaryContainer = ChunksColors.Green, onTertiaryContainer = ChunksColors.OnAccent,
    background = ChunksColors.Background, onBackground = ChunksColors.Text,
    surface = ChunksColors.Background, onSurface = ChunksColors.Text,
    surfaceVariant = ChunksColors.Muted, onSurfaceVariant = ChunksColors.TextSecondary,
    surfaceContainerLowest = ChunksColors.Background, surfaceContainerLow = ChunksColors.Surface,
    surfaceContainer = ChunksColors.Surface, surfaceContainerHigh = ChunksColors.SurfaceRaised,
    // Material's filled Card uses this: keep cards at the reference card tone.
    surfaceContainerHighest = ChunksColors.Surface,
    inverseSurface = ChunksColors.Text, inverseOnSurface = ChunksColors.OnAccent,
    outline = ChunksColors.Outline, outlineVariant = ChunksColors.Muted,
    error = ChunksColors.Red, onError = ChunksColors.OnAccent,
    errorContainer = ChunksColors.Red, onErrorContainer = ChunksColors.OnAccent
)

@Composable
internal fun ChunksTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = ChunksColorScheme, typography = ChunksTypography,
        shapes = ChunksShapes, content = content)
