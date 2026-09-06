package com.multiprofileemu.util

import java.util.UUID

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun newId(): String = UUID.randomUUID().toString()

actual fun defaultAppDataDir(): String {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return if (os.contains("win")) {
        val appData = System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
        "$appData\\MultiProfileEmu"
    } else {
        val base = System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config")
        "$base/MultiProfileEmu"
    }
}
