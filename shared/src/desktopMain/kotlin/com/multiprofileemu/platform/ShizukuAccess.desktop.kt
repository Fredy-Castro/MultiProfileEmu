package com.multiprofileemu.platform

/** Desktop has no scoped-storage restriction to work around, so Shizuku never applies here. */
actual fun isShizukuAvailable(): Boolean = false

actual fun hasShizukuPermission(): Boolean = false

actual fun requestShizukuPermission(onResult: (Boolean) -> Unit) {}
