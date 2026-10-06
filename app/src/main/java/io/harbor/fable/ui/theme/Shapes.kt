package io.harbor.fable.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FableShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

// Glass card corner radius — rounded for the frosted look, tight enough for dense lists.
val GlassRadius = 20.dp
val DockRadius = 28.dp

// Buttons, text fields and selectable controls.
val ControlRadius = 14.dp

// Chips, pills and icon tiles.
val ChipRadius = 10.dp
