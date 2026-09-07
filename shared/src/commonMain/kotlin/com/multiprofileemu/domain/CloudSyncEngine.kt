package com.multiprofileemu.domain

import com.multiprofileemu.cloud.RcloneRunner
import com.multiprofileemu.model.CloudSyncMode
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RcloneLogLine(val stats: RcloneStats? = null, val level: String? = null, val msg: String? = null)

@Serializable
private data class RcloneStats(val bytes: Long = 0, val totalBytes: Long = 0, val lastError: String? = null)

/**
 * Drives the real `rclone` binary (via [RcloneRunner]) for everything cloud-related: creating a
 * Google Drive remote through rclone's own browser OAuth flow, listing/deleting remotes, and
 * running an upload/download/bisync between a local folder and a remote one. Never reimplements
 * any of rclone's own logic (auth, transfer, conflict handling) — this is a thin, typed wrapper.
 */
class CloudSyncEngine(private val runner: RcloneRunner) {

    private val json = Json { ignoreUnknownKeys = true }

    fun isRcloneAvailable(): Boolean = runner.isAvailable()

    suspend fun listRemotes(): List<String> {
        val lines = mutableListOf<String>()
        runner.run(listOf("listremotes")) { line -> lines += line }
        return lines.map { it.trim().removeSuffix(":") }.filter { it.isNotEmpty() }
    }

    suspend fun deleteRemote(name: String) {
        runner.run(listOf("config", "delete", name)) {}
    }

    /**
     * Runs rclone's own interactive Drive setup (it opens the system browser and runs a local
     * OAuth callback server itself — nothing here talks to Google directly). Returns an error
     * message, or null on success. [RcloneRunner.run]'s cancellation handler kills the rclone
     * process the moment this timeout fires, so no zombie process is left waiting for a consent
     * that will never come.
     */
    suspend fun createDriveRemote(name: String): String? {
        val output = StringBuilder()
        val exitCode = withTimeoutOrNull(FIVE_MINUTES_MS) {
            runner.run(listOf("config", "create", name, "drive")) { line -> output.appendLine(line) }
        }
        return when (exitCode) {
            null -> "Se agotó el tiempo de espera para autorizar el acceso a Google Drive. Intentá de nuevo."
            0 -> null
            else -> output.lines().lastOrNull { it.isNotBlank() } ?: "rclone terminó con un error (código $exitCode)."
        }
    }

    /**
     * @param resync only meaningful for [CloudSyncMode.BISYNC] — must be true the very first time
     * a given local/remote pair syncs (rclone bisync requires an initial `--resync` to establish
     * its baseline listings; every run after that omits it).
     */
    suspend fun sync(
        localPath: String,
        remoteName: String,
        remotePath: String,
        mode: CloudSyncMode,
        resync: Boolean,
        onProgress: (OperationProgress) -> Unit,
    ): String? {
        val remote = "$remoteName:$remotePath"
        val phase = when (mode) {
            CloudSyncMode.UPLOAD -> OperationPhase.CLOUD_UPLOAD
            CloudSyncMode.DOWNLOAD -> OperationPhase.CLOUD_DOWNLOAD
            CloudSyncMode.BISYNC -> OperationPhase.CLOUD_BISYNC
        }
        val args = buildList {
            when (mode) {
                CloudSyncMode.UPLOAD -> {
                    add("sync"); add(localPath); add(remote)
                }
                CloudSyncMode.DOWNLOAD -> {
                    add("sync"); add(remote); add(localPath)
                }
                CloudSyncMode.BISYNC -> {
                    add("bisync"); add(localPath); add(remote)
                    add("--conflict-resolve"); add("newer")
                    add("--conflict-loser"); add("num")
                    if (resync) add("--resync")
                }
            }
            add("--use-json-log")
            add("-v")
            add("--stats"); add("500ms")
        }

        var lastErrorLine: String? = null
        onProgress(OperationProgress(phase, 0L, 0L))
        val exitCode = runner.run(args) { line ->
            val parsed = try {
                json.decodeFromString<RcloneLogLine>(line)
            } catch (e: Exception) {
                null
            }
            val stats = parsed?.stats
            if (stats != null) {
                onProgress(OperationProgress(phase, stats.bytes, stats.totalBytes))
                if (!stats.lastError.isNullOrBlank()) lastErrorLine = stats.lastError
            }
            if (lastErrorLine == null && parsed?.level == "error") {
                lastErrorLine = parsed.msg ?: line
            }
        }
        return if (exitCode == 0) null else lastErrorLine ?: "rclone terminó con un error (código $exitCode)."
    }

    private companion object {
        const val FIVE_MINUTES_MS = 5 * 60 * 1000L
    }
}
