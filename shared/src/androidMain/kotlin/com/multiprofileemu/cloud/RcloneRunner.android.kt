package com.multiprofileemu.cloud

/** Cloud sync is desktop-only for now — see the plan for why Android needs a different OAuth flow. */
class AndroidRcloneRunner : RcloneRunner {
    override fun isAvailable(): Boolean = false

    override suspend fun run(args: List<String>, onJsonLine: (String) -> Unit): Int =
        throw UnsupportedOperationException("Cloud sync is not available on Android yet")
}

actual fun createRcloneRunner(configPath: String): RcloneRunner = AndroidRcloneRunner()
