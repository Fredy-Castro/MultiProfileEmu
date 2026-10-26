package com.multiprofileemu.app

import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.CloudSyncEngine
import com.multiprofileemu.domain.OperationPhase
import com.multiprofileemu.domain.OperationProgress
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.domain.SyncConflict
import com.multiprofileemu.fs.FileSystemAdapter
import com.multiprofileemu.model.CloudSyncConfig
import com.multiprofileemu.model.CloudSyncMode
import com.multiprofileemu.model.ConflictResolutionStrategy
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.platform.needsCloudSyncStaging
import com.multiprofileemu.platform.openUrlInBrowser
import com.multiprofileemu.util.currentEpochMillis
import com.multiprofileemu.util.defaultAppDataDir
import com.multiprofileemu.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Top-level, platform-agnostic app state. Every mutation persists immediately.
 *
 * Every mutation below actually runs on [scope] — an app-lifetime scope owned by this class, not
 * whatever screen happened to trigger it — via `async` + `await`. If the *caller* gets cancelled
 * (e.g. a screen's `rememberCoroutineScope()` dies because the user backed out right after
 * tapping "switch"), only the `await()` is abandoned; the swap/copy that's actually running here
 * keeps going to completion instead of leaving a save half backed-up or half restored. [mutex]
 * serializes overlapping calls (a rapid double-tap, say) so they apply in order against a
 * consistent snapshot instead of racing on [_groups].
 *
 * Everything also hops to [Dispatchers.IO] for the actual file work — on Android, SAF operations
 * go through Binder IPC to the DocumentsProvider and can easily take seconds for anything beyond
 * a trivial folder, and running that on the caller's thread (the UI thread, for every button
 * click) is exactly what triggers "app isn't responding".
 */
