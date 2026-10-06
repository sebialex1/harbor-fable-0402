package io.harbor.fable.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Adds an app/game: pick a file, name it and assign it to a container.
 *
 * Shared by the Home screen and the container detail screen. When [preselectedContainerId]
 * is set (detail screen) the container selector starts on that container.
 */
@Composable
fun AddAppSheet(
    onDismiss: () -> Unit,
    preselectedContainerId: String? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.containerRepository
    val fableUi = LocalFableUi.current
    val containers by repository.containers.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf("") }
    var chosenContainerId by remember { mutableStateOf(preselectedContainerId) }
    val containerId = containers.firstOrNull { it.id == chosenContainerId }?.id ?: containers.firstOrNull()?.id

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access across restarts; not every provider supports persisting.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val fileName = resolveFileName(context, uri)
            pickedUri = uri
            pickedFileName = fileName
            if (name.isBlank()) name = fileName.substringBeforeLast('.', fileName)
        }
    }

    FableSheet(
        title = "Add App",
        onDismiss = onDismiss,
    ) { close ->
        // File and container share one section; the name is the only thing typed.
        GlassCard {
            InfoRow(
                label = "File",
                value = pickedFileName.ifBlank { "Choose" },
                valueColor = if (pickedFileName.isBlank()) FableText else FableTextDim,
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            if (containers.isNotEmpty()) {
                CardDivider()
                OptionSelector(
                    label = "Container",
                    options = containers.map { SelectOption(it.id, it.name) },
                    selected = containerId.orEmpty(),
                    onSelect = { chosenContainerId = it },
                )
            }
        }
        GlassTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
        )
        if (containers.isEmpty()) {
            Text(
                text = "Create a container first.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        GlassButton(
            text = "Add",
            primary = true,
            enabled = pickedUri != null && name.isNotBlank() && containerId != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val uri = pickedUri
                val target = containerId
                if (uri != null && target != null && name.isNotBlank()) {
                    val appName = name.trim()
                    close {
                        onDismiss()
                        fableUi.scope.launch {
                            runCatching { repository.addExe(target, appName, uri.toString()) }
                                .onSuccess { fableUi.showMessage("$appName added") }
                                .onFailure { fableUi.showMessage(it.message ?: "Couldn't add $appName", long = true) }
                        }
                    }
                }
            },
        )
    }
}

/** Display name of a document [uri], falling back to its last path segment. */
internal fun resolveFileName(context: Context, uri: Uri): String {
    val documentName = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
    if (!documentName.isNullOrBlank()) return documentName
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    if (!queried.isNullOrBlank()) return queried
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "app.exe"
}
