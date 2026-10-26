package com.multiprofileemu.platform

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.multiprofileemu.MultiProfileEmuApplication
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around Shizuku's API — lets this app run file operations as `adb shell`'s UID once
 * the user has started Shizuku (separately, via Wireless debugging or USB pairing) and granted
 * this app permission. Used by [com.multiprofileemu.fs.AndroidFileSystemAdapter] as a fallback for
 * the one thing confirmed broken even with `MANAGE_EXTERNAL_STORAGE`: creating a new file inside
 * *another* app's `Android/data` folder (reading an existing one there works fine without this).
 *
 * `Shizuku.newProcess` (the obvious "just run a shell command" API) turned out to be private in
 * the current shizuku-api — confirmed by decompiling the actual jar after the compiler rejected
 * it. [PrivilegedFileOpsService] + [bindUserService] is Shizuku's actual public mechanism: it
 * spawns that class in a separate process running as the privileged UID and hands back an AIDL
 * binder to call into it.
 *
 * Everything here degrades to "unavailable"/no-op instead of throwing when Shizuku isn't running —
 * this app must work the same as before for anyone who never installs Shizuku at all.
 */
internal object ShizukuBridge {
    private const val REQUEST_CODE = 9100
    private var pendingCallback: ((Boolean) -> Unit)? = null

    @Volatile private var serviceProxy: IPrivilegedFileOps? = null
    private var bindingLatch: CountDownLatch? = null
    private val bindLock = Any()

    private val resultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        val callback = pendingCallback
        pendingCallback = null
        callback?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceProxy = binder?.let { IPrivilegedFileOps.Stub.asInterface(it) }
            bindingLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceProxy = null
        }
    }

    init {
        Shizuku.addRequestPermissionResultListener(resultListener)
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }
        pendingCallback = onResult
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Binds [PrivilegedFileOpsService] the first time it's needed, then reuses the same proxy. Blocking. */
    private fun ensureServiceBound(): IPrivilegedFileOps? {
        serviceProxy?.let { return it }
        if (!hasPermission()) return null
        synchronized(bindLock) {
            serviceProxy?.let { return it }
            val latch = CountDownLatch(1)
            bindingLatch = latch
            val args = Shizuku.UserServiceArgs(
                ComponentName(MultiProfileEmuApplication.instance.packageName, PrivilegedFileOpsService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(false)
                .version(1)
            try {
                Shizuku.bindUserService(args, serviceConnection)
            } catch (e: Exception) {
                return null
            }
            latch.await(10, TimeUnit.SECONDS)
            return serviceProxy
        }
    }

    /** Blocking — callers already run this on [kotlinx.coroutines.Dispatchers.IO]. */
    fun copyDirectory(sourcePath: String, destPath: String): Boolean {
        val proxy = ensureServiceBound() ?: return false
        return try {
            proxy.copyDirectory(sourcePath, destPath) == 0
        } catch (e: Exception) {
            false
        }
    }
}

actual fun isShizukuAvailable(): Boolean = ShizukuBridge.isAvailable()

actual fun hasShizukuPermission(): Boolean = ShizukuBridge.hasPermission()

actual fun requestShizukuPermission(onResult: (Boolean) -> Unit) = ShizukuBridge.requestPermission(onResult)
