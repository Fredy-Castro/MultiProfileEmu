package com.multiprofileemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiprofileemu.app.PendingConflicts

/**
 * Shown at the app root (see [App]) whenever [com.multiprofileemu.app.AppController.pendingConflicts]
 * is non-null — a BISYNC left one or more files changed on both sides, and the user has to pick a
 * winner per file. Every conflict needs an explicit choice before "Aplicar" enables; there's no
 * "newer wins" default because letting the user decide is the whole point of this dialog.
 */
@Composable
fun ConflictResolutionDialog(
    pending: PendingConflicts,
    onDismiss: () -> Unit,
    onResolve: (Map<String, Boolean>) -> Unit,
) {
    // relativePath -> true (keep local) / false (keep cloud); absent = not decided yet.
    var choices by remember(pending) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conflictos de sincronización") },
        text = {
            Column {
                Text(
                    "Estos archivos cambiaron tanto en la carpeta local como en la nube desde la " +
                        "última sincronización. Elegí, para cada uno, cuál versión predomina.",
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    pending.conflicts.forEach { conflict ->
                        Spacer(Modifier.height(8.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(conflict.relativePath, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text("Local: ${conflict.localModified ?: "fecha desconocida"}")
                                Text("Nube: ${conflict.cloudModified ?: "fecha desconocida"}")
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    val selected = choices[conflict.relativePath]
                                    if (selected == true) {
                                        Button(onClick = { choices = choices + (conflict.relativePath to true) }) {
                                            Text("Usar local")
                                        }
                                    } else {
                                        OutlinedButton(onClick = { choices = choices + (conflict.relativePath to true) }) {
                                            Text("Usar local")
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (selected == false) {
                                        Button(onClick = { choices = choices + (conflict.relativePath to false) }) {
                                            Text("Usar nube")
                                        }
                                    } else {
                                        OutlinedButton(onClick = { choices = choices + (conflict.relativePath to false) }) {
                                            Text("Usar nube")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pending.conflicts.all { choices.containsKey(it.relativePath) },
                onClick = { onResolve(choices) },
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
