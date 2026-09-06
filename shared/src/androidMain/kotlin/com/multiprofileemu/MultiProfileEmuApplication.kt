package com.multiprofileemu

import android.app.Application

/**
 * The `expect`/`actual` factory functions in commonMain (createFileSystemAdapter,
 * createGamepadController, defaultAppDataDir) take no parameters, but their Android
 * implementations need a Context. Declaring it here in the Application, set once at process
 * start, is the standard way to bridge that gap without threading a Context through every
 * commonMain call site.
 */
class MultiProfileEmuApplication : Application() {
    companion object {
        lateinit var instance: MultiProfileEmuApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
