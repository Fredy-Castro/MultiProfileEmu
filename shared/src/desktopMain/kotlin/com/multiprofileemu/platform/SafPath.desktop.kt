package com.multiprofileemu.platform

/** Desktop has no SAF concept — every path is a plain filesystem path. */
actual fun isSafPath(path: String): Boolean = false
