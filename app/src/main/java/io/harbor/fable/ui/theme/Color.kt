package io.harbor.fable.ui.theme

import androidx.compose.ui.graphics.Color

// Premium dark palette: a near-black canvas with charcoal glass on top of it.
val FableBg = Color(0xFF0D0D0F)

/** Opaque surfaces: sheets and menus. */
val FableSurface = Color(0xFF151518)

/** Opaque surfaces that float above sheets: dialogs and snackbars. */
val FableSurfaceRaised = Color(0xFF1D1D22)

/** Card fill: charcoal #1A1A1E at 85%, so the canvas only just shows through. */
val FableGlass = Color(0xD91A1A1E)

/** The glass edge: a hairline of white at 6%. */
val FableGlassBorder = Color(0x0FFFFFFF)
val FableDivider = Color(0x0FFFFFFF)

/** Fill and edge for controls that sit on glass: buttons, chips, switch tracks. */
val FableControl = Color(0x14FFFFFF)
val FableControlBorder = Color(0x1AFFFFFF)

/** Edge of text fields and other outlines that must read as interactive. */
val FableOutline = Color(0x29FFFFFF)

val FableAccent = Color(0xFF7C5CFF)
val FableAccentLight = Color(0xFF9279FF)
val FableAccentDim = Color(0xFF4A3A8A)
val FableText = Color(0xFFEDEDF0)
val FableTextDim = Color(0xFF8E8E99)
val FableSuccess = Color(0xFF4ADE80)
val FableWarn = Color(0xFFFBBF24)
val FableError = Color(0xFFEF4444)

// Dock-specific. Nearly opaque so list content scrolling underneath stays out of the way.
val DockBg = Color(0xF2141417)
val DockBorder = Color(0x1AFFFFFF)
val DockItemActive = FableAccent
val DockItemIdle = Color(0xFF6E6E7A)