class AppController(
    private val configRepository: AppConfigRepository,
    private val swapEngine: ProfileSwapEngine,
    private val cloudSyncEngine: CloudSyncEngine,
    private val fs: FileSystemAdapter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _groups = MutableStateFlow(configRepository.loadGroups())
    val groups: StateFlow<List<SaveGroup>> = _groups.asStateFlow()

    // Non-null while a heavy file operation (profile swap, capture, sync, migration, cloud
    // sync...) is in flight, on this or on any other operation reporting through [reportProgress].
    // The UI uses this to show a progress overlay and block touch/gamepad input, since without it
    // users had no way to tell a switch was still running versus their tap having been dropped.
    private val _busy = MutableStateFlow<OperationProgress?>(null)
    val busy: StateFlow<OperationProgress?> = _busy.asStateFlow()

    /** Lets callers outside [AppController] (e.g. home-folder migration) drive the same busy overlay. */
    fun reportProgress(progress: OperationProgress?) {
        _busy.value = progress
    }

    // Non-null right after a BISYNC leaves unresolved conflict-pairs behind (see
    // CloudSyncEngine.findConflicts) — the UI shows a popup letting the user pick a winner per
    // file. Global rather than per-screen for the same reason [_busy] is: the sync that surfaced
    // it may have been triggered from a screen the user has since navigated away from.
    private val _pendingConflicts = MutableStateFlow<PendingConflicts?>(null)
    val pendingConflicts: StateFlow<PendingConflicts?> = _pendingConflicts.asStateFlow()

    /** Discards the popup without resolving anything — the .local/.cloud files are left as-is. */
    fun dismissPendingConflicts() {
        _pendingConflicts.value = null
    }

    val cloudSyncAvailable: Boolean get() = cloudSyncEngine.isRcloneAvailable()

    private suspend fun <T> runExclusive(block: suspend () -> T): T = scope.async { mutex.withLock { block() } }.await()

    private suspend fun runMutation(block: suspend () -> Unit) = runExclusive(block)

    private suspend fun persist(newGroups: List<SaveGroup>) {
        _groups.value = newGroups
        withContext(Dispatchers.IO) { configRepository.saveGroups(newGroups) }
    }

    private suspend fun updateGroup(groupId: String, transform: (SaveGroup) -> SaveGroup) = runMutation {
        try {
            val updated = withContext(Dispatchers.IO) {
                _groups.value.map { if (it.id == groupId) transform(it) else it }
            }
            persist(updated)
        } finally {
            _busy.value = null
        }
    }

    suspend fun addGroup(name: String, activeFolderPath: String, emulatorHint: String? = null) = runMutation {
        val group = SaveGroup(
            id = newId(),
            name = name,
            activeFolderPath = activeFolderPath,
            emulatorHint = emulatorHint,
        )
        persist(_groups.value + group)
    }

    suspend fun removeGroup(groupId: String) = runMutation {
        persist(_groups.value.filterNot { it.id == groupId })
    }

    suspend fun switchProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { group ->
            swapEngine.switchProfile(group, profileId, onProgress = { progress -> _busy.value = progress })
        }

    suspend fun createProfileFromActive(groupId: String, name: String) =
        updateGroup(groupId) { group ->
            swapEngine.createProfileFromActive(group, name, onProgress = { progress -> _busy.value = progress })
        }

    suspend fun createEmptyProfile(groupId: String, name: String) =
        updateGroup(groupId) { swapEngine.createEmptyProfile(it, name) }

    suspend fun syncActiveToProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { group ->
            swapEngine.syncActiveToProfile(group, profileId, onProgress = { progress -> _busy.value = progress })
        }

    suspend fun renameProfile(groupId: String, profileId: String, newName: String) =
        updateGroup(groupId) { swapEngine.renameProfile(it, profileId, newName) }

    suspend fun deleteProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { swapEngine.deleteProfile(it, profileId) }

    suspend fun duplicateProfile(groupId: String, profileId: String, newName: String) =
        updateGroup(groupId) { group ->
            swapEngine.duplicateProfile(group, profileId, newName, onProgress = { progress -> _busy.value = progress })
        }

    // --- Cloud sync -----------------------------------------------------------------------

    suspend fun listCloudRemotes(): List<String> = runExclusive { cloudSyncEngine.listRemotes() }

    suspend fun addGoogleDriveRemote(name: String): String? = runExclusive {
        try {
            _busy.value = OperationProgress(OperationPhase.CLOUD_AUTHORIZING, 0L, 0L)
            cloudSyncEngine.createDriveRemote(name, onAuthUrl = ::openUrlInBrowser)
        } finally {
            _busy.value = null
        }
    }

    suspend fun deleteCloudRemote(name: String) = runExclusive { cloudSyncEngine.deleteRemote(name) }

    /**
     * Runs rclone sync/bisync for [config] against [localPath], then persists the config (with
     * updated last-synced/resync bookkeeping) via [persistConfig]. Shared by per-profile cloud
     * sync and the group's active-folder cloud sync below — they differ only in which path gets
     * synced and where the resulting config is stored, not in the sync/bookkeeping logic itself.
     * Returns an error message, or null on success.
     */
    private suspend fun performCloudSync(
        localPath: String,
        config: CloudSyncConfig,
        force: Boolean = false,
        persistConfig: (List<SaveGroup>, CloudSyncConfig) -> List<SaveGroup>,
    ): String? {
        val resync = config.mode == CloudSyncMode.BISYNC && !config.everResynced
        val error = cloudSyncEngine.sync(
            localPath = localPath,
            remoteName = config.remoteName,
            remotePath = config.remotePath,
            mode = config.mode,
            resync = resync,
            conflictResolution = config.conflictResolution,
            force = force,
            onProgress = { progress -> _busy.value = progress },
        )
        val finalConfig = if (error == null) {
            config.copy(
                lastSyncedEpochMs = currentEpochMillis(),
                everResynced = config.everResynced || config.mode == CloudSyncMode.BISYNC,
            )
        } else {
            config
        }
        persist(persistConfig(_groups.value, finalConfig))
        return error
    }

    /**
     * Persists [config] onto the profile and immediately runs the sync it describes — one step,
     * same pattern as "Crear"/"Capturar" elsewhere: configuring and doing are the same action.
     */
    suspend fun runCloudSync(groupId: String, profileId: String, config: CloudSyncConfig): String? =
        runExclusive { runCloudSyncLocked(groupId, profileId, config) }

    /**
     * Same idea as [runCloudSync], but for people who don't want to deal with profiles at all:
     * syncs [SaveGroup.activeFolderPath] itself against the cloud remote, straight from the emulator's
     * live save location — no profile needs to exist first.
     */
    suspend fun runActiveCloudSync(groupId: String, config: CloudSyncConfig): String? =
        runExclusive { runActiveCloudSyncLocked(groupId, config) }

    // The two functions above split a thin public entry point from the "Locked" body below so
    // that resolveConflicts() — which already holds the mutex via its own runExclusive — can call
    // the body directly to re-run the sync after resolving. Calling the public function instead
    // would try to take [mutex] a second time from inside runExclusive's own withLock and deadlock
    // (kotlinx.coroutines.sync.Mutex is not reentrant).

    private suspend fun runCloudSyncLocked(
        groupId: String,
        profileId: String,
        config: CloudSyncConfig,
        force: Boolean = false,
        skipMirrorIn: Boolean = false,
    ): String? {
        try {
            val group = _groups.value.firstOrNull { it.id == groupId }
            return if (group == null) {
                "No se encontró el grupo."
            } else {
                val originalPath = swapEngine.storagePathFor(groupId, profileId)
                val staged = needsCloudSyncStaging(originalPath)
                val syncPath = if (staged) stagingPath("$groupId-profile-$profileId") else originalPath
                if (staged) {
                    fs.ensureDirectory(syncPath)
                    if (!skipMirrorIn) mirrorDirectory(originalPath, syncPath)?.let { return "$STAGING_ERROR $it" }
                }
                val error = performCloudSync(syncPath, config, force) { groups, finalConfig ->
                    groups.map { g ->
                        if (g.id != groupId) {
                            g
                        } else {
                            g.copy(profiles = g.profiles.map { p -> if (p.id == profileId) p.copy(cloudSync = finalConfig) else p })
                        }
                    }
                }
                if (error == null) {
                    checkForConflicts(groupId, profileId, syncPath, config)
                    if (staged && _pendingConflicts.value == null) {
                        mirrorDirectory(syncPath, originalPath)?.let { return "$STAGING_ERROR $it" }
                    }
                }
                error
            }
        } finally {
            _busy.value = null
        }
    }

    private suspend fun runActiveCloudSyncLocked(
        groupId: String,
        config: CloudSyncConfig,
        force: Boolean = false,
        skipMirrorIn: Boolean = false,
    ): String? {
        try {
            val group = _groups.value.firstOrNull { it.id == groupId }
            return if (group == null) {
                "No se encontró el grupo."
            } else {
                val originalPath = group.activeFolderPath
                val staged = needsCloudSyncStaging(originalPath)
                val syncPath = if (staged) stagingPath("$groupId-active") else originalPath
                if (staged) {
                    fs.ensureDirectory(syncPath)
                    if (!skipMirrorIn) mirrorDirectory(originalPath, syncPath)?.let { return "$STAGING_ERROR $it" }
                }
                val error = performCloudSync(syncPath, config, force) { groups, finalConfig ->
                    groups.map { g -> if (g.id == groupId) g.copy(activeCloudSync = finalConfig) else g }
                }
                if (error == null) {
                    checkForConflicts(groupId, null, syncPath, config)
                    if (staged && _pendingConflicts.value == null) {
                        mirrorDirectory(syncPath, originalPath)?.let { return "$STAGING_ERROR $it" }
                    }
                }
                error
            }
        } finally {
            _busy.value = null
        }
    }

    private fun stagingPath(key: String): String = "${defaultAppDataDir()}/.cloudstage/$key"

    /**
     * Mirrors [source]'s contents into [dest] (clearing [dest] first, so it ends up an exact
     * copy) — used on Android to keep a real, app-owned staging directory in sync with a SAF
     * folder before/after rclone touches the staging side (see [needsCloudSyncStaging]'s doc comment for why
     * rclone can never touch the SAF folder directly). Wrapped in [Dispatchers.IO] since
     * [FileSystemAdapter.copyDirectoryContents] is a plain blocking call — same pattern
     * [updateGroup] already uses for the equivalent profile-swap copies.
     *
     * Returns null on success, or a detail string on failure — [FileSystemAdapter.copyDirectoryContents]
     * doesn't report per-file failures, so this can only catch the most dangerous failure shape — a
     * copy that silently produced nothing at all (e.g. a revoked SAF grant) — by comparing total
     * sizes before and after; a partial failure (some files copied, others silently skipped) isn't
     * caught. The detail string exists so that shape of failure, when it does happen, says *why*
     * instead of just "it didn't work" — see [STAGING_ERROR]'s callers.
     */
    private suspend fun mirrorDirectory(source: String, dest: String): String? = withContext(Dispatchers.IO) {
        val sourceSize = fs.sizeOfDirectory(source)
        fs.clearDirectoryContents(dest)
        fs.ensureDirectory(dest)
        val verifiedByAdapter = try {
            fs.copyDirectoryContents(source, dest) { done, total ->
                _busy.value = OperationProgress(OperationPhase.SYNCING, done.toLong(), total.toLong())
            }
        } catch (e: Exception) {
            return@withContext (e.message ?: e.toString())
        }
        // A true here is authoritative even if the check below says otherwise — see
        // FileSystemAdapter.copyDirectoryContents's doc for why that check isn't reliable for
        // every destination. A false (or an adapter that hasn't been updated to verify its own
        // copy) still falls through to it as a fallback.
        val destSize = fs.sizeOfDirectory(dest)
        when {
            verifiedByAdapter || sourceSize == 0L || destSize > 0L -> null
            else -> "origen $source: existe=${fs.exists(source)}, $sourceSize bytes; " +
                "destino $dest: existe=${fs.exists(dest)}, $destSize bytes tras copiar"
        }
    }

    /**
     * After a successful BISYNC using [ConflictResolutionStrategy.MANUAL], surfaces any conflicts
     * left in [localPath] via [pendingConflicts]. NEWEST resolves everything on its own — rclone
     * never leaves .local/.cloud pairs behind for that strategy, so there's nothing to detect.
     */
    private suspend fun checkForConflicts(groupId: String, profileId: String?, localPath: String, config: CloudSyncConfig) {
        if (config.mode != CloudSyncMode.BISYNC) return
        if (config.conflictResolution != ConflictResolutionStrategy.MANUAL) return
        val conflicts = cloudSyncEngine.findConflicts(localPath)
        if (conflicts.isNotEmpty()) {
            _pendingConflicts.value = PendingConflicts(groupId, profileId, localPath, config, conflicts)
        }
    }

    /**
     * Applies each entry of [resolutions] (keyed by [SyncConflict.relativePath], true = keep the
     * local version) via [CloudSyncEngine.resolveConflict], then re-runs the same sync that
     * surfaced the conflicts so the resolution — and the deletion of the leftover .local/.cloud
     * copies — propagates to the other side. Returns the first error encountered, or null.
     */
    suspend fun resolveConflicts(resolutions: Map<String, Boolean>): String? = runExclusive {
        val pending = _pendingConflicts.value ?: return@runExclusive null
        try {
            for (conflict in pending.conflicts) {
                val keepLocal = resolutions[conflict.relativePath] ?: continue
                val error = cloudSyncEngine.resolveConflict(pending.localPath, conflict, keepLocal)
                if (error != null) return@runExclusive error
            }
            _pendingConflicts.value = null
            // force=true: the deletions this follow-up sync propagates (the losing conflict
            // copies on the other side) are exactly what the user just chose above — not an
            // unreviewed mass deletion, so bisync's own delete-count safety abort would otherwise
            // trip on nothing more than a small save folder having few total files.
            // skipMirrorIn=true: the staging copy already has the user's resolution applied (via
            // CloudSyncEngine.resolveConflict, which operated on pending.localPath — the staging
            // path). Mirroring the SAF folder's stale, unresolved content back in would overwrite
            // that resolution right before this sync runs.
            if (pending.profileId != null) {
                runCloudSyncLocked(pending.groupId, pending.profileId, pending.config, force = true, skipMirrorIn = true)
            } else {
                runActiveCloudSyncLocked(pending.groupId, pending.config, force = true, skipMirrorIn = true)
            }
        } finally {
            _busy.value = null
        }
    }

    private companion object {
        const val STAGING_ERROR = "No se pudo preparar la sincronización: no se pudo copiar el contenido entre " +
            "la carpeta activa y el área temporal de la app. Revisá que el permiso sobre esa carpeta siga " +
            "vigente (podés tener que elegirla de nuevo desde Configuración). Detalle:"
    }
}

/**
 * @param profileId null means [localPath] is the group's active folder rather than a profile's
 * storage — mirrors the split between [AppController.runCloudSync] and [AppController.runActiveCloudSync].
 */
data class PendingConflicts(
    val groupId: String,
    val profileId: String?,
    val localPath: String,
    val config: CloudSyncConfig,
    val conflicts: List<SyncConflict>,
)
