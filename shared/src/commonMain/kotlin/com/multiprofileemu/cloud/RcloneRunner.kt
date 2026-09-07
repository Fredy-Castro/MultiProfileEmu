package com.multiprofileemu.cloud

/**
 * Thin wrapper over the system-installed `rclone` binary. Desktop shells out to the real
 * executable found on PATH; Android has no implementation yet (cloud sync is desktop-only for
 * now — see [isAvailable]), but still needs an `actual` so the shared module compiles for it.
 */
interface RcloneRunner {
    /** Whether an `rclone` executable could be located (and, on desktop, actually runs). */
    fun isAvailable(): Boolean

    /**
     * Runs `rclone <args> --config <configPath>`, invoking [onJsonLine] once per line of
     * stdout+stderr (rclone with `--use-json-log` emits one JSON object per line). Returns the
     * process exit code. Runs on [kotlinx.coroutines.Dispatchers.IO] and does not get killed if
     * the calling coroutine is cancelled — callers that need that guarantee should invoke this
     * through the same app-lifetime scope used for other file operations.
     */
    suspend fun run(args: List<String>, onJsonLine: (String) -> Unit): Int
}

expect fun createRcloneRunner(configPath: String): RcloneRunner
