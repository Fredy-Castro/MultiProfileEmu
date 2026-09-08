package com.multiprofileemu.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class DesktopRcloneRunner(private val configPath: String) : RcloneRunner {

    // Resolved once and reused — the packaged app's resources dir doesn't change mid-run.
    private val binaryPath: String by lazy { resolveBinaryPath() }

    /**
     * Prefers the rclone binary bundled into the package (see desktopApp/build.gradle.kts'
     * appResourcesRootDir) over relying on a system install — most people, especially on Linux,
     * shouldn't have to install rclone themselves just to use cloud sync. Falls back to the bare
     * "rclone" (PATH lookup) when there's no bundled copy: that's the normal case for
     * `./gradlew :desktopApp:run` (compose.application.resources.dir is only set in a packaged
     * app), and also covers someone who already has rclone installed system-wide even without
     * the bundle.
     */
    private fun resolveBinaryPath(): String {
        val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return "rclone"
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val bundled = File(resourcesDir, if (isWindows) "rclone.exe" else "rclone")
        if (!bundled.exists()) return "rclone"
        // Defensive, idempotent: nothing here guarantees the executable bit survives Gradle's
        // resource copy + (on Linux) the manual createDistributable -> AppDir -> appimagetool
        // pipeline documented in the README — that pipeline already has one real chmod gotcha
        // (fails outright if the build runs on /mnt/c/... under WSL), so don't assume this bit
        // makes it through untouched.
        if (!isWindows) bundled.setExecutable(true)
        return bundled.absolutePath
    }

    override fun isAvailable(): Boolean = try {
        val process = ProcessBuilder(binaryPath, "version").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0
    } catch (e: IOException) {
        false
    }

    // Cancellation (e.g. a withTimeoutOrNull around the Drive-authorize flow) must actually stop
    // the subprocess, not just abandon the coroutine — otherwise a user who never finishes the
    // browser consent leaves a zombie rclone process running forever. Plain thread interruption
    // does not unblock a blocking InputStream.read(), so the cancellation handler explicitly
    // destroys the process, which closes its streams and unblocks the read below with an
    // IOException instead.
    override suspend fun run(args: List<String>, onJsonLine: (String) -> Unit): Int {
        val command = listOf(binaryPath) + args + listOf("--config", configPath)
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }
        return try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { process.destroyForcibly() }
                    val exitCode = try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            reader.lineSequence().forEach { line -> if (line.isNotBlank()) onJsonLine(line) }
                        }
                        process.waitFor()
                    } catch (e: IOException) {
                        // Stream closed because destroyForcibly() ran above (cancellation) —
                        // the coroutine is already cancelled, so this exit code is never used.
                        -1
                    }
                    cont.resume(exitCode) { _, _, _ -> }
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

actual fun createRcloneRunner(configPath: String): RcloneRunner = DesktopRcloneRunner(configPath)
