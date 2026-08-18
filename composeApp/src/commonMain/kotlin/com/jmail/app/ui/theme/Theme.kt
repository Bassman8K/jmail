package com.jmail.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmail.shared.model.UiDensity
import com.jmail.shared.model.UiTheme

/**
 * Spacing scale.
 *
 * A four-point grid, named by intent rather than size. Using `Spacing.betweenSections`
 * instead of `24.dp` at a call site is what keeps rhythm consistent as the app grows —
 * and what makes the density setting a one-line change rather than an audit.
 */
data class Spacing(
    val hairline: Dp = 2.dp,
    val tight: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val betweenSections: Dp = 24.dp,
    val generous: Dp = 32.dp,
    /** Vertical padding inside a message row; the one value density actually changes. */
    val rowVertical: Dp = 12.dp,
    val rowHorizontal: Dp = 16.dp,
    /** Minimum touch target. 48dp is the accessibility floor on every platform. */
    val minimumTouchTarget: Dp = 48.dp,
) {
    companion object {
        fun forDensity(density: UiDensity): Spacing = when (density) {
            UiDensity.COMPACT -> Spacing(rowVertical = 6.dp, rowHorizontal = 12.dp)
            UiDensity.COMFORTABLE -> Spacing()
            UiDensity.SPACIOUS -> Spacing(rowVertical = 18.dp, rowHorizontal = 20.dp)
        }
    }
}

/**
 * Type scale.
 *
 * Line heights are set explicitly and generously: message previews and bodies are prose,
 * and Material's defaults are tuned for labels. `LineHeightStyle` trims the leading at the
 * top and bottom of a block so multi-line rows still align to the grid.
 */
private val jmailTypography: Typography
    @Composable
    @ReadOnlyComposable
    get() {
        val body = TextStyle(
            fontFamily = FontFamily.Default,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )

        return Typography(
            displaySmall = body.copy(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
            headlineMedium = body.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = body.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = body.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = body.copy(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = body.copy(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            labelSmall = body.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        )
    }

private val jmailShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

val LocalSemanticColors = staticCompositionLocalOf { JMailSemanticColors.light }

/**
 * The app's theme.
 *
 * @param theme the user's stored preference; SYSTEM follows the OS.
 * @param density controls row height only — text never shrinks, because shrinking type is
 *   an accessibility regression, not a density setting.
 */
@Composable
fun JMailTheme(
    theme: UiTheme = UiTheme.SYSTEM,
    density: UiDensity = UiDensity.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val useDark = when (theme) {
        UiTheme.SYSTEM -> isSystemInDarkTheme()
        UiTheme.LIGHT -> false
        UiTheme.DARK -> true
    }

    CompositionLocalProvider(
        LocalSpacing provides Spacing.forDensity(density),
        LocalSemanticColors provides if (useDark) JMailSemanticColors.dark else JMailSemanticColors.light,
    ) {
        MaterialTheme(
            colorScheme = if (useDark) JMailDarkColors else JMailLightColors,
            typography = jmailTypography,
            shapes = jmailShapes,
            content = content,
        )
    }
}

/** Shorthand so call sites read `JMailTheme.spacing.large` rather than reaching for locals. */
object JMailTheme {

    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current

    val semantic: JMailSemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
}
