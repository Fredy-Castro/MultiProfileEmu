package com.multiprofileemu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.multiprofileemu.app.AppController
import com.multiprofileemu.input.GamepadAction
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.platform.pickFolderDialog
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(
    controller: AppController,
    actions: Flow<GamepadAction>,
    homeDir: String,
    onMigrateHome: (String) -> String?,
    supportsHomeMigration: Boolean = true,
    onOpenGroup: (String) -> Unit,
) {
    val groups by controller.groups.collectAsState()
    val focus = remember { FocusListState(groups.size) }
    LaunchedEffect(groups.size) { focus.updateItemCount(groups.size) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // LaunchedEffect(actions) below never restarts (its key never changes), so its collector
    // must read `groups` through a stable rememberUpdatedState — otherwise it keeps closing
    // over whatever the list looked like when this screen first composed.
    val currentGroups = rememberUpdatedState(groups)

    LaunchedEffect(actions) {
        actions.collect { action ->
            when (action) {
                GamepadAction.NAV_UP -> focus.moveUp()
                GamepadAction.NAV_DOWN -> focus.moveDown()
                GamepadAction.PAGE_UP -> focus.pageUp()
                GamepadAction.PAGE_DOWN -> focus.pageDown()
                GamepadAction.CONFIRM -> currentGroups.value.getOrNull(focus.focusedIndex)?.let { onOpenGroup(it.id) }
                GamepadAction.MENU -> showAddDialog = true
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MultiProfileEmu") },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuración")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Agregar carpeta de saves")
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Todavía no agregaste ninguna carpeta de saves.\nUsá el botón + para empezar.",
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(groups) { index, group ->
                    GroupRow(
                        group = group,
                        focused = index == focus.focusedIndex,
                        onClick = { onOpenGroup(group.id) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddGroupDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, path ->
                controller.addGroup(name, path)
                showAddDialog = false
            },
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            homeDir = homeDir,
            onMigrate = onMigrateHome,
            supportsMigration = supportsHomeMigration,
            onDismiss = { showSettingsDialog = false },
        )
    }
}

@Composable
private fun GroupRow(group: SaveGroup, focused: Boolean, onClick: () -> Unit) {
    val activeProfileName = group.profiles.firstOrNull { it.id == group.activeProfileId }?.name
        ?: "Sin perfil activo"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (focused) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Perfil activo: $activeProfileName", style = MaterialTheme.typography.bodyMedium)
            Text(group.activeFolderPath, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AddGroupDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva carpeta de saves") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre (ej: Zelda BOTW)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Carpeta de save activa") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            pickFolderDialog("Elegí la carpeta de save") { chosen -> chosen?.let { path = it } }
                        }) {
                            Text("Elegir…")
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && path.isNotBlank(),
                onClick = { onConfirm(name.trim(), path.trim()) },
            ) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SettingsDialog(
    homeDir: String,
    onMigrate: (String) -> String?,
    supportsMigration: Boolean,
    onDismiss: () -> Unit,
) {
    var migrationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuración") },
        text = {
            Column {
                Text("Carpeta de datos actual", style = MaterialTheme.typography.labelLarge)
                Text(homeDir, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                if (supportsMigration) {
                    Text(
                        "Podés mover toda la configuración y los perfiles guardados a otra carpeta " +
                            "(por ejemplo, una unidad externa).",
                    )
                } else {
                    Text("Esta carpeta es privada de la app y no se puede migrar en esta plataforma todavía.")
                }
                migrationError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (supportsMigration) {
                TextButton(onClick = {
                    pickFolderDialog("Elegí la nueva carpeta de datos", homeDir) { chosen ->
                        if (chosen != null && chosen != homeDir) migrationError = onMigrate(chosen)
                    }
                }) { Text("Migrar a otra carpeta…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
