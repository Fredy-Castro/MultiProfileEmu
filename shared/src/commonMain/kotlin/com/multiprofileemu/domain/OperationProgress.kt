package com.multiprofileemu.domain

/** Which long-running, file-copying step is currently in flight — lets the UI show a phase-specific label. */
enum class OperationPhase {
    CAPTURING,
    SYNCING,
    DUPLICATING,
    BACKING_UP_OUTGOING,
    RESTORING_INCOMING,
    MIGRATING,
}

data class OperationProgress(
    val phase: OperationPhase,
    val filesDone: Int,
    val filesTotal: Int,
) {
    /** 0 files to copy is instantly "done" rather than indeterminate. */
    val fraction: Float
        get() = if (filesTotal <= 0) 1f else (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
}
