package com.multiprofileemu.platform

import java.io.File

/**
 * Shizuku instantiates this by reflection (public no-arg constructor required) in a separate
 * process it spawns running as `adb shell`'s UID — never constructed directly by this app's own
 * process. Plain `java.io.File` calls here therefore run with that UID, which is confirmed on real
 * hardware to bypass the one thing `MANAGE_EXTERNAL_STORAGE` doesn't cover: creating a new file
 * inside *another* app's `Android/data` folder (see [ShizukuBridge]).
 */
class PrivilegedFileOpsService : IPrivilegedFileOps.Stub() {

    override fun copyDirectory(sourcePath: String, destPath: String): Int = try {
        val source = File(sourcePath)
        val dest = File(destPath)
        val sourceSize = sizeOf(source)
        dest.mkdirs()
        copyRecursive(source, dest)
        // Verified from *inside* this privileged process rather than left to the caller: the
        // caller's own (unprivileged) view of destPath can still report stale/nonexistent right
        // after this process just wrote to it — confirmed on real hardware — so only a check made
        // from here is trustworthy.
        if (sourceSize == 0L || sizeOf(dest) > 0L) 0 else -1
    } catch (e: Exception) {
        -1
    }

    private fun copyRecursive(source: File, dest: File) {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles()?.forEach { copyRecursive(it, File(dest, it.name)) }
        } else {
            source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    private fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }
}
