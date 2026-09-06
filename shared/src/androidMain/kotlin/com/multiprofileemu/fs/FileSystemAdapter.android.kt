package com.multiprofileemu.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.multiprofileemu.MultiProfileEmuApplication
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Prefix marking a "SAF child path" — a tree URI plus relative segments appended after it, e.g.
 * by [com.multiprofileemu.domain.ProfileStorageResolver] doing plain "$root/child" string
 * concatenation. Naively appending segments straight onto a `content://` URI does not produce a
 * resolvable path (SAF trees have to be walked via [DocumentFile.findFile]/[DocumentFile.createDirectory],
 * not addressed by string manipulation), so this encoding keeps the root URI and the relative
 * segments separate until [AndroidFileSystemAdapter] walks them. Everything above this adapter
 * (ProfileStorageResolver, AppConfigRepository, AppHomeManager) never needs to know this scheme
 * exists — to them it's just another opaque path string they can concatenate onto.
 */
private const val SAF_CHILD_SCHEME = "safchild://"

/** Wraps a freshly-picked SAF tree URI so it can be used as a home dir (i.e. have paths appended to it). */
fun encodeSafRoot(treeUri: String): String = "$SAF_CHILD_SCHEME${URLEncoder.encode(treeUri, "UTF-8")}!"

/** Best-effort human-readable label for a [SAF_CHILD_SCHEME]-encoded path, or null if it isn't one. */
fun prettySafRootLabel(path: String): String? {
    if (!path.startsWith(SAF_CHILD_SCHEME)) return null
    val rest = path.removePrefix(SAF_CHILD_SCHEME)
    val encodedRoot = rest.substringBefore('!')
    val treeUri = URLDecoder.decode(encodedRoot, "UTF-8")
    val docId = Uri.parse(treeUri).lastPathSegment?.let { Uri.decode(it) } ?: return treeUri
    return "Almacenamiento del dispositivo/${docId.substringAfter(':', docId)}"
}

/**
 * Handles three kinds of paths: plain filesystem paths (used for the app's own config + profile
 * snapshots when the data home is app-private storage), bare SAF tree URIs (`content://...`,
 * always `activeFolderPath` — an emulator's save folder under scoped storage, never has segments
 * appended to it), and [SAF_CHILD_SCHEME]-encoded paths (used when the data home itself has been
 * migrated to a SAF folder, so config.json/profile snapshots live under it too).
 *
 * Recursive copy/traversal follows the same pattern already proven in SaveState-App's
 * BackupManager: stream-copy (never load a whole file into memory) and create SAF files with MIME
 * `application/octet-stream` so the DocumentsProvider doesn't silently append an extension to
 * extensionless save files.
 */
class AndroidFileSystemAdapter(private val context: Context) : FileSystemAdapter {

    private fun isSafPath(path: String) = path.startsWith("content://") || path.startsWith(SAF_CHILD_SCHEME)

    /** Splits a SAF path into its root tree URI and the relative segments appended after it. */
    private fun splitSafPath(path: String): Pair<String, List<String>> {
        if (!path.startsWith(SAF_CHILD_SCHEME)) return path to emptyList()
        val rest = path.removePrefix(SAF_CHILD_SCHEME)
        val bang = rest.indexOf('!')
        val encodedRoot = if (bang >= 0) rest.substring(0, bang) else rest
        val relative = if (bang >= 0) rest.substring(bang + 1) else ""
        val root = URLDecoder.decode(encodedRoot, "UTF-8")
        val segments = relative.split("/").filter { it.isNotEmpty() }
        return root to segments
    }

