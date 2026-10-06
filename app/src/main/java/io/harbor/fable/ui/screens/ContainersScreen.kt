package io.harbor.fable.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.components.GlassButton
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.*

@Composable
fun ContainersScreen() {
    val scrollState = rememberScrollState()
    var showCreate by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Containers", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FableText)
                Text("Manage your Wine environments", fontSize = 13.sp, color = FableTextDim)
            }
            GlassButton(
                text = "New",
                primary = true,
                icon = { Icon(Icons.Outlined.Add, contentDescription = "Create", tint = Color.White, modifier = Modifier.size(18.dp)) },
                onClick = { showCreate = true },
            )
        }

        Spacer(Modifier.height(24.dp))

        // Empty state
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = FableTextDim,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("No containers yet", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FableText)
                Text(
                    "Create a container to start managing Wine environments and adding executables",
                    fontSize = 13.sp,
                    color = FableTextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(20.dp))
                GlassButton(
                    text = "Create Container",
                    primary = true,
                    onClick = { showCreate = true },
                )
            }
        }

        // Container list would go here when data layer is ready
    }

    if (showCreate) {
        CreateContainerSheet(onDismiss = { showCreate = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContainerSheet(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("1280x720") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = FableSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
        ) {
            Text("New Container", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = FableText)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Container name", color = FableTextDim) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = FableText,
                    unfocusedTextColor = FableText,
                    focusedBorderColor = FableAccent,
                    unfocusedBorderColor = FableGlassBorder,
                ),
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = resolution,
                onValueChange = { resolution = it },
                label = { Text("Screen resolution", color = FableTextDim) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = FableText,
                    unfocusedTextColor = FableText,
                    focusedBorderColor = FableAccent,
                    unfocusedBorderColor = FableGlassBorder,
                ),
            )
            Spacer(Modifier.height(24.dp))

            GlassButton(
                text = "Create",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss() },
            )
        }
    }
}
