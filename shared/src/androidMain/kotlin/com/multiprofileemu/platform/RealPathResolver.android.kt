package com.multiprofileemu.platform

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.multiprofileemu.fs.SAF_CHILD_SCHEME
import com.multiprofileemu.fs.splitSafPath

/**
 * Only handles the standard "external storage" document provider (`com.android.externalstorage.
 * documents`) — the one backing "Internal storage" and any inserted SD card in Android's own
 * folder picker UI, which is what the vast majority of real devices/folder choices are. Its tree
 * document ID has the well-known (if technically undocumented-by-Google) format
 * `<volumeId>:<relativePath>`, where `volumeId` is literally "primary" for internal storage or the
 * card's volume UUID for a removable SD card, both mapping to a predictable real mount point.
 * Any other provider (a cloud-backed "Files" app, a USB-OTG provider under some OEM skins, etc.)
 * returns null — there's no reliable way to turn those into a real path, and guessing wrong would
 * silently sync the wrong folder instead of just failing loudly.
 */
actual fun resolveRealPath(path: String): String? {
    if (path.startsWith(SAF_CHILD_SCHEME)) {
        val (rootUri, segments) = splitSafPath(path)
        val realRoot = resolveTreeUri(rootUri) ?: return null
        return (listOf(realRoot) + segments).joinToString("/")
    }
    if (path.startsWith("content://")) {
        return resolveTreeUri(path)
    }
    return path
}

private fun resolveTreeUri(treeUri: String): String? {
    val uri = Uri.parse(treeUri)
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        return null
    } ?: return null
    val colon = docId.indexOf(':')
    val volumeId = if (colon >= 0) docId.substring(0, colon) else docId
    val relativePath = if (colon >= 0) docId.substring(colon + 1) else ""
    val volumeRoot = if (volumeId == "primary") {
        Environment.getExternalStorageDirectory().path
    } else {
        "/storage/$volumeId"
    }
    return if (relativePath.isEmpty()) volumeRoot else "$volumeRoot/$relativePath"
}
