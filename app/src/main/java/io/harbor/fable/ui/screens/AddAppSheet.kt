package io.harbor.fable.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.harbor.fable.data.ExeIcons
import io.harbor.fable.data.GameFolders
import io.harbor.fable.data.formatBytes
import io.harbor.fable.ui.theme.CardRadius
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.FableSurface
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.HairlineStroke
import io.harbor.fable.ui.theme.Motion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.harbor.fable.ui.icons.FableIcons

/**
 * Adds an app/game: pick a file (or the game's whole folder), name it and assign it to a
 * container.
 *
 * "Choose game folder" is what games need: a Unity game's .exe (ULTRAKILL) imports
 * `UnityPlayer.dll` and reads `<Name>_Data/` next to it, and a single picked file gives Fable
 * access to that one document only. With a folder (a tree grant) the launch runs the game in
 * place or copies the whole folder into the container ([GameFolders]); the sheet lists the
 * folder's .exe files and preselects the likely main one.
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
    var pickedSize by remember { mutableStateOf(0L) }
    // The program's own icon, read from the picked EXE; null shows the initials tile.
    var pickedIcon by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pickedUri) {
        pickedIcon = null
        pickedUri?.let { uri -> pickedIcon = ExeIcons.extract(context, uri) }
    }
    // "Choose game folder": the tree, its .exe files (relative path to entry) and the chosen one.
    var pickedFolder by remember { mutableStateOf<Uri?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var folderExes by remember { mutableStateOf<List<GameFolders.Entry>>(emptyList()) }
    var folderExe by remember { mutableStateOf<String?>(null) }
    var scanningFolder by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    fun selectFolderExe(relative: String) {
        val tree = pickedFolder ?: return
        val entry = folderExes.firstOrNull { it.relativePath == relative } ?: return
        folderExe = relative
        pickedUri = GameFolders(context).documentUri(tree, entry)
        pickedFileName = relative
        pickedSize = entry.size.coerceAtLeast(0)
    }
    LaunchedEffect(pickedFolder) {
        val tree = pickedFolder ?: return@LaunchedEffect
        scanningFolder = true
        folderError = null
        val scan = withContext(Dispatchers.IO) {
            val folders = GameFolders(context)
            val entries = folders.list(tree, maxDepth = FOLDER_SCAN_DEPTH)
            val title = runCatching { DocumentFile.fromTreeUri(context, tree)?.name }.getOrNull()
            Triple(entries, title, entries.filter { it.isDirectory }.map { it.relativePath }.toSet())
        }
        val (entries, title, dirs) = scan
        val exes = entries.filter { !it.isDirectory && it.relativePath.endsWith(".exe", ignoreCase = true) }
            .sortedWith(compareBy<GameFolders.Entry> { it.relativePath.count { c -> c == '/' } }.thenBy { it.relativePath.lowercase() })
        folderName = title
        folderExes = exes
        scanningFolder = false
        val main = GameFolders.pickMainExe(exes.map { it.relativePath to it.size }, title, dirs)
        if (main == null) {
            folderError = if (entries.isEmpty()) "Couldn't read that folder" else "No .exe in that folder"
            pickedUri = null
            folderExe = null
        } else {
            selectFolderExe(main)
            if (name.isBlank()) name = title ?: main.substringAfterLast('/').substringBeforeLast('.')
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            // The launch reads the folder later (and after restarts): keep the grant.
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            folderExes = emptyList()
            folderExe = null
            pickedFolder = tree
        }
    }
    var chosenContainerId by remember { mutableStateOf(preselectedContainerId) }
    val containerId = containers.firstOrNull { it.id == chosenContainerId }?.id ?: containers.firstOrNull()?.id

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access across restarts; not every provider supports persisting.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val fileName = resolveFileName(context, uri)
            // A single file replaces a folder chosen before.
            pickedFolder = null
            folderName = null
            folderExes = emptyList()
            folderExe = null
            folderError = null
            pickedUri = uri
            pickedFileName = fileName
            pickedSize = resolveFileSize(context, uri)
            if (name.isBlank()) name = fileName.substringBeforeLast('.', fileName)
        }
    }

    FableSheet(
        title = "Add App",
        onDismiss = onDismiss,
    ) { close ->
        // The banner is the file picker: the program's icon and name once chosen.
        AddAppBanner(
            name = name.trim(),
            fileName = pickedFileName,
            sizeBytes = pickedSize,
            icon = pickedIcon,
            picked = pickedUri != null,
            onClick = { picker.launch(arrayOf("*/*")) },
        )
        FableButton(
            text = when {
                scanningFolder -> "Reading folder\u2026"
                pickedFolder != null -> "Game folder: ${folderName ?: "chosen"}"
                else -> "Choose game folder"
            },
            enabled = !scanningFolder,
            modifier = Modifier.fillMaxWidth(),
            onClick = { folderPicker.launch(null) },
        )
        Text(
            text = folderError
                ?: "For games: pick the folder the game's .exe is in, so its DLLs and data come along " +
                    "(Unity games like ULTRAKILL need UnityPlayer.dll and <Name>_Data next to the .exe).",
            style = MaterialTheme.typography.bodySmall,
            color = FableTextDim,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        if (pickedFolder != null && folderExes.size > 1) {
            FableCard {
                OptionSelector(
                    label = "Program",
                    options = folderExes.map { SelectOption(it.relativePath, it.relativePath) },
                    selected = folderExe.orEmpty(),
                    onSelect = { selectFolderExe(it) },
                )
            }
        }
        if (containers.isNotEmpty()) {
            FableCard {
                OptionSelector(
                    label = "Container",
                    options = containers.map { SelectOption(it.id, it.name) },
                    selected = containerId.orEmpty(),
                    onSelect = { chosenContainerId = it },
                )
            }
        }
        FableTextField(
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
        FableButton(
            text = "Add",
            primary = true,
            enabled = pickedUri != null && name.isNotBlank() && containerId != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val uri = pickedUri
                val target = containerId
                if (uri != null && target != null && name.isNotBlank()) {
                    val appName = name.trim()
                    val icon = pickedIcon
                    val folder = pickedFolder?.takeIf { folderExe != null }?.toString()
                    val exeInFolder = folderExe.takeIf { folder != null }
                    close {
                        onDismiss()
                        fableUi.scope.launch {
                            val iconPath = icon?.let { ExeIcons.save(context, it) }
                            runCatching { repository.addExe(target, appName, uri.toString(), iconPath, folder, exeInFolder) }
                                .onSuccess { fableUi.showMessage("$appName added") }
                                .onFailure { fableUi.showMessage(it.message ?: "Couldn't add $appName", long = true) }
                        }
                    }
                }
            },
        )
    }
}

