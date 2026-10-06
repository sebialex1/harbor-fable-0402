package io.harbor.fable.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FableShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Glass cards: large enough to read as frosted panels, tight enough for dense lists.
val GlassRadius = 22.dp
val DockRadius = 28.dp

// Buttons, text fields and selectable controls.
val ControlRadius = 14.dp

// Chips, pills and icon tiles.
val ChipRadius = 8.dp
