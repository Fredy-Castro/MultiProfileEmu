package com.multiprofileemu.app

import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.CloudSyncEngine
import com.multiprofileemu.domain.OperationPhase
import com.multiprofileemu.domain.OperationProgress
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.model.CloudSyncConfig
import com.multiprofileemu.model.CloudSyncMode
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.platform.openUrlInBrowser
import com.multiprofileemu.util.currentEpochMillis
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
     * Persists [config] onto the profile and immediately runs the sync it describes — one step,
     * same pattern as "Crear"/"Capturar" elsewhere: configuring and doing are the same action.
     * Returns an error message, or null on success.
     */
    suspend fun runCloudSync(groupId: String, profileId: String, config: CloudSyncConfig): String? = runExclusive {
        try {
            val groups = _groups.value
            val group = groups.firstOrNull { it.id == groupId }
            if (group == null) {
                "No se encontró el grupo."
            } else {
                val localPath = swapEngine.storagePathFor(groupId, profileId)
                val resync = config.mode == CloudSyncMode.BISYNC && !config.everResynced
                val error = cloudSyncEngine.sync(
                    localPath = localPath,
                    remoteName = config.remoteName,
                    remotePath = config.remotePath,
                    mode = config.mode,
                    resync = resync,
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
                val updated = groups.map { g ->
                    if (g.id != groupId) {
                        g
                    } else {
                        g.copy(profiles = g.profiles.map { p -> if (p.id == profileId) p.copy(cloudSync = finalConfig) else p })
                    }
                }
                persist(updated)
                error
            }
        } finally {
            _busy.value = null
        }
    }
}