    /** Walks from the tree root through every segment, optionally creating missing directories along the way. */
    private fun resolveSafDoc(path: String, createMissingDirs: Boolean): DocumentFile? {
        val (rootUri, segments) = splitSafPath(path)
        var current: DocumentFile = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return null
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing != null -> existing
                createMissingDirs -> current.createDirectory(segment) ?: return null
                else -> return null
            }
        }
        return current
    }

    /** Like [resolveSafDoc] but stops one segment short, returning (parent dir, leaf name) — for file reads/writes. */
    private fun resolveSafParent(path: String, createMissingDirs: Boolean): Pair<DocumentFile, String>? {
        val (rootUri, segments) = splitSafPath(path)
        if (segments.isEmpty()) return null
        var current: DocumentFile = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return null
        for (segment in segments.dropLast(1)) {
            val existing = current.findFile(segment)
            current = when {
                existing != null -> existing
                createMissingDirs -> current.createDirectory(segment) ?: return null
                else -> return null
            }
        }
        return current to segments.last()
    }

    override fun exists(path: String): Boolean =
        if (isSafPath(path)) resolveSafDoc(path, createMissingDirs = false)?.exists() == true else File(path).exists()

    override fun ensureDirectory(path: String) {
        if (isSafPath(path)) {
            resolveSafDoc(path, createMissingDirs = true)
        } else {
            File(path).mkdirs()
        }
    }

    override fun clearDirectoryContents(path: String) {
        if (isSafPath(path)) {
            resolveSafDoc(path, createMissingDirs = false)?.listFiles()?.forEach { it.delete() }
        } else {
            File(path).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    override fun copyDirectoryContents(sourcePath: String, destPath: String, onProgress: (Int, Int) -> Unit) {
        ensureDirectory(destPath)
        val sourceIsSaf = isSafPath(sourcePath)
        val destIsSaf = isSafPath(destPath)
        when {
            sourceIsSaf && destIsSaf -> {
                val source = resolveSafDoc(sourcePath, createMissingDirs = false) ?: return onProgress(0, 0)
                val dest = resolveSafDoc(destPath, createMissingDirs = true) ?: return onProgress(0, 0)
                val total = countFilesInDoc(source)
                var done = 0
                onProgress(0, total)
                copyDocToDoc(source, dest) { done++; onProgress(done, total) }
            }
            sourceIsSaf -> {
                val source = resolveSafDoc(sourcePath, createMissingDirs = false) ?: return onProgress(0, 0)
                val total = countFilesInDoc(source)
                var done = 0
                onProgress(0, total)
                copyDocToFile(source, File(destPath)) { done++; onProgress(done, total) }
            }
            destIsSaf -> {
                val source = File(sourcePath)
                if (!source.exists()) return onProgress(0, 0)
                val dest = resolveSafDoc(destPath, createMissingDirs = true) ?: return onProgress(0, 0)
                val total = source.listFiles()?.sumOf { countFilesInFile(it) } ?: 0
                var done = 0
                onProgress(0, total)
                source.listFiles()?.forEach { copyFileToDoc(it, dest) { done++; onProgress(done, total) } }
            }
            else -> {
                val source = File(sourcePath)
                if (!source.exists()) return onProgress(0, 0)
                val total = countFilesInFile(source)
                var done = 0
                onProgress(0, total)
                copyFileToFile(source, File(destPath)) { done++; onProgress(done, total) }
            }
        }
    }

    private fun countFilesInFile(file: File): Int =
        if (file.isDirectory) file.listFiles()?.sumOf { countFilesInFile(it) } ?: 0 else 1

    private fun countFilesInDoc(doc: DocumentFile): Int =
        if (doc.isDirectory) doc.listFiles().sumOf { countFilesInDoc(it) } else 1

    override fun sizeOfDirectory(path: String): Long =
        if (isSafPath(path)) sizeOfDoc(resolveSafDoc(path, createMissingDirs = false)) else sizeOfFile(File(path))

    override fun readTextFile(path: String): String? {
        if (isSafPath(path)) {
            val (parent, leafName) = resolveSafParent(path, createMissingDirs = false) ?: return null
            val doc = parent.findFile(leafName) ?: return null
            return context.contentResolver.openInputStream(doc.uri)?.use { it.bufferedReader().readText() }
        }
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }

    override fun writeTextFile(path: String, content: String) {
        if (isSafPath(path)) {
            val (parent, leafName) = resolveSafParent(path, createMissingDirs = true) ?: return
            parent.findFile(leafName)?.delete()
            val doc = parent.createFile("application/octet-stream", leafName) ?: return
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(content.toByteArray()) }
            return
        }
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun copyFileToFile(source: File, dest: File, onFileCopied: () -> Unit) {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles()?.forEach { copyFileToFile(it, File(dest, it.name), onFileCopied) }
        } else {
            dest.parentFile?.mkdirs()
            source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            onFileCopied()
        }
    }

    private fun copyFileToDoc(source: File, destParent: DocumentFile, onFileCopied: () -> Unit) {
        if (source.isDirectory) {
            val childDoc = destParent.findFile(source.name)?.takeIf { it.isDirectory }
                ?: destParent.createDirectory(source.name) ?: return
            source.listFiles()?.forEach { copyFileToDoc(it, childDoc, onFileCopied) }
        } else {
            destParent.findFile(source.name)?.delete()
            val childDoc = destParent.createFile("application/octet-stream", source.name) ?: return
            context.contentResolver.openOutputStream(childDoc.uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            onFileCopied()
        }
    }

    private fun copyDocToFile(source: DocumentFile, destDir: File, onFileCopied: () -> Unit) {
        destDir.mkdirs()
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                copyDocToFile(child, File(destDir, name), onFileCopied)
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(destDir, name).outputStream().use { output -> input.copyTo(output) }
                }
                onFileCopied()
            }
        }
    }

    private fun copyDocToDoc(source: DocumentFile, destParent: DocumentFile, onFileCopied: () -> Unit) {
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                val childDest = destParent.findFile(name)?.takeIf { it.isDirectory }
                    ?: destParent.createDirectory(name) ?: return@forEach
                copyDocToDoc(child, childDest, onFileCopied)
            } else {
                destParent.findFile(name)?.delete()
                val childDest = destParent.createFile("application/octet-stream", name) ?: return@forEach
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    context.contentResolver.openOutputStream(childDest.uri)?.use { output -> input.copyTo(output) }
                }
                onFileCopied()
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
