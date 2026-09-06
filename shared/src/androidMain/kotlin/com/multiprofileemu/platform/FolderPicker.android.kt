package com.multiprofileemu.platform

import android.net.Uri
import com.multiprofileemu.MultiProfileEmuApplication

actual fun pickFolderDialog(title: String, initialPath: String?, onResult: (String?) -> Unit) {
    // Android's system tree picker doesn't support a custom title or a starting URI reliably
    // across OEM implementations, so both are best-effort/ignored here.
    val initialUri = initialPath?.let { runCatching { Uri.parse(it) }.getOrNull() }
    AndroidFolderPickerBridge.pick(initialUri) { uri ->
        if (uri == null) {
            onResult(null)
            return@pick
        }
        takePersistablePermission(MultiProfileEmuApplication.instance, uri)
        onResult(uri.toString())
    }
}