/**
 * Header of the Add App sheet: a tall raised banner with the program's icon (or initials), its
 * name and the picked file. Before a file is chosen it invites the pick; tapping it any time
 * opens the file picker.
 */
@Composable
private fun AddAppBanner(
    name: String,
    fileName: String,
    sizeBytes: Long,
    icon: Bitmap?,
    picked: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CardRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(FableSurfaceRaised, FableSurface)))
            .border(HairlineStroke, FableBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = picked,
                transitionSpec = {
                    (fadeIn(Motion.enter(Motion.Quick)) + scaleIn(Motion.pop(), initialScale = 0.85f)) togetherWith
                        fadeOut(Motion.exit(Motion.Fast))
                },
                label = "addAppBannerIcon",
            ) { hasFile ->
                if (hasFile) {
                    ExeIcon(name = name.ifBlank { fileName }, bitmap = icon, size = BannerIconSize)
                } else {
                    IconTile(icon = FableIcons.OpenFile, size = BannerIconSize, tint = FableTextDim)
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = if (picked) name.ifBlank { fileName } else "Choose a Program",
                style = MaterialTheme.typography.titleMedium,
                color = FableText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = if (picked) {
                    listOfNotNull(fileName.ifBlank { null }, formatBytes(sizeBytes).takeIf { sizeBytes > 0 })
                        .joinToString(" · ")
                } else {
                    "A Windows .exe from your files"
                },
                style = MaterialTheme.typography.bodySmall,
                color = FableTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val BannerIconSize = 72.dp

/** How deep "Choose game folder" looks for .exe files (0 = the folder itself). */
private const val FOLDER_SCAN_DEPTH = 1

/** Size of a document [uri] in bytes, or 0 when the provider does not say. */
internal fun resolveFileSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
}.getOrNull() ?: 0L

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
