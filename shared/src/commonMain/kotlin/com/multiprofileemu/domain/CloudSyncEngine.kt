package com.multiprofileemu.domain

import com.multiprofileemu.cloud.RcloneRunner
import com.multiprofileemu.model.CloudSyncMode
import com.multiprofileemu.model.ConflictResolutionStrategy
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RcloneLogLine(val stats: RcloneStats? = null, val level: String? = null, val msg: String? = null)

@Serializable
private data class RcloneStats(val bytes: Long = 0, val totalBytes: Long = 0, val lastError: String? = null)

@Serializable
private data class LsJsonEntry(
    @SerialName("Path") val path: String,
    @SerialName("ModTime") val modTime: String? = null,
)

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
        // -q suppresses the "Config file ... not found - using defaults" NOTICE rclone prints
        // when rclone.conf doesn't exist yet (e.g. before any remote has ever been created) —
        // without it, that line gets parsed below as if it were a remote name.
        runner.run(listOf("listremotes", "-q")) { line -> lines += line }
        return lines.map { it.trim().removeSuffix(":") }.filter { it.isNotEmpty() }
    }

    suspend fun deleteRemote(name: String) {
        runner.run(listOf("config", "delete", name)) {}
    }

    /**
     * Runs rclone's own interactive Drive setup. On desktop, rclone opens the system browser and
     * runs a local OAuth callback server itself — nothing here talks to Google directly. A
     * sandboxed process (Android) can't open a browser the same way, so rclone just prints the
     * auth URL and waits instead; [onAuthUrl] is how the caller finds out about that URL so it can
     * open it itself (e.g. via an Intent). Desktop callers can ignore it. Returns an error
     * message, or null on success. [RcloneRunner.run]'s cancellation handler kills the rclone
     * process the moment this timeout fires, so no zombie process is left waiting for a consent
     * that will never come.
     */
    suspend fun createDriveRemote(name: String, onAuthUrl: (String) -> Unit = {}): String? {
        val output = StringBuilder()
        var urlReported = false
        val exitCode = withTimeoutOrNull(FIVE_MINUTES_MS) {
            runner.run(listOf("config", "create", name, "drive")) { line ->
                output.appendLine(line)
                if (!urlReported) {
                    val match = AUTH_URL_REGEX.find(line)
                    if (match != null) {
                        urlReported = true
                        onAuthUrl(match.value)
                    }
                }
            }
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
     * @param force only meaningful for [CloudSyncMode.BISYNC]. Bypasses bisync's own built-in
     * safety aborts ("too many deletes", "all files changed"), which trip easily in a save folder
     * with only a handful of files — resolving even one conflict deletes two conflict-suffixed
     * copies out of a small total. Only ever pass true for the follow-up sync right after
     * [resolveConflict] calls the user already explicitly made in the conflict-resolution dialog —
     * never for a regular/first-time sync, where these guards are exactly what should stop an
     * unintended mass deletion (e.g. an unmounted remote looking like everything got deleted).
     */
    suspend fun sync(
        localPath: String,
        remoteName: String,
        remotePath: String,
        mode: CloudSyncMode,
        resync: Boolean,
        conflictResolution: ConflictResolutionStrategy = ConflictResolutionStrategy.MANUAL,
        force: Boolean = false,
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
                    when (conflictResolution) {
                        ConflictResolutionStrategy.MANUAL -> {
                            // "none" means bisync doesn't pick a winner on its own: both
                            // conflicting versions get renamed (with --conflict-suffix) and
                            // copied to BOTH sides, which is what lets findConflicts()/
                            // resolveConflict() below detect and resolve conflicts by inspecting
                            // localPath alone, without ever needing to list the remote side.
                            add("--conflict-resolve"); add("none")
                            add("--conflict-suffix"); add("$LOCAL_CONFLICT_SUFFIX,$CLOUD_CONFLICT_SUFFIX")
                        }
                        ConflictResolutionStrategy.NEWEST -> {
                            add("--conflict-resolve"); add("newer")
                            add("--conflict-loser"); add("num")
                        }
                    }
                    if (resync) add("--resync")
                    if (force) add("--force")
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

    /**
     * Scans [localPath] for `.$LOCAL_CONFLICT_SUFFIX<N>`/`.$CLOUD_CONFLICT_SUFFIX<N>` pairs left
     * behind by a BISYNC run (see the comment on `--conflict-resolve` in [sync]). Confirmed against
     * the real binary that rclone always appends a number after the custom suffix we pass (e.g.
     * `save.dat.local1`/`save.dat.cloud1`, matching its own default "conflict1"/"conflict2"
     * numbering) — so this matches by regex rather than assuming an exact `.local`/`.cloud` suffix.
     * Only ever meaningful after a successful BISYNC — UPLOAD/DOWNLOAD never produce these.
     */
    suspend fun findConflicts(localPath: String): List<SyncConflict> {
        val output = StringBuilder()
        runner.run(listOf("lsjson", "-R", "--files-only", localPath)) { line -> output.append(line) }
        val entries = try {
            json.decodeFromString<List<LsJsonEntry>>(output.toString())
        } catch (e: Exception) {
            return emptyList()
        }
        return entries.mapNotNull { entry ->
            if (!LOCAL_SUFFIX_REGEX.containsMatchIn(entry.path)) return@mapNotNull null
            val relativePath = LOCAL_SUFFIX_REGEX.replace(entry.path, "")
            val cloudEntry = entries.firstOrNull {
                CLOUD_SUFFIX_REGEX.containsMatchIn(it.path) && CLOUD_SUFFIX_REGEX.replace(it.path, "") == relativePath
            } ?: return@mapNotNull null
            SyncConflict(
                relativePath = relativePath,
                localConflictPath = entry.path,
                cloudConflictPath = cloudEntry.path,
                localModified = entry.modTime?.let(::formatModTime),
                cloudModified = cloudEntry.modTime?.let(::formatModTime),
            )
        }
    }

    /**
     * Renames the winning side's copy back to [SyncConflict.relativePath] and deletes the losing
     * one — both plain local operations against [localPath]. Nothing is pushed to the remote here;
     * the caller is expected to run [sync] again afterwards so the resolution (and the deletion of
     * the leftover conflict copies) propagates the same way any other change would.
     */
    suspend fun resolveConflict(localPath: String, conflict: SyncConflict, keepLocal: Boolean): String? {
        val winnerConflictPath = if (keepLocal) conflict.localConflictPath else conflict.cloudConflictPath
        val loserConflictPath = if (keepLocal) conflict.cloudConflictPath else conflict.localConflictPath
        val winnerPath = "$localPath/$winnerConflictPath"
        val canonicalPath = "$localPath/${conflict.relativePath}"
        val loserPath = "$localPath/$loserConflictPath"

        val moveExit = runner.run(listOf("moveto", winnerPath, canonicalPath)) {}
        if (moveExit != 0) {
            return "No se pudo aplicar la resolución para ${conflict.relativePath}."
        }
        runner.run(listOf("deletefile", loserPath)) {}
        return null
    }

    /** RFC3339 ("2026-09-08T14:32:10.48-05:00") trimmed to a readable "2026-09-08 14:32:10". */
    private fun formatModTime(modTime: String): String =
        modTime.take(19).replace('T', ' ')

    private companion object {
        const val FIVE_MINUTES_MS = 5 * 60 * 1000L
        const val LOCAL_CONFLICT_SUFFIX = "local"
        const val CLOUD_CONFLICT_SUFFIX = "cloud"
        // rclone appends a number after whatever --conflict-suffix we pass (confirmed on the real
        // binary: "save.dat" -> "save.dat.local1"/"save.dat.cloud1"), so matching needs a regex
        // rather than a fixed ".local"/".cloud" suffix.
        val LOCAL_SUFFIX_REGEX = Regex("""\.$LOCAL_CONFLICT_SUFFIX\d+$""")
        val CLOUD_SUFFIX_REGEX = Regex("""\.$CLOUD_CONFLICT_SUFFIX\d+$""")
        // Must require the /auth path specifically — rclone also prints an earlier, informational
        // notice ("Make sure your Redirect URL is set to \"http://127.0.0.1:53682/\" in your
        // custom config.") whose bare "127.0.0.1:53682/" would otherwise match first and win,
        // pointing the browser at a dead link instead of the real authorization URL.
        val AUTH_URL_REGEX = Regex("""https?://127\.0\.0\.1:\d+/auth\S*""")
    }
}
