package com.multiprofileemu.util

import com.multiprofileemu.MultiProfileEmuApplication
import java.util.UUID

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun newId(): String = UUID.randomUUID().toString()

actual fun defaultAppDataDir(): String {
    val app = MultiProfileEmuApplication.instance
    val dir = app.getExternalFilesDir(null) ?: app.filesDir
    return dir.path
}
