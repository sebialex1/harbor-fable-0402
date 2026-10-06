package io.harbor.fable.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.components.GlassButton
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.*

@Composable
fun DriversScreen() {
    val scrollState = rememberScrollState()
    var showImport by remember { mutableStateOf(false) }

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
                Text("Drivers", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FableText)
                Text("Adrenotools Vulkan driver packages", fontSize = 13.sp, color = FableTextDim)
            }
            GlassButton(
                text = "Import",
                primary = true,
                icon = { Icon(Icons.Outlined.FileUpload, contentDescription = "Import", tint = Color.White, modifier = Modifier.size(18.dp)) },
                onClick = { showImport = true },
            )
        }

        Spacer(Modifier.height(24.dp))

        // Available driver sources
        Text("Available Sources", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = FableTextDim)
        Spacer(Modifier.height(12.dp))

        // JimVulkan Xclipse driver card
        DriverSourceCard(
            name = "RADV Xclipse (JimVulkan)",
            version = "Mesa 26.3.0-devel",
            description = "Custom RADV driver for Samsung Xclipse 920/530 (RDNA2)",
            vulkanVersion = "Vulkan 1.4.358",
            fileSize = "18.9 MB",
            isDownloaded = false,
        )

        Spacer(Modifier.height(12.dp))

        // Turnip driver card
        DriverSourceCard(
            name = "Turnip (Mesa)",
            version = "Latest",
            description = "Open-source Adreno driver for Snapdragon GPUs",
            vulkanVersion = "Vulkan 1.3+",
            fileSize = "Varies",
            isDownloaded = false,
        )

        Spacer(Modifier.height(24.dp))

        // Installed drivers section
        Text("Installed", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = FableTextDim)
        Spacer(Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Memory, contentDescription = null, tint = FableTextDim, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("No drivers installed", fontSize = 14.sp, color = FableText)
                Text("Import a .zip adrenotools driver package to begin", fontSize = 12.sp, color = FableTextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DriverSourceCard(
    name: String,
    version: String,
    description: String,
    vulkanVersion: String,
    fileSize: String,
    isDownloaded: Boolean,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Driver icon
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(FableAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Memory, contentDescription = null, tint = FableAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            // Info
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FableText)
                Text(description, fontSize = 12.sp, color = FableTextDim, modifier = Modifier.padding(top = 2.dp))
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TagChip(version)
                    TagChip(vulkanVersion)
                    TagChip(fileSize)
                }
            }
            Spacer(Modifier.width(8.dp))
            // Action
            GlassButton(
                text = if (isDownloaded) "Installed" else "Get",
                primary = !isDownloaded,
                onClick = {},
            )
        }
    }
}

@Composable
private fun TagChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FableGlass)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 10.sp, color = FableTextDim)
    }
}
