package com.multiprofileemu.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiprofileemu.app.AppController
import com.multiprofileemu.input.GamepadAction
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.model.SaveProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The actions a profile row can expose; which ones apply depends on whether it's the active profile. */
private enum class ProfileAction(val label: String) {
    SWITCH("Cambiar a este"),
    SYNC("Sincronizar"),
    RENAME("Renombrar"),
    DUPLICATE("Duplicar"),
    DELETE("Eliminar"),
}

private fun actionsForProfile(isActive: Boolean): List<ProfileAction> =
    if (isActive) {
        listOf(ProfileAction.SYNC, ProfileAction.RENAME, ProfileAction.DUPLICATE, ProfileAction.DELETE)
    } else {
        listOf(ProfileAction.SWITCH, ProfileAction.SYNC, ProfileAction.RENAME, ProfileAction.DUPLICATE, ProfileAction.DELETE)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    controller: AppController,
    group: SaveGroup,
    actions: Flow<GamepadAction>,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = remember(group.id) { FocusListState(group.profiles.size) }
    // Which action button is selected *within* the focused row — resets whenever the focused
    // row changes so left/right always starts from the first action of the new row.
    var focusedActionIndex by remember(group.id) { mutableStateOf(0) }

    // LaunchedEffect(actions, group.id) below never restarts across a plain profile switch
    // (group.id doesn't change), so its long-lived collector would otherwise keep closing over
    // whatever `group` looked like when the screen first opened — meaning the very first
    // switch/sync/etc. would work but every subsequent one would silently act on stale data.
    // rememberUpdatedState keeps a stable reference whose `.value` is always the latest `group`.
    val currentGroup = rememberUpdatedState(group)

    LaunchedEffect(group.profiles.size) {
        focus.updateItemCount(group.profiles.size)
        focusedActionIndex = 0
    }

    var pendingDialog by remember { mutableStateOf<DetailDialog?>(null) }

    fun focusedRowActionCount(): Int {
        val profile = currentGroup.value.profiles.getOrNull(focus.focusedIndex) ?: return 0
        return actionsForProfile(profile.id == currentGroup.value.activeProfileId).size
    }

    LaunchedEffect(actions, group.id) {
        actions.collect { action ->
            when (action) {
                GamepadAction.NAV_UP -> {
                    focus.moveUp()
                    focusedActionIndex = 0
                }
                GamepadAction.NAV_DOWN -> {
                    focus.moveDown()
                    focusedActionIndex = 0
                }
                GamepadAction.PAGE_UP -> {
                    focus.pageUp()
                    focusedActionIndex = 0
                }
                GamepadAction.PAGE_DOWN -> {
                    focus.pageDown()
                    focusedActionIndex = 0
                }
                GamepadAction.NAV_LEFT -> {
                    val count = focusedRowActionCount()
                    if (count > 0) focusedActionIndex = (focusedActionIndex - 1 + count) % count
                }
                GamepadAction.NAV_RIGHT -> {
                    val count = focusedRowActionCount()
                    if (count > 0) focusedActionIndex = (focusedActionIndex + 1) % count
                }
                GamepadAction.BACK -> onBack()
                GamepadAction.CONFIRM -> {
                    val liveGroup = currentGroup.value
                    val profile = liveGroup.profiles.getOrNull(focus.focusedIndex)
                    if (profile != null) {
                        val rowActions = actionsForProfile(profile.id == liveGroup.activeProfileId)
                        when (rowActions.getOrNull(focusedActionIndex)) {
                            ProfileAction.SWITCH ->
                                if (liveGroup.activeProfileId != null) controller.switchProfile(liveGroup.id, profile.id)
                            ProfileAction.SYNC -> controller.syncActiveToProfile(liveGroup.id, profile.id)
                            ProfileAction.RENAME -> pendingDialog = DetailDialog.RenameProfile(profile)
                            ProfileAction.DUPLICATE -> pendingDialog = DetailDialog.DuplicateProfile(profile)
                            ProfileAction.DELETE -> controller.deleteProfile(liveGroup.id, profile.id)
                            null -> Unit
                        }
                    }
                }
                GamepadAction.MENU -> pendingDialog = DetailDialog.NewProfile
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { pendingDialog = DetailDialog.NewProfile }) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo perfil")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (group.activeProfileId == null) {
                FirstUseBanner(onCapture = { pendingDialog = DetailDialog.CaptureActive })
            }

            if (group.profiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Todavía no hay perfiles en este grupo.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(group.profiles) { index, profile ->
                        val rowFocused = index == focus.focusedIndex
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == group.activeProfileId,
                            focused = rowFocused,
                            focusedActionIndex = if (rowFocused) focusedActionIndex else -1,
                            switchEnabled = group.activeProfileId != null,
                            onSwitch = { scope.launch { controller.switchProfile(group.id, profile.id) } },
                            onSync = { scope.launch { controller.syncActiveToProfile(group.id, profile.id) } },
                            onRename = { pendingDialog = DetailDialog.RenameProfile(profile) },
                            onDuplicate = { pendingDialog = DetailDialog.DuplicateProfile(profile) },
                            onDelete = { scope.launch { controller.deleteProfile(group.id, profile.id) } },
                        )
                    }
                }
            }
        }
    }

    when (val dialog = pendingDialog) {
        null -> Unit
        DetailDialog.NewProfile -> TextInputDialog(
            title = "Nuevo perfil",
            label = "Nombre del perfil",
            confirmLabel = "Crear",
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                scope.launch { controller.createEmptyProfile(group.id, name) }
                pendingDialog = null
            },
        )
        DetailDialog.CaptureActive -> TextInputDialog(
            title = "Capturar perfil actual",
            label = "Nombre para este perfil",
            confirmLabel = "Capturar",
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                scope.launch { controller.createProfileFromActive(group.id, name) }
                pendingDialog = null
            },
        )
        is DetailDialog.RenameProfile -> TextInputDialog(
            title = "Renombrar perfil",
            label = "Nuevo nombre",
            initialValue = dialog.profile.name,
            confirmLabel = "Guardar",
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                scope.launch { controller.renameProfile(group.id, dialog.profile.id, name) }
                pendingDialog = null
            },
        )
        is DetailDialog.DuplicateProfile -> TextInputDialog(
            title = "Duplicar perfil",
            label = "Nombre de la copia",
            initialValue = "${dialog.profile.name} (copia)",
            confirmLabel = "Duplicar",
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                scope.launch { controller.duplicateProfile(group.id, dialog.profile.id, name) }
                pendingDialog = null
            },
        )
    }
}

