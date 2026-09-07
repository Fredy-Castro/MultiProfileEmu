package com.multiprofileemu.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class DesktopRcloneRunner(private val configPath: String) : RcloneRunner {

    override fun isAvailable(): Boolean = try {
        val process = ProcessBuilder("rclone", "version").redirectErrorStream(true).start()
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
        val command = listOf("rclone") + args + listOf("--config", configPath)
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
