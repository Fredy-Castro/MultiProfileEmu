package com.multiprofileemu.platform

/** Formats a home-dir path for display in Settings — platforms whose paths are already
 * human-readable (desktop) can just return it unchanged; platforms that encode extra structure
 * into the path (Android's SAF-backed paths) get a chance to prettify it. */
expect fun displayHomeDir(path: String): String
