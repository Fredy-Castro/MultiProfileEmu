package com.multiprofileemu.domain

/**
 * A file changed on both sides between two BISYNC runs, left unresolved by rclone (see the
 * `--conflict-resolve none` comment in [CloudSyncEngine.sync]) for the user to pick a winner.
 * Transient — never persisted, only lives in [com.multiprofileemu.app.AppController] state while
 * the user resolves it.
 */
data class SyncConflict(
    val relativePath: String,
    /** Path (relative to the synced folder) of the local-side copy, e.g. "save.dat.local1". */
    val localConflictPath: String,
    /** Path (relative to the synced folder) of the cloud-side copy, e.g. "save.dat.cloud1". */
    val cloudConflictPath: String,
    val localModified: String?,
    val cloudModified: String?,
)
