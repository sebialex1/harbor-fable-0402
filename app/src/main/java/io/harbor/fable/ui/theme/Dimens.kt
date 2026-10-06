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
val ScreenPadding = 12.dp

/** Horizontal/vertical padding inside list rows and card headers. */
val RowPaddingHorizontal = 14.dp
val RowPaddingVertical = 10.dp

/** Floating dock geometry. Screens reserve [Clearance] (plus the nav-bar inset) at the bottom. */
object DockMetrics {
    val Height = 64.dp
    val Margin = 10.dp

    /** Vertical space the dock occupies above the navigation-bar inset. */
    val Clearance = Height + Margin * 2
}

/**
 * Shadow elevation per glass level (see `GlassLevel`). The canvas is near-black, so shadows are
 * felt as a soft falloff around the pane rather than seen as a drop shadow; they grow with the
 * distance a pane floats above what is under it.
 */
object GlassShadow {
    val Card = 8.dp
    val Floating = 22.dp
    val Sheet = 28.dp
    val Overlay = 34.dp
}

/** Control heights, so buttons, chips and fields line up in a row. */
object ControlHeight {
    val Regular = 44.dp
    val Compact = 34.dp
    val Icon = 36.dp
}
