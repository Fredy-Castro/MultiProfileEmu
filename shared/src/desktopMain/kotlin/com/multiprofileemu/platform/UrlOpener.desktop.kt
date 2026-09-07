package com.multiprofileemu.platform

/** No-op on desktop — rclone opens the system browser itself for its Drive-authorize flow. */
actual fun openUrlInBrowser(url: String) {}
