package com.multiprofileemu.fs

import java.io.File
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Plain filesystem access — desktop has no scoped-storage restrictions, unlike Android. */
class DesktopFileSystemAdapter : FileSystemAdapter {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun ensureDirectory(path: String) {
        Files.createDirectories(Path.of(path))
    }

    override fun clearDirectoryContents(path: String) {
        val dir = File(path)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override fun copyDirectoryContents(sourcePath: String, destPath: String, onProgress: (Int, Int) -> Unit): Boolean? {
        val sourceRoot = Path.of(sourcePath)
        if (!Files.exists(sourceRoot)) {
            onProgress(0, 0)
            return true
        }
        val destRoot = Path.of(destPath)
        Files.createDirectories(destRoot)

        val total = countFiles(sourceRoot)
        var done = 0
        onProgress(0, total)

        // FOLLOW_LINKS matters here: several emulator setups (Flatpak installs on Bazzite/SteamOS
        // via EmuDeck, for instance) point the save folder itself at a symlink. Files.walk()
        // without it only ever visits the symlink as a single opaque entry and never descends
        // into its contents — copyDirectoryContents would then silently copy zero files, and the
        // outgoing profile's backup during a switch ends up empty right before the real files (at
        // the symlink target) get wiped by clearDirectoryContents (which, using java.io.File, does
        // resolve the symlink) — actual data loss. Reproduced and confirmed on real hardware.
        Files.walk(sourceRoot, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.forEach { source ->
                val relative = sourceRoot.relativize(source)
                if (relative.toString().isEmpty()) return@forEach
                val target = destRoot.resolve(relative.toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    done++
                    onProgress(done, total)
                }
            }
        }
        return true
    }

    private fun countFiles(root: Path): Int {
        if (!Files.exists(root)) return 0
        Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.count().toInt()
        }
    }

    override fun sizeOfDirectory(path: String): Long {
        val root = Path.of(path)
        if (!Files.exists(root)) return 0L
        return Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
    }

    override fun readTextFile(path: String): String? {
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }

    override fun writeTextFile(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}

actual fun createFileSystemAdapter(): FileSystemAdapter = DesktopFileSystemAdapter()
