package com.multiprofileemu.platform

/** Opens a native "choose a folder" dialog. Returns null if the user cancelled. */
expect fun pickFolderDialog(title: String, initialPath: String? = null): String?
