package io.harbor.fable.ui.theme

import androidx.compose.ui.graphics.Color

// Premium dark palette: a near-black canvas, liquid light drifting behind it, and panes of
// charcoal glass on top. Glass is never fully opaque; the light behind it is what makes it glass.
val FableBg = Color(0xFF0B0B0E)

/** Opaque reference tones the glass fills are derived from. Use the glass colours for surfaces. */
val FableSurface = Color(0xFF151518)
val FableSurfaceRaised = Color(0xFF1D1D22)

// --- Glass fills, from the canvas up. Alpha is the depth cue: each level is a touch more solid.

/** Cards and rows: charcoal at 78%, so the liquid light behind shows through the pane. */
val FableGlass = Color(0xC71A1A1F)

/** Controls on a card (buttons, chips, icon buttons): a lift of white rather than more charcoal. */
val FableGlassRaised = Color(0x1AFFFFFF)

/** Chrome floating over scrolling content (the dock): 90%, content stays legible underneath. */
val FableGlassDeep = Color(0xE616161A)

/** Sheets, dialogs and snackbars: 94%, the topmost pane. */
val FableGlassSheet = Color(0xF0191920)

/** Shadow colours for glass panes: ambient is broad and faint, spot is tighter and deeper. */
val FableGlassShadow = Color(0x66000000)
val FableGlassShadowSpot = Color(0xB3000000)

/** Hairline between rows that share a pane. */
val FableDivider = Color(0x0FFFFFFF)

/**
 * Edge of text fields and other outlines that must read as interactive. Glass panes have no
 * border (see `Modifier.glassRim`); this is for Material components that insist on one.
 */
val FableOutline = Color(0x29FFFFFF)

/** Kept for Material components that take a flat container/border pair (switch tracks). */
val FableControl = FableGlassRaised
val FableControlBorder = Color(0x1AFFFFFF)
val FableGlassBorder = Color(0x0FFFFFFF)

// --- Accent and semantic colours.
val FableAccent = Color(0xFF7C5CFF)
val FableAccentLight = Color(0xFF9F8BFF)
val FableAccentDim = Color(0xFF4A3A8A)

/** Secondary hues for the liquid backdrop; chosen to sit next to the accent, not compete with it. */
val FableLiquidBlue = Color(0xFF3B7BF6)
val FableLiquidMagenta = Color(0xFFE879F9)
val FableLiquidTeal = Color(0xFF2DD4BF)

val FableText = Color(0xFFF2F2F5)
val FableTextDim = Color(0xFF8E8E99)
val FableSuccess = Color(0xFF4ADE80)
val FableWarn = Color(0xFFFBBF24)
val FableError = Color(0xFFF0525B)

// Dock items.
val DockBg = FableGlassDeep
val DockBorder = FableControlBorder
val DockItemActive = FableAccent
val DockItemIdle = Color(0xFF74747F)
