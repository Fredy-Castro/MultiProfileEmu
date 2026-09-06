package com.multiprofileemu.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.multiprofileemu.MultiProfileEmuApplication
import java.io.File

/**
 * Handles two kinds of paths: plain filesystem paths (used for the app's own config + profile
 * snapshots, always private app storage) and SAF tree URIs (`content://...`, used only for
 * `activeFolderPath` — an emulator's save folder living under scoped storage that this app
 * doesn't own). Recursive copy/traversal here follows the same pattern already proven in
 * SaveState-App's BackupManager: stream-copy (never load a whole file into memory) and create
 * SAF files with MIME `application/octet-stream` so the DocumentsProvider doesn't silently
 * append an extension to extensionless save files.
 */
class AndroidFileSystemAdapter(private val context: Context) : FileSystemAdapter {

    private fun isSafUri(path: String) = path.startsWith("content://")
    private fun safDoc(path: String): DocumentFile? = DocumentFile.fromTreeUri(context, Uri.parse(path))

    override fun exists(path: String): Boolean =
        if (isSafUri(path)) safDoc(path)?.exists() == true else File(path).exists()

    override fun ensureDirectory(path: String) {
        // SAF trees already exist by construction (the user picked them via the system picker);
        // only plain app-storage paths ever need to be created.
        if (!isSafUri(path)) File(path).mkdirs()
    }

    override fun clearDirectoryContents(path: String) {
        if (isSafUri(path)) {
            safDoc(path)?.listFiles()?.forEach { it.delete() }
        } else {
            File(path).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    override fun copyDirectoryContents(sourcePath: String, destPath: String) {
        ensureDirectory(destPath)
        val sourceIsSaf = isSafUri(sourcePath)
        val destIsSaf = isSafUri(destPath)
        when {
            sourceIsSaf && destIsSaf -> {
                val source = safDoc(sourcePath) ?: return
                val dest = safDoc(destPath) ?: return
                copyDocToDoc(source, dest)
            }
            sourceIsSaf -> {
                val source = safDoc(sourcePath) ?: return
                copyDocToFile(source, File(destPath))
            }
            destIsSaf -> {
                val source = File(sourcePath)
                if (!source.exists()) return
                val dest = safDoc(destPath) ?: return
                source.listFiles()?.forEach { copyFileToDoc(it, dest) }
            }
            else -> {
                val source = File(sourcePath)
                if (!source.exists()) return
                copyFileToFile(source, File(destPath))
            }
        }
    }

    override fun sizeOfDirectory(path: String): Long =
        if (isSafUri(path)) sizeOfDoc(safDoc(path)) else sizeOfFile(File(path))

    override fun readTextFile(path: String): String? {
        // config.json / location.json are always plain app-storage paths, never SAF.
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }

    override fun writeTextFile(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun copyFileToFile(source: File, dest: File) {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles()?.forEach { copyFileToFile(it, File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    private fun copyFileToDoc(source: File, destParent: DocumentFile) {
        if (source.isDirectory) {
            val childDoc = destParent.findFile(source.name)?.takeIf { it.isDirectory }
                ?: destParent.createDirectory(source.name) ?: return
            source.listFiles()?.forEach { copyFileToDoc(it, childDoc) }
        } else {
            destParent.findFile(source.name)?.delete()
            val childDoc = destParent.createFile("application/octet-stream", source.name) ?: return
            context.contentResolver.openOutputStream(childDoc.uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }

    private fun copyDocToFile(source: DocumentFile, destDir: File) {
        destDir.mkdirs()
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                copyDocToFile(child, File(destDir, name))
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(destDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun copyDocToDoc(source: DocumentFile, destParent: DocumentFile) {
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                val childDest = destParent.findFile(name)?.takeIf { it.isDirectory }
                    ?: destParent.createDirectory(name) ?: return@forEach
                copyDocToDoc(child, childDest)
            } else {
                destParent.findFile(name)?.delete()
                val childDest = destParent.createFile("application/octet-stream", name) ?: return@forEach
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    context.contentResolver.openOutputStream(childDest.uri)?.use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun sizeOfDoc(doc: DocumentFile?): Long {
        if (doc == null) return 0L
        if (doc.isFile) return doc.length()
        return doc.listFiles().sumOf { sizeOfDoc(it) }
    }

    private fun sizeOfFile(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { sizeOfFile(it) } ?: 0L
    }
}

actual fun createFileSystemAdapter(): FileSystemAdapter = AndroidFileSystemAdapter(MultiProfileEmuApplication.instance)
