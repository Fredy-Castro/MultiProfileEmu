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

    /** Where a profile's snapshot lives on disk — exposed for callers (e.g. cloud sync) that need the raw path. */
    fun storagePathFor(groupId: String, profileId: String): String = storage.storagePath(groupId, profileId)

    /**
     * Null on success, or a reason to treat the copy as a failure — same tri-state handling
     * [AppController.mirrorDirectory] uses. `true`/`false` from [FileSystemAdapter.copyDirectoryContents]
     * are both authoritative; only `null` (unverified) falls through to the source/dest-size
     * fallback, which — unlike for a cloud-sync download into a folder that started out empty —
     * [switchProfile]'s destination is essentially *never* empty beforehand (the outgoing
     * profile's old files are still sitting there), so a `false` must never be treated the same
     * as that fallback passing.
     */
    private fun copyFailureReason(verifiedByAdapter: Boolean?, sourcePath: String, destPath: String): String? =
        when (verifiedByAdapter) {
            true -> null
            false -> "el proceso con privilegios (Shizuku) no pudo escribir ahí — puede que la carpeta/" +
                "archivo no tenga permiso de escritura de grupo (hace falta chmod con root real para " +
                "eso, Shizuku por sí solo no alcanza)"
            null -> if (fs.sizeOfDirectory(sourcePath) == 0L || fs.sizeOfDirectory(destPath) > 0L) {
                null
            } else {
                "el origen tiene contenido pero el destino quedó vacío tras copiar"
            }
        }

    /**
     * Switches [group]'s active save folder to [targetProfileId]'s content.
     *
     * Safety: if another profile is currently active, its content is copied back into its
     * own storage *before* the active folder is cleared, so switching away never loses data.
     *
     * Both copies below are checked via [copyFailureReason] — confirmed on real hardware that the
     * *incoming* one specifically can silently touch nothing at all when the active folder lives
     * inside another app's Android/data and that app created its files without group-write
     * permission (not even Shizuku's shell-level access can override that, only root could);
     * without this check, the switch would report success while the real folder keeps the
     * outgoing profile's content.
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
            val backedUp = fs.copyDirectoryContents(activePath, outgoingPath) { done, total ->
                onProgress(OperationProgress(OperationPhase.BACKING_UP_OUTGOING, done.toLong(), total.toLong()))
            }
            copyFailureReason(backedUp, activePath, outgoingPath)?.let { reason ->
                error("No se pudo respaldar el perfil que estaba activo antes de cambiar ($reason) — se canceló el cambio para no perder su progreso.")
            }
        }

        val targetPath = storage.storagePath(group.id, targetProfileId)
        fs.ensureDirectory(targetPath)
        fs.clearDirectoryContents(activePath)
        val restored = fs.copyDirectoryContents(targetPath, activePath) { done, total ->
            onProgress(OperationProgress(OperationPhase.RESTORING_INCOMING, done.toLong(), total.toLong()))
        }
        copyFailureReason(restored, targetPath, activePath)?.let { reason ->
            error("No se pudo escribir el perfil en la carpeta activa ($reason).")
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
            onProgress(OperationProgress(OperationPhase.CAPTURING, done.toLong(), total.toLong()))
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
            onProgress(OperationProgress(OperationPhase.SYNCING, done.toLong(), total.toLong()))
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
            onProgress(OperationProgress(OperationPhase.DUPLICATING, done.toLong(), total.toLong()))
        }
        return group.copy(profiles = group.profiles + newProfile)
    }
}
