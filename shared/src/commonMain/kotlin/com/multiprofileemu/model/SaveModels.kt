package com.multiprofileemu.model

import kotlinx.serialization.Serializable

@Serializable
data class SaveProfile(
    val id: String,
    val name: String,
    val colorArgb: Long? = null,
    val lastSyncedEpochMs: Long? = null,
)

@Serializable
data class SaveGroup(
    val id: String,
    val name: String,
    val activeFolderPath: String,
    val emulatorHint: String? = null,
    val profiles: List<SaveProfile> = emptyList(),
    val activeProfileId: String? = null,
)
