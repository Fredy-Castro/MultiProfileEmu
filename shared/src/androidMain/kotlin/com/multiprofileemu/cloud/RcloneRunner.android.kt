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
 * rclone has no official Android build, so this ships one built from source (see README's Android
 * section for the exact `go build` invocation): `GOOS=android GOARCH=arm64 CGO_ENABLED=1`, using
 * the Android NDK's clang so it links against bionic instead of Go's pure-Go resolver. That part
 * matters more than it sounds — the plain upstream `linux-arm64` release binary runs fine under
 * Android's kernel (verified: no root needed, no SELinux issue) but its DNS resolution is
 * completely broken there: Go's pure-Go resolver reads `/etc/resolv.conf`, which doesn't exist on
 * Android (DNS is resolved through the platform's own framework instead), so every hostname lookup
 * fails with "dial tcp: lookup ... on [::1]:53: connection refused". The bionic-linked build uses
 * Android's real resolver via cgo and doesn't have this problem. Running an arbitrary downloaded
 * binary on API 29+ is blocked by W^X/SELinux regardless, so the only way to actually execute one
 * is to ship it as a fake native library under `jniLibs/<abi>/librclone.so` — Android extracts
 * those at install time with execute permission already granted, which is exactly what a real .so
 * needs too.
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
