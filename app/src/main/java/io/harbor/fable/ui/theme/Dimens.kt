package io.harbor.fable.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing scale shared by every screen so paddings and gaps stay consistent. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Horizontal padding applied to all screen content. */
val ScreenPadding = 16.dp

/** Horizontal/vertical padding inside list rows and card headers. */
val RowPaddingHorizontal = 16.dp
val RowPaddingVertical = 11.dp

/** Floating dock geometry. Screens reserve [Clearance] (plus the nav-bar inset) at the bottom. */
object DockMetrics {
    val Height = 64.dp
    val Margin = 10.dp

    /** Vertical space the dock occupies above the navigation-bar inset. */
    val Clearance = Height + Margin * 2
}

/**
 * Shadow elevation per material level (see `GlassLevel`). Sections in a list have none; only
 * chrome that floats over moving content gets a soft falloff.
 */
object GlassShadow {
    val Card = 0.dp
    val Floating = 12.dp
    val Sheet = 0.dp
    val Overlay = 16.dp
}

/** Control heights, so buttons, chips and fields line up in a row. */
object ControlHeight {
    val Regular = 50.dp
    val Compact = 32.dp
    val Icon = 34.dp
}
