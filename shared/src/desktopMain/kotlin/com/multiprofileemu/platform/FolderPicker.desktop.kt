package com.multiprofileemu.platform

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.plaf.nimbus.NimbusLookAndFeel

actual fun pickFolderDialog(title: String, initialPath: String?, onResult: (String?) -> Unit) {
    val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
    if (isLinux) {
        // Nimbus ships with every JDK (no native GTK dependency) and has been reliable across
        // every Linux desktop tested — kept over the native GTK L&F mainly to avoid depending on
        // GTK bindings being present at all. Note this was NOT the fix for the real invisible-text
        // bug below; it's just a reasonable, safe default.
        runCatching { UIManager.setLookAndFeel(NimbusLookAndFeel()) }
    } else {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
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
