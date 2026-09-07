package com.multiprofileemu.cloud

import android.content.Context
import com.multiprofileemu.MultiProfileEmuApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * rclone has no official Android build, but its Linux releases are statically-linked Go binaries
 * with no dynamic linker dependency (verified: `file` reports "statically linked", no
 * `PT_INTERP`) — they run directly under Android's Linux kernel with no root needed, the same way
 * they'd run on any other Linux box. Running an arbitrary downloaded binary on API 29+ is blocked
 * by W^X/SELinux, though, so the only way to actually execute one is to ship it as a fake native
 * library under `jniLibs/<abi>/librclone.so` — Android extracts those at install time with
 * execute permission already granted, which is exactly what a real .so needs too.
 */
class AndroidRcloneRunner(context: Context, private val configPath: String) : RcloneRunner {
    private val binaryPath = File(context.applicationInfo.nativeLibraryDir, "librclone.so").path

    override fun isAvailable(): Boolean = File(binaryPath).exists()

    // Same shape as DesktopRcloneRunner.run — see that file for why cancellation destroys the
    // process explicitly instead of relying on thread interruption to unblock the blocking read.
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

actual fun createRcloneRunner(configPath: String): RcloneRunner =
    AndroidRcloneRunner(MultiProfileEmuApplication.instance, configPath)
