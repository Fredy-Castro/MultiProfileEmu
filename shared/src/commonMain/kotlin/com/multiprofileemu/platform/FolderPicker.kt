package com.multiprofileemu.platform

/**
 * Opens a native "choose a folder" picker; [onResult] receives the chosen path, or null if
 * cancelled. Callback-shaped (rather than a plain return value) because Android's picker is
 * inherently asynchronous (goes through an ActivityResultLauncher) — desktop's implementation
 * stays synchronous under the hood and just calls [onResult] before returning.
 */
expect fun pickFolderDialog(title: String, initialPath: String? = null, onResult: (String?) -> Unit)
