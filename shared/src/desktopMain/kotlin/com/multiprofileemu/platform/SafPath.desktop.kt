package com.multiprofileemu.platform

/** Desktop has no SAF concept — every path is a plain filesystem path. */
actual fun isSafPath(path: String): Boolean = false

/** Desktop has no Android/data concept either — rclone can always touch the real path directly. */
actual fun needsCloudSyncStaging(path: String): Boolean = false
