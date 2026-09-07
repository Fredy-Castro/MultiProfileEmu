package com.multiprofileemu.platform

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import javax.swing.plaf.nimbus.NimbusLookAndFeel

actual fun pickFolderDialog(title: String, initialPath: String?, onResult: (String?) -> Unit) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    // On some Linux setups (no GTK bindings available, e.g. minimal window managers) the system
    // L&F falls back to Metal — whose JFileChooser UI defaults resolve `FileChooser.font` to null
    // on Linux, making every label/button in the dialog render with invisible (zero-metric) text
    // while the layout/borders still show. Nimbus ships with every JDK (no native library needed)
    // and doesn't hit this, so it's a safe fallback specifically for that case; Windows/macOS
    // always get a real native L&F here and never reach this branch.
    if (UIManager.getLookAndFeel() is MetalLookAndFeel) {
        runCatching { UIManager.setLookAndFeel(NimbusLookAndFeel()) }
    }

    val startDir = initialPath?.let(::File)?.takeIf { it.exists() }
    val chooser = JFileChooser(startDir).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }

    val result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
    onResult(result)
}
