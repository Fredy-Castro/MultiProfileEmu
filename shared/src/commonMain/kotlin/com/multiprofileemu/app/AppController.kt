package com.multiprofileemu.app

import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.util.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level, platform-agnostic app state. Every mutation persists immediately. */
class AppController(
    private val configRepository: AppConfigRepository,
    private val swapEngine: ProfileSwapEngine,
) {
    private val _groups = MutableStateFlow(configRepository.loadGroups())
    val groups: StateFlow<List<SaveGroup>> = _groups.asStateFlow()

    private fun persist(newGroups: List<SaveGroup>) {
        _groups.value = newGroups
        configRepository.saveGroups(newGroups)
    }

    private fun updateGroup(groupId: String, transform: (SaveGroup) -> SaveGroup) {
        persist(_groups.value.map { if (it.id == groupId) transform(it) else it })
    }

    fun addGroup(name: String, activeFolderPath: String, emulatorHint: String? = null) {
        val group = SaveGroup(
            id = newId(),
            name = name,
            activeFolderPath = activeFolderPath,
            emulatorHint = emulatorHint,
        )
        persist(_groups.value + group)
    }

    fun removeGroup(groupId: String) {
        persist(_groups.value.filterNot { it.id == groupId })
    }

    fun switchProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { swapEngine.switchProfile(it, profileId) }

    fun createProfileFromActive(groupId: String, name: String) =
        updateGroup(groupId) { swapEngine.createProfileFromActive(it, name) }

    fun createEmptyProfile(groupId: String, name: String) =
        updateGroup(groupId) { swapEngine.createEmptyProfile(it, name) }

    fun syncActiveToProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { swapEngine.syncActiveToProfile(it, profileId) }

    fun renameProfile(groupId: String, profileId: String, newName: String) =
        updateGroup(groupId) { swapEngine.renameProfile(it, profileId, newName) }

    fun deleteProfile(groupId: String, profileId: String) =
        updateGroup(groupId) { swapEngine.deleteProfile(it, profileId) }

    fun duplicateProfile(groupId: String, profileId: String, newName: String) =
        updateGroup(groupId) { swapEngine.duplicateProfile(it, profileId, newName) }
}
