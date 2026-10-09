package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.harbor.fable.app.FableApp
import io.harbor.fable.display.controls.ControlProfileRepository
import io.harbor.fable.display.controls.ProfileResult
import io.harbor.fable.display.controls.StoredProfile
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.icons.FableIcons
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableTextFaint
import io.harbor.fable.ui.theme.Spacing
import io.harbor.fable.ui.theme.TileTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the on-screen control presets (`.icp`) outside a running container: pick the default,
 * add, rename, duplicate, export and delete, and rescan the import folders. See
 * [ControlProfileRepository] for storage and the delete policy.
 *
 * The preset marked here is the global default; a container that picked its own in the
 * in-container menu keeps it. [onEdit] is where the visual editor plugs in: while it is null the
 * "Edit" action is simply not offered.
 */
@Composable
fun ControlsScreen(
    onBack: () -> Unit,
    onEdit: ((StoredProfile) -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val fableUi = app.fableUi
    val repository = remember(context) { ControlProfileRepository(context) }
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<StoredProfile>?>(null) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var hiddenBuiltins by remember { mutableStateOf(0) }

    /** Runs [block] off the main thread, then refreshes the list from disk. */
    fun reload(block: () -> Unit = {}) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                block()
                repository.load()
            }
            profiles = loaded
            selectedFile = repository.selected(loaded, null)?.file?.name
            hiddenBuiltins = repository.hiddenBuiltins().size
        }
    }
    LaunchedEffect(Unit) { reload() }

    /** Runs a management call that can be refused, shows its message, and refreshes. */
    fun manage(success: String?, call: () -> ProfileResult<StoredProfile>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { call() }
            when (result) {
                is ProfileResult.Ok -> success?.let(fableUi::showMessage)
                is ProfileResult.Failed -> fableUi.showMessage(result.message, long = true)
            }
            reload()
        }
    }

    ControlsContent(
        profiles = profiles,
        selectedFile = selectedFile,
        hiddenBuiltins = hiddenBuiltins,
        importHint = "Copy Winlator .icp files to Android/data/${context.packageName}/files/profiles " +
            "or Download/Winlator/profiles, then tap Import now.",
        onBack = onBack,
        onSelect = { profile ->
            repository.select(profile, null)
            selectedFile = profile.file.name
        },
        onEdit = onEdit,
        onCreate = { name, template -> manage("Created $name") { repository.create(name, template) } },
        onRename = { profile, name -> manage("Renamed to ${name.trim()}") { repository.rename(profile, name) } },
        onDuplicate = { profile -> manage("Duplicated ${profile.profile.name}") { repository.duplicate(profile) } },
        onExport = { profile ->
            scope.launch {
                val file = withContext(Dispatchers.IO) { repository.export(profile) }
                fableUi.showMessage(
                    if (file != null) "Saved ${file.absolutePath}" else "Couldn't export ${profile.profile.name}",
                    long = true,
                )
            }
        },
        onDelete = { profile ->
            scope.launch {
                val deleted = withContext(Dispatchers.IO) { repository.delete(profile) }
                fableUi.showMessage(if (deleted) "Deleted ${profile.profile.name}" else "Couldn't delete ${profile.profile.name}")
                reload()
            }
        },
        onImportNow = {
            scope.launch {
                val before = profiles?.size ?: 0
                val loaded = withContext(Dispatchers.IO) { repository.importNow() }
                profiles = loaded
                selectedFile = repository.selected(loaded, null)?.file?.name
                val added = loaded.size - before
                fableUi.showMessage(if (added > 0) "Imported $added preset(s)" else "Nothing new to import")
            }
        },
        onRestoreBuiltins = {
            reload { repository.restoreBuiltins() }
        },
    )
}

