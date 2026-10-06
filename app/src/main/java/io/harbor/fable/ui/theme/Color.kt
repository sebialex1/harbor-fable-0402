package io.harbor.fable.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome OLED palette, modelled on the iOS dark system colours: a true-black canvas, grouped
// surfaces one step up in grey, and white as the only emphasis. There are no hues in the UI.

/** True black canvas, so OLED pixels are off behind the content. */
val FableBg = Color(0xFF000000)

/** Grouped-list surface (iOS secondarySystemGroupedBackground). */
val FableSurface = Color(0xFF1C1C1E)

/** One step above a grouped surface: controls, chips and tiles on a card (iOS tertiary). */
val FableSurfaceRaised = Color(0xFF2C2C2E)

// --- Surfaces. Every surface is opaque: depth comes from a lighter grey, never from
// translucency, blur or shadows.

/** Pressed and selected state of a raised control. */
val FableSurfaceHigh = Color(0xFF3A3A3C)

/** Subtle hairline edge around solid surfaces. */
val FableBorder = Color(0x1AFFFFFF)

/** Hairline between rows that share a section (iOS separator). */
val FableDivider = Color(0xFF38383A)

/** Outline for text fields and other controls that must read as interactive. */
val FableOutline = Color(0x33FFFFFF)

/** Track of switches and progress lines when off or empty. */
val FableTrack = Color(0xFF39393D)

/** Material components that take a flat container/border pair (switch tracks). */
val FableControl = FableTrack
val FableControlBorder = Color(0x00000000)

// --- Emphasis. White is the accent: primary buttons, selection, progress. Content on it is black.
val FableAccent = Color(0xFFFFFFFF)
val FableAccentLight = Color(0xFFE5E5EA)
val FableOnAccent = Color(0xFF000000)

/** Label colours (iOS label / secondaryLabel / tertiaryLabel on black). */
val FableText = Color(0xFFFFFFFF)
val FableTextDim = Color(0xFF8E8E93)
val FableTextFaint = Color(0xFF636366)

/** Status tones. Success and warning are greys: the words carry the meaning, not the colour. */
val FableSuccess = FableTextDim
val FableWarn = Color(0xFFD1D1D6)

/**
 * Failures and destructive actions. Still no hue: they read as full white next to otherwise grey
 * text, and carry an icon or the word itself.
 */
val FableError = FableText

// Tab bar: a floating, opaque dark-grey pill over the black canvas.
val TabBarBg = FableSurface
val TabItemActive = FableText
val TabItemIdle = FableTextDim
