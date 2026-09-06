package com.multiprofileemu.domain

import com.multiprofileemu.fs.FileSystemAdapter
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.model.SaveProfile
import com.multiprofileemu.util.currentEpochMillis
import com.multiprofileemu.util.newId

/** Resolves where a profile's snapshot lives on disk, independent of the group's active folder. */
class ProfileStorageResolver(private val profilesRootDir: String) {
    fun storagePath(groupId: String, profileId: String): String =
        "$profilesRootDir/$groupId/$profileId"
}

/**
 * Core save-swapping logic, shared across every platform. Operates purely through
 * [FileSystemAdapter] so it never needs to know whether it is touching a plain folder
 * (desktop) or a SAF tree (Android).
 */
class ProfileSwapEngine(
    private val fs: FileSystemAdapter,
    private val storage: ProfileStorageResolver,
) {

    /**
     * Switches [group]'s active save folder to [targetProfileId]'s content.
     *
     * Safety: if another profile is currently active, its content is copied back into its
     * own storage *before* the active folder is cleared, so switching away never loses data.
     */
    fun switchProfile(
        group: SaveGroup,
        targetProfileId: String,
        onProgress: (OperationProgress) -> Unit = {},
    ): SaveGroup {
        require(group.profiles.any { it.id == targetProfileId }) { "Unknown profile: $targetProfileId" }

        val activePath = group.activeFolderPath
        fs.ensureDirectory(activePath)

        val outgoingId = group.activeProfileId
        if (outgoingId != null) {
            val outgoingPath = storage.storagePath(group.id, outgoingId)
            fs.ensureDirectory(outgoingPath)
            fs.clearDirectoryContents(outgoingPath)
            fs.copyDirectoryContents(activePath, outgoingPath) { done, total ->
                onProgress(OperationProgress(OperationPhase.BACKING_UP_OUTGOING, done, total))
            }
        }

        val targetPath = storage.storagePath(group.id, targetProfileId)
        fs.ensureDirectory(targetPath)
        fs.clearDirectoryContents(activePath)
        fs.copyDirectoryContents(targetPath, activePath) { done, total ->
            onProgress(OperationProgress(OperationPhase.RESTORING_INCOMING, done, total))
        }

        val now = currentEpochMillis()
        val updatedProfiles = group.profiles.map { profile ->
            when (profile.id) {
                outgoingId, targetProfileId -> profile.copy(lastSyncedEpochMs = now)
                else -> profile
            }
        }
        return group.copy(activeProfileId = targetProfileId, profiles = updatedProfiles)
    }

    /** Captures whatever is currently in the active folder as a brand-new profile (first-use flow). */
    fun createProfileFromActive(
        group: SaveGroup,
        profileName: String,
        onProgress: (OperationProgress) -> Unit = {},
    ): SaveGroup {
        val newProfile = SaveProfile(id = newId(), name = profileName, lastSyncedEpochMs = currentEpochMillis())
        val newPath = storage.storagePath(group.id, newProfile.id)
        fs.ensureDirectory(group.activeFolderPath)
        fs.ensureDirectory(newPath)
        fs.copyDirectoryContents(group.activeFolderPath, newPath) { done, total ->
            onProgress(OperationProgress(OperationPhase.CAPTURING, done, total))
        }

        return group.copy(
            profiles = group.profiles + newProfile,
            activeProfileId = group.activeProfileId ?: newProfile.id,
        )
    }

    /** Creates a fresh, empty profile (e.g. a new family member starting a new save). */
    fun createEmptyProfile(group: SaveGroup, profileName: String): SaveGroup {
        val newProfile = SaveProfile(id = newId(), name = profileName)
        fs.ensureDirectory(storage.storagePath(group.id, newProfile.id))
        return group.copy(profiles = group.profiles + newProfile)
    }

    /** Manual "back up now" without switching the active profile. */
    fun syncActiveToProfile(
        group: SaveGroup,
        profileId: String,
        onProgress: (OperationProgress) -> Unit = {},
    ): SaveGroup {
        require(group.profiles.any { it.id == profileId }) { "Unknown profile: $profileId" }
        val path = storage.storagePath(group.id, profileId)
        fs.ensureDirectory(path)
        fs.clearDirectoryContents(path)
        fs.ensureDirectory(group.activeFolderPath)
        fs.copyDirectoryContents(group.activeFolderPath, path) { done, total ->
            onProgress(OperationProgress(OperationPhase.SYNCING, done, total))
        }

        val now = currentEpochMillis()
        return group.copy(profiles = group.profiles.map {
            if (it.id == profileId) it.copy(lastSyncedEpochMs = now) else it
        })
    }

    fun renameProfile(group: SaveGroup, profileId: String, newName: String): SaveGroup =
        group.copy(profiles = group.profiles.map {
            if (it.id == profileId) it.copy(name = newName) else it
        })

    fun deleteProfile(group: SaveGroup, profileId: String): SaveGroup {
        fs.clearDirectoryContents(storage.storagePath(group.id, profileId))
        return group.copy(
            profiles = group.profiles.filterNot { it.id == profileId },
            activeProfileId = group.activeProfileId?.takeIf { it != profileId },
        )
    }

    fun duplicateProfile(
        group: SaveGroup,
        profileId: String,
        newName: String,
        onProgress: (OperationProgress) -> Unit = {},
    ): SaveGroup {
        val source = group.profiles.first { it.id == profileId }
        val newProfile = source.copy(id = newId(), name = newName, lastSyncedEpochMs = currentEpochMillis())
        val sourcePath = storage.storagePath(group.id, profileId)
        val destPath = storage.storagePath(group.id, newProfile.id)
        fs.ensureDirectory(sourcePath)
        fs.ensureDirectory(destPath)
        fs.copyDirectoryContents(sourcePath, destPath) { done, total ->
            onProgress(OperationProgress(OperationPhase.DUPLICATING, done, total))
        }
        return group.copy(profiles = group.profiles + newProfile)
    }
}
