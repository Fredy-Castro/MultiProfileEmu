package com.multiprofileemu.platform

/** Desktop paths are always real filesystem paths already — nothing to translate. */
actual fun resolveRealPath(path: String): String? = path
