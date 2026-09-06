package com.multiprofileemu.platform

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Bridges the commonMain `pickFolderDialog` expect/actual (a free function) to Android's
 * SAF folder picker, which can only be launched through an ActivityResultLauncher registered
 * on the hosting Activity *before* it reaches STARTED — so the Activity must call [register]
 * from its own `onCreate`.
 */
object AndroidFolderPickerBridge {
    private var launcher: ActivityResultLauncher<Uri?>? = null
    private var pendingCallback: ((Uri?) -> Unit)? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val callback = pendingCallback
            pendingCallback = null
            callback?.invoke(uri)
        }
    }

    fun pick(initialUri: Uri?, onResult: (Uri?) -> Unit) {
        val activeLauncher = launcher
        if (activeLauncher == null) {
            onResult(null)
            return
        }
        pendingCallback = onResult
        activeLauncher.launch(initialUri)
    }
}

internal fun takePersistablePermission(context: android.content.Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}
