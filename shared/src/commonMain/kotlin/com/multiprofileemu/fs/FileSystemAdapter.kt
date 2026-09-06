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

    /** Recursively copies every entry inside [sourcePath] into [destPath] (both must be directories). */
    fun copyDirectoryContents(sourcePath: String, destPath: String)

    fun sizeOfDirectory(path: String): Long

    fun readTextFile(path: String): String?
    fun writeTextFile(path: String, content: String)
}

expect fun createFileSystemAdapter(): FileSystemAdapter
