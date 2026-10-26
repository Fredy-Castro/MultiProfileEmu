package com.multiprofileemu.platform

/**
 * Whether Shizuku is reachable right now. Shizuku is a separate app the user starts themselves
 * (via Wireless debugging or USB pairing) that keeps a privileged "shell"-UID broker running —
 * confirmed on real hardware that `adb shell`'s UID can create a file inside *another* app's
 * `Android/data` folder even though this app's own process, even with `MANAGE_EXTERNAL_STORAGE`,
 * cannot (see [needsCloudSyncStaging]). It doesn't survive a reboot on its own on most devices, so
 * this can flip from true to false between calls. Always false on desktop.
 */
expect fun isShizukuAvailable(): Boolean

/** Whether this app currently holds Shizuku's runtime permission to run privileged shell commands. */
expect fun hasShizukuPermission(): Boolean

/**
 * Shows Shizuku's own permission prompt; [onResult] fires with whether it ended up granted. A
 * no-op that never calls back if Shizuku isn't running ([isShizukuAvailable] false) — callers
 * should check that first if they want to tell the two cases apart. Always a no-op on desktop.
 */
expect fun requestShizukuPermission(onResult: (Boolean) -> Unit)
