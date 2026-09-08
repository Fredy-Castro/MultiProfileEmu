package com.multiprofileemu.model

import kotlinx.serialization.Serializable

enum class CloudSyncMode { UPLOAD, DOWNLOAD, BISYNC }

@Serializable
data class CloudSyncConfig(
    val remoteName: String,
    val remotePath: String,
    val mode: CloudSyncMode,
    /** True once a bisync `--resync` has succeeded for this pair — later runs skip it. */
    val everResynced: Boolean = false,
    val lastSyncedEpochMs: Long? = null,
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
