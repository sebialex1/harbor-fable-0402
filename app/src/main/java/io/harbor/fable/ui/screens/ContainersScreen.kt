package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.components.GlassButton
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ContainersScreen() {
    val context = LocalContext.current
    val repository = remember(context) { FableApp.from(context).containerRepository }
    val containers by repository.containers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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
                icon = {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "Create",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = { showCreate = true },
            )
        }

        Spacer(Modifier.height(24.dp))

        if (containers.isEmpty()) {
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
        } else {
            containers.forEach { container ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(container.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = FableText)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (container.status) {
                                    ContainerStatus.READY -> FableAccent
                                    ContainerStatus.RUNNING -> FableSuccess
                                    ContainerStatus.ERROR -> FableError
                                    else -> FableGlass
                                },
                            ) {
                                Text(
                                    container.status.name,
                                    fontSize = 11.sp,
                                    color = FableText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Wine: ${container.wineVersion}", fontSize = 12.sp, color = FableTextDim)
                        Text("Driver: ${container.graphicsDriver}", fontSize = 12.sp, color = FableTextDim)
                        if (container.dxvkVersion != null) {
                            Text("DXVK: ${container.dxvkVersion}", fontSize = 12.sp, color = FableTextDim)
                        }
                        Text("Resolution: ${container.screenResolution}", fontSize = 12.sp, color = FableTextDim)
                        Spacer(Modifier.height(12.dp))
                        GlassButton(
                            text = "Delete",
                            primary = false,
                            icon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                    tint = FableText,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                scope.launch { repository.delete(container.id) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showCreate) {
        CreateContainerSheet(
            onDismiss = { showCreate = false },
            onCreate = { name, resolution ->
                scope.launch {
                    repository.create(
                        name = name,
                        screenResolution = resolution,
                    )
                }
                showCreate = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContainerSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String, resolution: String) -> Unit,
) {
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
                onClick = {
                    if (name.isNotBlank()) {
                        onCreate(name.trim(), resolution.trim())
                    }
                },
            )
        }
    }
}
