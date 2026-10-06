package io.harbor.fable.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.components.GlassButton
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.*

@Composable
fun HomeScreen() {
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        // Hero header
        Text(
            "Fable",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = FableText,
        )
        Text(
            "Minimal Wine container manager",
            fontSize = 14.sp,
            color = FableTextDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(28.dp))

        // Quick stats row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard("Containers", "0", FableAccent, Modifier.weight(1f))
            StatCard("Drivers", "0", FableSuccess, Modifier.weight(1f))
            StatCard("Assets", "0", FableWarn, Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        // Quick actions
        Text(
            "Quick Actions",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = FableTextDim,
        )
        Spacer(Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                ActionRow("Create Container", "Set up a new Wine environment", Icons.Outlined.Add)
                ActionRow("Import Driver", "Load an adrenotools driver zip", Icons.Outlined.Memory)
                ActionRow("Download Assets", "Get DXVK, Wine, Proton", Icons.Outlined.Download)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Recent activity
        Text(
            "Recent",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = FableTextDim,
        )
        Spacer(Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No recent activity",
                    fontSize = 14.sp,
                    color = FableTextDim,
                )
                Text(
                    "Create a container or import a driver to get started",
                    fontSize = 12.sp,
                    color = FableTextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier) {
    GlassCard(modifier = modifier) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = accent)
            Text(label, fontSize = 11.sp, color = FableTextDim, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(FableAccent.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = title, tint = FableAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = FableText)
            Text(subtitle, fontSize = 12.sp, color = FableTextDim)
        }
    }
}
