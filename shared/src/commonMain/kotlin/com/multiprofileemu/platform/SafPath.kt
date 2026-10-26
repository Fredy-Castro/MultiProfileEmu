package com.multiprofileemu.platform

/**
 * True when [path] needs Android's SAF/`DocumentFile` APIs to read or write — a `content://` tree
 * URI, or the app's own `safchild://`-encoded child-path scheme (see
 * [com.multiprofileemu.fs.AndroidFileSystemAdapter]) — rather than being a plain filesystem path
 * usable directly by an external process. Always false on desktop.
 *
 * This matters for cloud sync specifically: rclone is a separate native process that can only do
 * raw POSIX file I/O, and Android's scoped storage does not reliably honor a SAF grant for that —
 * confirmed on real hardware two different ways (a `rename()` on the resolved real path failing
 * with "operation not permitted" finishing a download, and, even after avoiding that, an `open()`
 * on a pre-existing file created by another app failing with "permission denied"). [AppController]
 * uses this to decide whether a cloud sync needs to happen against a staging copy instead of the
 * real path directly — see its `mirrorDirectory`.
 */
expect fun isSafPath(path: String): Boolean

/**
 * True when [path] needs the same staging treatment as [isSafPath], for a different reason: a raw
 * filesystem path into *another* app's `Android/data`/`Android/obb` folder. This app's own
 * Kotlin/Java file I/O can read/write there once "All files access" (`MANAGE_EXTERNAL_STORAGE`) is
 * granted, but rclone — a separate native process making its own raw `mkdir()`/`open()` syscalls —
 * cannot: confirmed on real hardware that it gets `ENOENT` just trying to create the destination
 * directory there, even though the exact same path already exists and is readable through the
 * app's own file APIs. Always false on desktop, where no such distinction exists. Includes
 * [isSafPath] itself so [AppController] only has to check one function.
 */
expect fun needsCloudSyncStaging(path: String): Boolean
