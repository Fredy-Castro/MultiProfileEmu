package com.multiprofileemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.multiprofileemu.domain.OperationPhase
import com.multiprofileemu.domain.OperationProgress
import com.multiprofileemu.domain.isIndeterminate
import kotlin.math.ln
import kotlin.math.pow

private fun phaseLabel(phase: OperationPhase): String = when (phase) {
    OperationPhase.CAPTURING -> "Capturando el save activo..."
    OperationPhase.SYNCING -> "Respaldando el save activo..."
    OperationPhase.DUPLICATING -> "Duplicando perfil..."
    OperationPhase.BACKING_UP_OUTGOING -> "Guardando el perfil anterior..."
    OperationPhase.RESTORING_INCOMING -> "Restaurando el perfil elegido..."
    OperationPhase.MIGRATING -> "Moviendo la carpeta de datos..."
    OperationPhase.CLOUD_AUTHORIZING -> "Se abrió tu navegador para autorizar el acceso a Google Drive..."
    OperationPhase.CLOUD_UPLOAD -> "Subiendo a la nube..."
    OperationPhase.CLOUD_DOWNLOAD -> "Bajando de la nube..."
    OperationPhase.CLOUD_BISYNC -> "Sincronizando con la nube (dos vías)..."
}

private fun isByteBased(phase: OperationPhase): Boolean = when (phase) {
    OperationPhase.CLOUD_UPLOAD, OperationPhase.CLOUD_DOWNLOAD, OperationPhase.CLOUD_BISYNC -> true
    else -> false
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
    val value = bytes / 1024.0.pow(exp)
    val unit = "KMGT"[exp - 1]
    val rounded = (value * 10).toInt() / 10.0
    return "$rounded ${unit}iB"
}

/**
 * Full-screen, input-absorbing overlay shown while a heavy file operation is running. The
 * `pointerInput(Unit) { detectTapGestures {} }` on the root Box swallows every touch that reaches
 * it instead of letting it fall through to whatever is underneath; the caller is additionally
 * responsible for filtering the gamepad/keyboard action stream while [progress] is non-null, since
 * that input never passes through this composable's pointer input at all.
 */
@Composable
fun BusyOverlay(progress: OperationProgress) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.width(320.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(phaseLabel(progress.phase), style = MaterialTheme.typography.titleMedium)
                    Column(modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
                        if (progress.phase.isIndeterminate) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (!progress.phase.isIndeterminate) {
                        val detail = if (isByteBased(progress.phase)) {
                            "${formatBytes(progress.done)} / ${formatBytes(progress.total)}"
                        } else {
                            "${progress.done}/${progress.total} archivos"
                        }
                        Text(
                            "${(progress.fraction * 100).toInt()}% · $detail",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