@Composable
internal fun ControlsContent(
    profiles: List<StoredProfile>?,
    selectedFile: String?,
    hiddenBuiltins: Int,
    importHint: String,
    onBack: () -> Unit,
    onSelect: (StoredProfile) -> Unit,
    onEdit: ((StoredProfile) -> Unit)?,
    onCreate: (name: String, template: StoredProfile?) -> Unit,
    onRename: (StoredProfile, String) -> Unit,
    onDuplicate: (StoredProfile) -> Unit,
    onExport: (StoredProfile) -> Unit,
    onDelete: (StoredProfile) -> Unit,
    onImportNow: () -> Unit,
    onRestoreBuiltins: () -> Unit,
) {
    val appear = rememberEntrance()
    var showCreate by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<StoredProfile?>(null) }
    var renaming by remember { mutableStateOf<StoredProfile?>(null) }
    var deleting by remember { mutableStateOf<StoredProfile?>(null) }
    val selected = profiles?.firstOrNull { it.file.name == selectedFile }

    FableScreen(title = "Controls", onBack = onBack) {
        item(key = "presets-label") { SectionLabel("Presets", Modifier.animateItem().entrance(appear, 0)) }
        when {
            profiles == null -> item(key = "loading") { LoadingCard("Loading presets", Modifier.animateItem()) }
            profiles.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = FableIcons.Apps,
                    title = "No presets",
                    message = "Create one, import a Winlator .icp, or restore the built-ins.",
                    modifier = Modifier.animateItem(),
                )
            }
            else -> item(key = "presets") {
                FableCard(Modifier.animateItem().entrance(appear, 0)) {
                    profiles.forEachIndexed { index, profile ->
                        if (index > 0) CardDivider(afterIcon = true)
                        ProfileRow(
                            profile = profile,
                            isSelected = profile.file.name == selectedFile,
                            onSelect = { onSelect(profile) },
                            onMore = { actionsFor = profile },
                        )
                    }
                }
            }
        }

        item(key = "manage-label") { SectionLabel("Manage", Modifier.animateItem().entrance(appear, 1)) }
        item(key = "manage") {
            FableCard(Modifier.animateItem().entrance(appear, 1)) {
                ListRow(
                    title = "New Profile",
                    subtitle = "Blank, or a copy of an existing preset",
                    icon = FableIcons.Add,
                    onClick = { showCreate = true },
                )
                CardDivider(afterIcon = true)
                ListRow(
                    title = "Import Now",
                    subtitle = "Rescan the Winlator and app profile folders",
                    icon = FableIcons.Download,
                    onClick = onImportNow,
                )
                if (hiddenBuiltins > 0) {
                    CardDivider(afterIcon = true)
                    ListRow(
                        title = "Restore Built-in Presets",
                        subtitle = "$hiddenBuiltins deleted",
                        icon = FableIcons.Refresh,
                        onClick = onRestoreBuiltins,
                    )
                }
            }
        }
        item(key = "hint") {
            Text(
                text = "$importHint The preset marked here is the default; a container that picked its own " +
                    "in the in-game menu keeps it.",
                style = MaterialTheme.typography.bodySmall,
                color = FableTextFaint,
                modifier = Modifier
                    .animateItem()
                    .entrance(appear, 2)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
    }

    if (showCreate) {
        NameSheet(
            title = "New Profile",
            confirmLabel = "Create",
            initial = "",
            template = selected,
            onDismiss = { showCreate = false },
            onConfirm = { name, template ->
                showCreate = false
                onCreate(name, template)
            },
        )
    }

    renaming?.let { target ->
        NameSheet(
            title = "Rename Profile",
            confirmLabel = "Rename",
            initial = target.profile.name,
            template = null,
            onDismiss = { renaming = null },
            onConfirm = { name, _ ->
                renaming = null
                onRename(target, name)
            },
        )
    }

    actionsFor?.let { target ->
        FableSheet(
            title = target.profile.name,
            subtitle = if (target.builtin) "Built-in preset" else "${target.file.name}",
            onDismiss = { actionsFor = null },
        ) { close ->
            FableCard {
                if (onEdit != null) {
                    ListRow(title = "Edit", icon = FableIcons.OpenFile, onClick = { close { actionsFor = null; onEdit(target) } })
                    CardDivider(afterIcon = true)
                }
                if (!target.builtin) {
                    ListRow(title = "Rename", icon = FableIcons.Checklist, onClick = { close { actionsFor = null; renaming = target } })
                    CardDivider(afterIcon = true)
                }
                ListRow(title = "Duplicate", icon = FableIcons.Add, onClick = { close { actionsFor = null; onDuplicate(target) } })
                CardDivider(afterIcon = true)
                ListRow(title = "Export", icon = FableIcons.Forward, onClick = { close { actionsFor = null; onExport(target) } })
                CardDivider(afterIcon = true)
                ListRow(
                    title = "Delete",
                    titleColor = FableError,
                    icon = FableIcons.Trash,
                    iconTint = FableError,
                    showChevron = false,
                    onClick = { close { actionsFor = null; deleting = target } },
                )
            }
        }
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.profile.name}?",
            message = if (target.builtin) {
                "It stays deleted across updates. Restore Built-in Presets brings it back."
            } else {
                "This can't be undone. Containers using it fall back to the default preset."
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleting = null
                onDelete(target)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: StoredProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMore: () -> Unit,
) {
    val controls = profile.profile.elements.count { it.isSupported }
    ListRow(
        title = profile.profile.name,
        subtitle = if (controls == 1) "1 control" else "$controls controls",
        onClick = onSelect,
        showChevron = false,
        leading = {
            ToneIconTile(
                icon = if (isSelected) FableIcons.Check else FableIcons.Apps,
                tone = if (isSelected) TileTone.Indigo else TileTone.Graphite,
            )
        },
        titleBadge = if (isSelected || profile.builtin) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (isSelected) Pill("Default")
                    if (profile.builtin) Pill("Built-in")
                }
            }
        } else {
            null
        },
        trailing = {
            FableIconButton(
                icon = FableIcons.More,
                contentDescription = "Actions for ${profile.profile.name}",
                onClick = onMore,
            )
        },
    )
}

/** Name entry sheet for creating (with an optional base to copy) and renaming. */
@Composable
private fun NameSheet(
    title: String,
    confirmLabel: String,
    initial: String,
    template: StoredProfile?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, template: StoredProfile?) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var copyTemplate by remember { mutableStateOf(false) }
    FableSheet(title = title, onDismiss = onDismiss) { close ->
        FableTextField(value = name, onValueChange = { name = it }, label = "Name")
        if (template != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FableChip(text = "Blank", selected = !copyTemplate, onClick = { copyTemplate = false })
                FableChip(text = "Copy of ${template.profile.name}", selected = copyTemplate, onClick = { copyTemplate = true })
            }
        }
        FableButton(
            text = confirmLabel,
            primary = true,
            enabled = name.isNotBlank(),
            onClick = { close { onConfirm(name, template.takeIf { copyTemplate }) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
