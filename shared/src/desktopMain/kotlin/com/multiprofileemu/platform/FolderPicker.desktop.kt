package com.multiprofileemu.platform

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.plaf.nimbus.NimbusLookAndFeel

actual fun pickFolderDialog(title: String, initialPath: String?, onResult: (String?) -> Unit) {
    val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
    if (isLinux) {
        // Linux's "system" L&F (GTKLookAndFeel, or a Metal fallback when no GTK bindings are
        // available) has repeatedly shown the same failure mode across different real
        // distros/desktop environments: JFileChooser's UI defaults resolve to a null font, so
        // every label/button in the dialog renders with invisible (zero-metric) text while the
        // layout/icons/borders still show fine. Confirmed both for the Metal fallback (WSLg) and
        // for GTKLookAndFeel itself (Bazzite/KDE) — there's no reliable way to detect which L&F
        // will hit this ahead of time, so Linux always gets Nimbus instead: it ships with every
        // JDK (no native library dependency) and has consistently resolved fonts correctly in
        // every environment tested so far.
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
