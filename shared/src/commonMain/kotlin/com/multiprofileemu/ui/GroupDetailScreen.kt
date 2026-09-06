package com.multiprofileemu.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiprofileemu.app.AppController
import com.multiprofileemu.input.GamepadAction
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.model.SaveProfile
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    controller: AppController,
    group: SaveGroup,
    actions: Flow<GamepadAction>,
    onBack: () -> Unit,
) {
    val focus = remember(group.id) { FocusListState(group.profiles.size) }
    LaunchedEffect(group.profiles.size) { focus.updateItemCount(group.profiles.size) }

    var pendingDialog by remember { mutableStateOf<DetailDialog?>(null) }

    LaunchedEffect(actions, group.id) {
        actions.collect { action ->
            when (action) {
                GamepadAction.NAV_UP -> focus.moveUp()
                GamepadAction.NAV_DOWN -> focus.moveDown()
                GamepadAction.PAGE_UP -> focus.pageUp()
                GamepadAction.PAGE_DOWN -> focus.pageDown()
                GamepadAction.BACK -> onBack()
                GamepadAction.CONFIRM -> {
                    val profile = group.profiles.getOrNull(focus.focusedIndex)
                    if (profile != null && group.activeProfileId != null && profile.id != group.activeProfileId) {
                        controller.switchProfile(group.id, profile.id)
                    }
                }
                GamepadAction.MENU -> pendingDialog = DetailDialog.NewProfile
                else -> Unit
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
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == group.activeProfileId,
                            focused = index == focus.focusedIndex,
                            switchEnabled = group.activeProfileId != null,
                            onSwitch = { controller.switchProfile(group.id, profile.id) },
                            onSync = { controller.syncActiveToProfile(group.id, profile.id) },
                            onRename = { pendingDialog = DetailDialog.RenameProfile(profile) },
                            onDuplicate = { pendingDialog = DetailDialog.DuplicateProfile(profile) },
                            onDelete = { controller.deleteProfile(group.id, profile.id) },
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
                controller.createEmptyProfile(group.id, name)
                pendingDialog = null
            },
        )
        DetailDialog.CaptureActive -> TextInputDialog(
            title = "Capturar perfil actual",
            label = "Nombre para este perfil",
            confirmLabel = "Capturar",
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                controller.createProfileFromActive(group.id, name)
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
                controller.renameProfile(group.id, dialog.profile.id, name)
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
                controller.duplicateProfile(group.id, dialog.profile.id, name)
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

@Composable
private fun ProfileRow(
    profile: SaveProfile,
    isActive: Boolean,
    focused: Boolean,
    switchEnabled: Boolean,
    onSwitch: () -> Unit,
    onSync: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
            Row {
                if (!isActive) {
                    Button(onClick = onSwitch, enabled = switchEnabled) { Text("Cambiar a este") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onSync) { Text("Sincronizar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRename) { Text("Renombrar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDuplicate) { Text("Duplicar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete) { Text("Eliminar") }
            }
        }
    }
}
