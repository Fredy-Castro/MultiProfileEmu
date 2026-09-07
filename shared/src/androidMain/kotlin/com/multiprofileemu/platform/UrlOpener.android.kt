package com.multiprofileemu.platform

import android.content.Intent
import android.net.Uri
import com.multiprofileemu.MultiProfileEmuApplication

/**
 * A sandboxed rclone subprocess has no way to launch a browser itself (no `xdg-open` equivalent),
 * so on Android the app opens rclone's printed OAuth URL on its behalf.
 */
actual fun openUrlInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    MultiProfileEmuApplication.instance.startActivity(intent)
}
