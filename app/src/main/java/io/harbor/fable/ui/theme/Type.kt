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
 * Type scale, sized after the iOS text styles (Large Title, Headline, Body, Subheadline,
 * Footnote) and set in Inter. Weight does little of the work: titles are Medium, everything else
 * Regular, and hierarchy comes from size and the grey label colours.
 *
 * - displaySmall   — large screen title, 32sp Medium
 * - headlineLarge  — large title on tabs, 32sp Medium
 * - headlineMedium — inline (collapsed) bar title and sheet titles, 17sp Medium
 * - titleLarge     — dialog titles, 20sp Medium
 * - titleMedium    — emphasised row titles, 17sp Medium
 * - titleSmall     — list row titles, 16sp Regular
 * - bodyLarge      — input text and values, 16sp Regular
 * - bodyMedium     — secondary values and messages, 15sp Regular grey
 * - bodySmall      — subtitles and footnotes, 13sp Regular grey
 * - labelLarge     — buttons, 16sp Medium
 * - labelMedium / labelSmall — section headers, links and tags, 13sp / 12sp
 */
val FableTypography = Typography(
    displaySmall = style(FontWeight.Medium, 32.sp, 38.sp, letterSpacing = (-0.6).sp),
    headlineLarge = style(FontWeight.Medium, 32.sp, 38.sp, letterSpacing = (-0.6).sp),
    headlineMedium = style(FontWeight.Medium, 17.sp, 22.sp, letterSpacing = (-0.2).sp),
    headlineSmall = style(FontWeight.Medium, 20.sp, 25.sp, letterSpacing = (-0.3).sp),
    titleLarge = style(FontWeight.Medium, 20.sp, 25.sp, letterSpacing = (-0.3).sp),
    titleMedium = style(FontWeight.Medium, 17.sp, 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = style(FontWeight.Normal, 16.sp, 21.sp, letterSpacing = (-0.2).sp),
    bodyLarge = style(FontWeight.Normal, 16.sp, 21.sp, letterSpacing = (-0.2).sp),
    bodyMedium = style(FontWeight.Normal, 15.sp, 20.sp, color = FableTextDim, letterSpacing = (-0.1).sp),
    bodySmall = style(FontWeight.Normal, 13.sp, 17.sp, color = FableTextDim),
    labelLarge = style(FontWeight.Medium, 16.sp, 21.sp, letterSpacing = (-0.2).sp),
    labelMedium = style(FontWeight.Normal, 13.sp, 17.sp, color = FableTextDim),
    labelSmall = style(FontWeight.Medium, 12.sp, 15.sp, color = FableTextDim),
)
