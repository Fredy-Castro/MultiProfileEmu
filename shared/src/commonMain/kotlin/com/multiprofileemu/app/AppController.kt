package com.multiprofileemu.app

import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.model.SaveGroup
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _groups = MutableStateFlow(configRepository.loadGroups())
    val groups: StateFlow<List<SaveGroup>> = _groups.asStateFlow()

    private suspend fun runMutation(block: suspend () -> Unit) {
        scope.async { mutex.withLock { block() } }.await()
    }

    private suspend fun persist(newGroups: List<SaveGroup>) {
        _groups.value = newGroups
        withContext(Dispatchers.IO) { configRepository.saveGroups(newGroups) }
    }

    private suspend fun updateGroup(groupId: String, transform: (SaveGroup) -> SaveGroup) = runMutation {
        val updated = withContext(Dispatchers.IO) {
            _groups.value.map { if (it.id == groupId) transform(it) else it }
        }
        persist(updated)
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
        updateGroup(groupId) { swapEngine.switchProfile(it, profileId) }

    suspend fun createProfileFromActive(groupId: String, name: String) =
        updateGroup(groupId) { swapEngine.createProfileFromActive(it, name) }

    suspend fun createEmptyProfile(groupId: String, name: String) =
        updateGroup(groupId) { swapEngine.createEmptyProfile(it, name) }

    suspend fun syncActiveToProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { swapEngine.syncActiveToProfile(it, profileId) }

    suspend fun renameProfile(groupId: String, profileId: String, newName: String) =
        updateGroup(groupId) { swapEngine.renameProfile(it, profileId, newName) }

    suspend fun deleteProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { swapEngine.deleteProfile(it, profileId) }

    suspend fun duplicateProfile(groupId: String, profileId: String, newName: String) =
        updateGroup(groupId) { swapEngine.duplicateProfile(it, profileId, newName) }
}
