package com.multiprofileemu.platform

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

actual fun pickFolderDialog(title: String, initialPath: String?, onResult: (String?) -> Unit) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

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
