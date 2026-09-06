package com.multiprofileemu.android

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.multiprofileemu.app.AppController
import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.ProfileStorageResolver
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.fs.createFileSystemAdapter
import com.multiprofileemu.input.AndroidGamepadController
import com.multiprofileemu.platform.AndroidFolderPickerBridge
import com.multiprofileemu.ui.App
import com.multiprofileemu.util.defaultAppDataDir

class MainActivity : ComponentActivity() {

    // Constructed directly (not via the common createGamepadController() factory) so we keep the
    // concrete type here and can feed it raw KeyEvents from dispatchKeyEvent below — Android
    // delivers gamepad input push-style through the Activity, unlike desktop's GLFW poll loop.
    // Initialized in onCreate (not as a property initializer): applicationContext is still null
    // during the Activity's constructor — Android only attaches it afterwards, in attachBaseContext.
    private lateinit var gamepadController: AndroidGamepadController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must run before this Activity reaches STARTED, per registerForActivityResult's contract.
        AndroidFolderPickerBridge.register(this)

        gamepadController = AndroidGamepadController(applicationContext)
        gamepadController.start()

        val fs = createFileSystemAdapter()
        val homeDir = defaultAppDataDir()
        fs.ensureDirectory(homeDir)
        val configRepository = AppConfigRepository(fs, configFilePath = "$homeDir/config.json")
        val swapEngine = ProfileSwapEngine(fs, ProfileStorageResolver(profilesRootDir = "$homeDir/profiles"))
        val controller = AppController(configRepository, swapEngine)

        setContent {
            App(
                controller = controller,
                gamepad = gamepadController,
                homeDir = homeDir,
                onMigrateHome = { "La migración de carpeta todavía no está disponible en Android." },
                supportsHomeMigration = false,
            )
        }
    }

    override fun onDestroy() {
        gamepadController.stop()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadController.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gamepadController.onTouchEvent()
        return super.dispatchTouchEvent(ev)
    }
}
