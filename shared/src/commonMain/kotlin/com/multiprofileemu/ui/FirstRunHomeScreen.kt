package com.multiprofileemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.multiprofileemu.platform.pickFolderDialog

/**
 * Shown once, the very first time the app runs on a machine — lets the user pick where
 * MultiProfileEmu keeps its config and every profile's saved files, or skip and keep the
 * platform default. Reachable again later from Settings ("Migrar a otra carpeta…").
 */
@Composable
fun FirstRunHomeScreen(defaultPath: String, onChoose: (String) -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bienvenido a MultiProfileEmu", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Elegí dónde querés guardar la configuración y los perfiles de save. " +
                            "Podés cambiarlo más adelante desde Configuración.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Carpeta por defecto: $defaultPath", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(24.dp))
                    Row {
                        Button(onClick = {
                            pickFolderDialog("Elegí la carpeta de datos de MultiProfileEmu", defaultPath)
                                ?.let(onChoose)
                        }) { Text("Elegir carpeta…") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { onChoose(defaultPath) }) { Text("Usar carpeta por defecto") }
                    }
                }
            }
        }
    }
}
