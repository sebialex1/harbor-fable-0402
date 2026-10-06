package io.harbor.fable.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.harbor.fable.R

/**
 * Inter, bundled in `res/font` (SIL OFL, see THIRD_PARTY_NOTICES.md). Three weights cover the
 * UI: Light for subtitles, Regular for body copy, Medium for everything that is a title.
 * Heavier weights resolve to Medium on purpose; the layout never needs bold.
 */
val FableFontFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_medium, FontWeight.SemiBold),
    Font(R.font.inter_medium, FontWeight.Bold),
)

private fun style(
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
    color: androidx.compose.ui.graphics.Color = FableText,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = FableFontFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    color = color,
)

/**
 * Type scale. Screens use these styles (via `MaterialTheme.typography`) instead of ad-hoc
 * font sizes:
 *
 * - headlineLarge / headlineMedium — screen and sheet titles, 20sp Medium
 * - titleMedium — section labels and empty-state titles, 16sp Medium
 * - titleSmall  — list row titles, 14sp Medium
 * - bodyLarge   — input text, 14sp Regular
 * - bodyMedium  — values and messages, 13sp Regular
 * - bodySmall   — subtitles and metadata, 12sp Light
 * - labelLarge  — buttons, 14sp Medium
 * - labelMedium / labelSmall — links and pills, 12sp / 11sp Medium
 */
val FableTypography = Typography(
    headlineLarge = style(FontWeight.Medium, 20.sp, 24.sp, letterSpacing = (-0.2).sp),
    headlineMedium = style(FontWeight.Medium, 20.sp, 24.sp, letterSpacing = (-0.2).sp),
    headlineSmall = style(FontWeight.Medium, 18.sp, 22.sp),
    titleLarge = style(FontWeight.Medium, 18.sp, 22.sp),
    titleMedium = style(FontWeight.Medium, 16.sp, 20.sp),
    titleSmall = style(FontWeight.Medium, 14.sp, 18.sp),
    bodyLarge = style(FontWeight.Normal, 14.sp, 19.sp),
    bodyMedium = style(FontWeight.Normal, 13.sp, 17.sp, color = FableTextDim),
    bodySmall = style(FontWeight.Light, 12.sp, 15.sp, color = FableTextDim),
    labelLarge = style(FontWeight.Medium, 14.sp, 18.sp),
    labelMedium = style(FontWeight.Medium, 12.sp, 16.sp, color = FableTextDim),
    labelSmall = style(FontWeight.Medium, 11.sp, 14.sp, color = FableTextDim),
)
