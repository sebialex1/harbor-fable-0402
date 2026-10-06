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

/** Bottom tab bar geometry. Screens reserve [Clearance] (plus the nav-bar inset) at the bottom. */
object TabBarMetrics {
    /** Height of the floating pill itself. */
    val Height = 60.dp

    /** Gap between the pill and the navigation-bar inset (and above it). */
    val FloatGap = 10.dp

    /** Inset of the sliding active-tab capsule inside the pill. */
    val IndicatorInset = 5.dp

    /** Vertical space the floating pill occupies above the navigation-bar inset. */
    val Clearance = Height + FloatGap * 2
}

/** Control heights, so buttons, chips and fields line up in a row. */
object ControlHeight {
    val Regular = 50.dp
    val Compact = 32.dp
    val Icon = 34.dp
}
