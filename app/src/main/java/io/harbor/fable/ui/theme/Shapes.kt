package io.harbor.fable.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FableShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

// Radii grow with the size of the pane: a chip is tighter than a card, a card tighter than a
// sheet, so nested glass keeps concentric corners.

/** Inset grouped sections, as in iOS Settings. */
val GlassRadius = 12.dp

/** The floating dock. */
val DockRadius = 26.dp

/** Modal sheets (top corners) and dialogs. */
val SheetRadius = 14.dp
val DialogRadius = 16.dp

/** Text fields. Buttons are pills (see [PillRadius]). */
val ControlRadius = 10.dp
val ControlRadiusCompact = 8.dp

/** Chips and icon tiles. */
val ChipRadius = 7.dp

/** Pills: fully rounded. */
val PillRadius = 50.dp

/** Width of the hairline edge on floating materials (see `Modifier.glassRim`). */
val GlassEdgeStroke = 0.5.dp
