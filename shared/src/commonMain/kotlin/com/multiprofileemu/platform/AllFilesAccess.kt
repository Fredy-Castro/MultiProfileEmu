package com.multiprofileemu.platform

/**
 * Whether the app currently holds Android's special "All files access" permission
 * (`MANAGE_EXTERNAL_STORAGE`) — the only way to touch a raw path outside the app's own sandbox or
 * a SAF grant, including another app's `Android/data` folder. Confirmed on real hardware that SAF's
 * own folder picker refuses to even navigate into `Android/data` regardless of permissions, but a
 * plain `java.io.File` path to the same folder works once this is granted — so a path typed/edited
 * directly (e.g. in config.json) rather than picked through SAF can still reach there. Always true
 * on desktop, which has no such restriction.
 */
expect fun hasAllFilesAccess(): Boolean

/** Opens the system settings screen where the user grants/revokes [hasAllFilesAccess] for this app. No-op on desktop. */
expect fun openAllFilesAccessSettings()
