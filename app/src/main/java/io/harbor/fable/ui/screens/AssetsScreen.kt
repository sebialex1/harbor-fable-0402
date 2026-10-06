package io.harbor.fable.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.*

@Composable
fun AssetsScreen() {
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        Text("Assets", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FableText)
        Text("Download Wine, DXVK, Proton and more", fontSize = 13.sp, color = FableTextDim)

        Spacer(Modifier.height(24.dp))

        // Asset categories
        AssetCategoryCard("Wine Builds", "Compatibility layers for running Windows executables", Icons.Outlined.WineBar)
        Spacer(Modifier.height(12.dp))
        AssetCategoryCard("DXVK", "DirectX to Vulkan translation layer", Icons.Outlined.Games)
        Spacer(Modifier.height(12.dp))
        AssetCategoryCard("VKD3D", "DirectX 12 to Vulkan translation", Icons.Outlined.ViewInAr)
        Spacer(Modifier.height(12.dp))
        AssetCategoryCard("Proton", "Steam's compatibility tool (experimental)", Icons.Outlined.Science)
        Spacer(Modifier.height(12.dp))
        AssetCategoryCard("Runtimes", "Visual C++ redistributables and dependencies", Icons.Outlined.Extension)
        Spacer(Modifier.height(24.dp))

        // Download queue
        Text("Download Queue", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = FableTextDim)
        Spacer(Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = FableTextDim, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("No downloads in queue", fontSize = 14.sp, color = FableText)
                Text("Select an asset above to start downloading", fontSize = 12.sp, color = FableTextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun AssetCategoryCard(name: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(GlassRadius.value.toInt().dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(FableAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = name, tint = FableAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FableText)
                Text(description, fontSize = 12.sp, color = FableTextDim, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = FableTextDim, modifier = Modifier.size(20.dp))
        }
    }
}
