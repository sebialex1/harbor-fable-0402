package io.harbor.fable.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome OLED palette, modelled on the iOS dark system colours: a true-black canvas, grouped
// surfaces one step up in grey, and white as the only emphasis. There are no hues in the UI.
//
// Phase 1 overhaul: added glass/translucency support, a blue accent for topbar notifications, and
// gradient surface colours for depth that replaces the old opaque-only philosophy.

/** True black canvas, so OLED pixels are off behind the content. */
val FableBg = Color(0xFF000000)

/** Grouped-list surface (iOS secondarySystemGroupedBackground). */
val FableSurface = Color(0xFF1C1C1E)

/** One step above a grouped surface: controls, chips and tiles on a card (iOS tertiary). */
val FableSurfaceRaised = Color(0xFF2C2C2E)

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

/**
 * Blue accent for topbar-integrated notifications and progress indicators.
 * A muted, system-style blue that reads as informational without breaking the dark theme.
 */
val FableBlue = Color(0xFF4A9EFF)
val FableBlueDim = Color(0xFF2E6BCC)

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

// --- Glass surfaces: translucent overlays with optional blur for depth.
// On API 31+ these get a real RenderEffect blur; on older versions the higher alpha compensates.

/** Translucent panel fill for glass surfaces (drawn over content with blur). */
val GlassSurface = Color(0xCC1C1C1E)
val GlassSurfaceRaised = Color(0xCC2C2C2E)
val GlassBorder = Color(0x33FFFFFF)

/** Lighter glass for floating elements (topbar depth, notification overlays). */
val GlassLight = Color(0x801C1C1E)

/** Scrim behind glass overlays. */
val GlassScrim = Color(0x99000000)

// --- Gradient stops for depth and blending.

/** Vertical gradient that fades from a surface colour to transparent, for tile backgrounds. */
val GradientTopStop = Color(0x18FFFFFF)
val GradientBottomStop = Color(0x00000000)

/** White gradient for the play button's hollow glass effect. */
val PlayGradientTop = Color(0x22FFFFFF)
val PlayGradientBottom = Color(0x08FFFFFF)

/** Blue gradient for notification text glow. */
val NotifyGradientStart = Color(0x4D4A9EFF)
val NotifyGradientEnd = Color(0x004A9EFF)

// Tab bar: a floating, glass pill over the black canvas.
val TabBarBg = GlassSurface
val TabItemActive = FableText
val TabItemIdle = FableTextDim

// --- Phase 2+: frost, chrome glass and tile tones.

/** Soft light pooled inside glass surfaces (see `Modifier.glassSurface`). */
val GlassFrost = Color(0x14FFFFFF)

/** Top-bar glass once content scrolls beneath it: near-black at the top, more see-through below. */
val BarGlassTop = Color(0xF2000000)
val BarGlassBottom = Color(0xC70B0B0D)

/** Dim blue wash behind topbar notifications so the text stays legible over content. */
val NotifyWash = Color(0x1A4A9EFF)

/**
 * Gradient tones that give tiles personality without turning the UI into a rainbow: every tone
 * is a deep, desaturated two-stop gradient that sits quietly on black, with the glyph in a
 * light tint of the same hue. Each tone pairs (start, end, glyph).
 */
enum class TileTone(val start: Color, val end: Color, val glyph: Color) {
    Blue(Color(0xFF1D4E8F), Color(0xFF0E2340), Color(0xFFA9CDFF)),
    Indigo(Color(0xFF3B3A8C), Color(0xFF17163D), Color(0xFFC3C1FF)),
    Teal(Color(0xFF14636A), Color(0xFF072A2E), Color(0xFF9DE3E8)),
    Violet(Color(0xFF5B2F86), Color(0xFF241237), Color(0xFFDCC2FF)),
    Amber(Color(0xFF7A4A12), Color(0xFF2E1B06), Color(0xFFFFD49A)),
    Graphite(Color(0xFF3A3A3F), Color(0xFF161618), Color(0xFFE5E5EA)),
}
