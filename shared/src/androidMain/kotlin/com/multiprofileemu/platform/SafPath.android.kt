package com.multiprofileemu.platform

import com.multiprofileemu.MultiProfileEmuApplication
import com.multiprofileemu.fs.SAF_CHILD_SCHEME

actual fun isSafPath(path: String): Boolean =
    path.startsWith("content://") || path.startsWith(SAF_CHILD_SCHEME)

actual fun needsCloudSyncStaging(path: String): Boolean = isSafPath(path) || isForeignAppStoragePath(path)

/**
 * True when [path] sits inside *another* app's `Android/data`/`Android/obb` folder — not ours,
 * where no scoped-storage restriction ever applies. Shared with
 * [com.multiprofileemu.fs.AndroidFileSystemAdapter], which uses it to decide when a write needs to
 * go through [ShizukuBridge] instead of plain `java.io.File` (see that object's doc for why).
 */
internal fun isForeignAppStoragePath(path: String): Boolean {
    val otherAppPackage = FOREIGN_APP_STORAGE_REGEX.find(path)?.groupValues?.get(1) ?: return false
    return otherAppPackage != MultiProfileEmuApplication.instance.packageName
}

private val FOREIGN_APP_STORAGE_REGEX = Regex("""/Android/(?:data|obb)/([^/]+)/""")
