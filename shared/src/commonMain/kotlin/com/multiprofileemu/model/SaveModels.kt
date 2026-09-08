package com.multiprofileemu.model

import kotlinx.serialization.Serializable

enum class CloudSyncMode { UPLOAD, DOWNLOAD, BISYNC }

/** Only meaningful when [CloudSyncConfig.mode] is [CloudSyncMode.BISYNC] — UPLOAD/DOWNLOAD are one-directional and never conflict. */
enum class ConflictResolutionStrategy {
    /** Both versions are kept side by side and the user picks a winner per file (see [com.multiprofileemu.domain.SyncConflict]). */
    MANUAL,
    /** rclone picks the more recently modified version automatically; the other is discarded. */
    NEWEST,
}

@Serializable
data class CloudSyncConfig(
    val remoteName: String,
    val remotePath: String,
    val mode: CloudSyncMode,
    /** True once a bisync `--resync` has succeeded for this pair — later runs skip it. */
    val everResynced: Boolean = false,
    val lastSyncedEpochMs: Long? = null,
    val conflictResolution: ConflictResolutionStrategy = ConflictResolutionStrategy.MANUAL,
)

@Serializable
data class SaveProfile(
    val id: String,
    val name: String,
    val colorArgb: Long? = null,
    val lastSyncedEpochMs: Long? = null,
    val cloudSync: CloudSyncConfig? = null,
)

@Serializable
data class SaveGroup(
    val id: String,
    val name: String,
    val activeFolderPath: String,
    val emulatorHint: String? = null,
    val profiles: List<SaveProfile> = emptyList(),
    val activeProfileId: String? = null,
    /** Cloud sync configured directly against [activeFolderPath], independent of any profile. */
    val activeCloudSync: CloudSyncConfig? = null,
)
