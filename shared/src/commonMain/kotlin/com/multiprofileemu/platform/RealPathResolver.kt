package com.multiprofileemu.platform

/**
 * Resolves [path] to a plain filesystem path usable by an external process (rclone) — on desktop
 * this is always a no-op since every path already is one, but on Android [path] may be a SAF tree
 * URI (`content://...`) or a [com.multiprofileemu.fs] `safchild://`-encoded path, picked via the
 * folder selector because scoped storage gives no other way to reach a folder outside the app's
 * own sandbox (e.g. another app's save folder). rclone's local backend can't write to a
 * `content://` URI directly, so cloud sync against such a path needs it translated to the real
 * path underneath first. Returns null when that translation isn't possible (a document provider
 * with no real filesystem path backing it, e.g. one backed by a cloud service) — callers should
 * surface that as "this folder can't be synced directly" rather than handing the raw URI to rclone
 * and getting a cryptic failure.
 */
expect fun resolveRealPath(path: String): String?