private sealed interface DetailDialog {
    data object NewProfile : DetailDialog
    data object CaptureActive : DetailDialog
    data class RenameProfile(val profile: SaveProfile) : DetailDialog
    data class DuplicateProfile(val profile: SaveProfile) : DetailDialog
}

@Composable
private fun FirstUseBanner(onCapture: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Esta carpeta todavía no tiene un perfil activo.", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Capturá lo que hay ahora mismo en la carpeta como tu primer perfil antes de cambiar " +
                    "entre otros, para no perder ese progreso.",
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCapture) { Text("Capturar contenido actual") }
        }
    }
}

/**
 * @param focusedActionIndex index (within this row's own action list) of the action currently
 * selected via gamepad/keyboard NAV_LEFT/NAV_RIGHT, or -1 when this row itself isn't focused.
 */
@Composable
private fun ProfileRow(
    profile: SaveProfile,
    isActive: Boolean,
    focused: Boolean,
    focusedActionIndex: Int,
    switchEnabled: Boolean,
    onSwitch: () -> Unit,
    onSync: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val callbacks = mapOf(
        ProfileAction.SWITCH to onSwitch,
        ProfileAction.SYNC to onSync,
        ProfileAction.RENAME to onRename,
        ProfileAction.DUPLICATE to onDuplicate,
        ProfileAction.DELETE to onDelete,
    )
    val rowActions = actionsForProfile(isActive)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer
                focused -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = {}, label = { Text("Activo") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                rowActions.forEachIndexed { index, action ->
                    val isSelected = focusedActionIndex == index
                    val enabled = if (action == ProfileAction.SWITCH) switchEnabled else true
                    val onClick = callbacks.getValue(action)
                    if (isSelected) {
                        Button(onClick = onClick, enabled = enabled) { Text(action.label) }
                    } else {
                        OutlinedButton(onClick = onClick, enabled = enabled) { Text(action.label) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
