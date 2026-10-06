package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.*

@Composable
fun SettingsScreen() {
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FableText)
        Spacer(Modifier.height(24.dp))

        SettingsSection("General") {
            SettingRow("Storage location", "/sdcard/Fable", Icons.Outlined.Folder)
            SettingRow("Default resolution", "1280x720", Icons.Outlined.AspectRatio)
            SettingRow("Default Wine version", "wine-9.0", Icons.Outlined.WineBar)
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection("Graphics") {
            SettingRow("Default driver", "Turnip (system)", Icons.Outlined.Memory)
            SettingRow("Vsync", "Enabled", Icons.Outlined.Sync)
            SettingRow("Frame pacing", "Adaptive", Icons.Outlined.Speed)
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection("About") {
            SettingRow("Version", "0.1.0", Icons.Outlined.Info)
            SettingRow("Architecture", "arm64-v8a", Icons.Outlined.Architecture)
            SettingRow("License", "MIT", Icons.Outlined.Description)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = FableTextDim)
    Spacer(Modifier.height(12.dp))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), content = content)
    }
}

@Composable
private fun SettingRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = FableTextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 14.sp, color = FableText, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = FableTextDim)
    }
}
