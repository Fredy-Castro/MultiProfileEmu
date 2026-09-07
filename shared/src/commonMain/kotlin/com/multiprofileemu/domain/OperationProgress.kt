package com.multiprofileemu.domain

/** Which long-running step is currently in flight — lets the UI show a phase-specific label. */
enum class OperationPhase {
    CAPTURING,
    SYNCING,
    DUPLICATING,
    BACKING_UP_OUTGOING,
    RESTORING_INCOMING,
    MIGRATING,

    /** Waiting on the user to finish the Google Drive consent flow in their browser. */
    CLOUD_AUTHORIZING,
    CLOUD_UPLOAD,
    CLOUD_DOWNLOAD,
    CLOUD_BISYNC,
}

/** True for phases with no meaningful [OperationProgress.fraction] — the UI shows an indeterminate spinner instead. */
val OperationPhase.isIndeterminate: Boolean
    get() = this == OperationPhase.CLOUD_AUTHORIZING

/**
 * [done]/[total] count files for local copy phases and bytes for [OperationPhase.CLOUD_UPLOAD]/
 * [OperationPhase.CLOUD_DOWNLOAD]/[OperationPhase.CLOUD_BISYNC] (rclone reports transfer progress
 * in bytes, not file counts) — the UI formats the pair differently depending on [phase].
 */
data class OperationProgress(
    val phase: OperationPhase,
    val done: Long,
    val total: Long,
) {
    /** Nothing to copy is instantly "done" rather than indeterminate. */
    val fraction: Float
        get() = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
}
