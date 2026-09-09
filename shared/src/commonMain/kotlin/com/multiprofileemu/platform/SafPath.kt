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
