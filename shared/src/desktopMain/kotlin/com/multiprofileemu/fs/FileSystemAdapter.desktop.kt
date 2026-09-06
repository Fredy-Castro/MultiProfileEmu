package com.multiprofileemu.fs

import java.io.File
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

    override fun copyDirectoryContents(sourcePath: String, destPath: String) {
        val sourceRoot = Path.of(sourcePath)
        if (!Files.exists(sourceRoot)) return
        val destRoot = Path.of(destPath)
        Files.createDirectories(destRoot)

        Files.walk(sourceRoot).use { stream ->
            stream.forEach { source ->
                val relative = sourceRoot.relativize(source)
                if (relative.toString().isEmpty()) return@forEach
                val target = destRoot.resolve(relative.toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    override fun sizeOfDirectory(path: String): Long {
        val root = Path.of(path)
        if (!Files.exists(root)) return 0L
        return Files.walk(root).use { stream ->
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
