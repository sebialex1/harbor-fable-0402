package io.harbor.fable.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FableShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Radii grow with the size of the pane: a chip is tighter than a card, a card tighter than a
// sheet, so nested glass keeps concentric corners.

/** Glass cards: large enough to read as frosted panes, tight enough for dense lists. */
val GlassRadius = 24.dp

/** The floating dock. */
val DockRadius = 30.dp

/** Modal sheets (top corners) and dialogs. */
val SheetRadius = 32.dp
val DialogRadius = 28.dp

/** Buttons, text fields and selectable controls. */
val ControlRadius = 16.dp
val ControlRadiusCompact = 12.dp

/** Chips and icon tiles. */
val ChipRadius = 10.dp

/** Pills: fully rounded. */
val PillRadius = 50.dp

/** Width of the light rim on glass panes (see `Modifier.glassRim`). */
val GlassEdgeStroke = 1.dp
