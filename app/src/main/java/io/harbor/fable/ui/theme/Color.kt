package io.harbor.fable.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome OLED palette, modelled on the iOS dark system colours: a true-black canvas, grouped
// surfaces one step up in grey, and white as the only emphasis. There are no hues in the UI; the
// single exception is [FableError], reserved for destructive actions and real failures.

/** True black canvas, so OLED pixels are off behind the content. */
val FableBg = Color(0xFF000000)

/** Grouped-list surface (iOS secondarySystemGroupedBackground). */
val FableSurface = Color(0xFF1C1C1E)

/** One step above a grouped surface: controls, chips and tiles on a card (iOS tertiary). */
val FableSurfaceRaised = Color(0xFF2C2C2E)

// --- Materials. Opaque surfaces sit in lists; only chrome floating over moving content (the tab
// bar, the top bar, sheets) is translucent, and those are blurred where the platform can.

/** Rows and grouped sections. Opaque: nothing moves behind a list section. */
val FableGlass = FableSurface

/** Controls on a surface (secondary buttons, chips, icon buttons): a neutral lift of white. */
val FableGlassRaised = Color(0x24FFFFFF)

/** Tint of the tab bar material over its blurred backdrop (see GlassDock). */
val FableGlassDeep = Color(0xB8141416)

/** Sheets, dialogs and snackbars. */
val FableGlassSheet = Color(0xFF1C1C1E)

/** Shadow colours for floating panes. On true black they only soften the edge. */
val FableGlassShadow = Color(0x66000000)
val FableGlassShadowSpot = Color(0x99000000)

/** Hairline between rows that share a section (iOS separator). */
val FableDivider = Color(0xFF38383A)

/** Outline for text fields and other controls that must read as interactive. */
val FableOutline = Color(0x33FFFFFF)

/** Material components that take a flat container/border pair (switch tracks). */
val FableControl = Color(0xFF39393D)
val FableControlBorder = Color(0x00000000)
val FableGlassBorder = Color(0x1AFFFFFF)

// --- Emphasis. White is the accent: primary buttons, selection, progress.
val FableAccent = Color(0xFFFFFFFF)
val FableAccentLight = Color(0xFFE5E5EA)
val FableAccentDim = Color(0xFF8E8E93)

/** Label colours (iOS label / secondaryLabel / tertiaryLabel on black). */
val FableText = Color(0xFFFFFFFF)
val FableTextDim = Color(0xFF8E8E93)
val FableTextFaint = Color(0xFF636366)

/** Status tones. Success and warning are greys: the words carry the meaning, not the colour. */
val FableSuccess = FableTextDim
val FableWarn = Color(0xFFD1D1D6)

/** The one hue: destructive actions and failures only (iOS systemRed, dark). */
val FableError = Color(0xFFFF453A)

// Tab bar.
val DockBg = FableGlassDeep
val DockBorder = FableGlassBorder
val DockItemActive = FableText
val DockItemIdle = Color(0xFF7C7C80)
