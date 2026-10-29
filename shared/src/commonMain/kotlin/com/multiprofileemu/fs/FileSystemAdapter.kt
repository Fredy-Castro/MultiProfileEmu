package com.multiprofileemu.fs

/**
 * Every file operation the domain layer needs, kept deliberately small so each
 * platform (plain filesystem on desktop, SAF/DocumentFile on Android) can implement
 * it its own way without leaking platform details into [com.multiprofileemu.domain.ProfileSwapEngine].
 */
interface FileSystemAdapter {
    fun exists(path: String): Boolean
    fun ensureDirectory(path: String)

    /** Deletes every entry inside [path], keeping the directory itself. */
    fun clearDirectoryContents(path: String)

    /**
     * Recursively copies every entry inside [sourcePath] into [destPath] (both must be directories).
     * [onProgress] is called once before copying starts (with `filesDone = 0`, so the caller learns
     * the total immediately) and once after each file is copied.
     *
     * Returns a definite `true`/`false` when the implementation itself could verify whether the
     * copy actually produced content, or `null` when it couldn't (the caller then has to fall back
     * to its own post-copy check of [sizeOfDirectory], comparing it against [destPath]'s size
     * *before* this call).
     *
     * Callers must trust a non-null result over their own fallback check — and *not* just treat
     * `false` the same as `null`. Concretely, on Android, a copy that ran through a privileged
     * helper process (see AndroidFileSystemAdapter/ShizukuBridge) returns a trustworthy `true`/
     * `false` from that helper's own same-process check; but the fallback size comparison a caller
     * would otherwise do is close to useless for it specifically — confirmed on real hardware that
     * [destPath] can already be non-empty *before* the copy even starts (e.g. switching profiles
     * always leaves the outgoing profile's old files sitting in the destination until the new
     * ones overwrite them), so "destination is non-empty afterwards" stays true whether the write
     * actually happened or silently failed and left the stale files untouched.
     */
    fun copyDirectoryContents(sourcePath: String, destPath: String, onProgress: (filesDone: Int, filesTotal: Int) -> Unit = { _, _ -> }): Boolean?

    fun sizeOfDirectory(path: String): Long

    fun readTextFile(path: String): String?
    fun writeTextFile(path: String, content: String)
}

expect fun createFileSystemAdapter(): FileSystemAdapter
