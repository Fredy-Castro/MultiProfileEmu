package com.multiprofileemu.android

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.multiprofileemu.app.AppController
import com.multiprofileemu.app.AppHomeManager
import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.ProfileStorageResolver
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.fs.createFileSystemAdapter
import com.multiprofileemu.fs.encodeSafRoot
import com.multiprofileemu.input.AndroidGamepadController
import com.multiprofileemu.platform.AndroidFolderPickerBridge
import com.multiprofileemu.ui.App
import com.multiprofileemu.util.defaultAppDataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    // Constructed directly (not via the common createGamepadController() factory) so we keep the
    // concrete type here and can feed it raw KeyEvents from dispatchKeyEvent below — Android
    // delivers gamepad input push-style through the Activity, unlike desktop's GLFW poll loop.
    // Initialized in onCreate (not as a property initializer): applicationContext is still null
    // during the Activity's constructor — Android only attaches it afterwards, in attachBaseContext.
    private lateinit var gamepadController: AndroidGamepadController

    // Outlives every screen's rememberCoroutineScope() — a migration in flight must finish even
    // if the user backs out of Settings mid-copy, or it'd leave the pointer and the actual files
    // out of sync. See AppController for the same reasoning applied to profile swaps.
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must run before this Activity reaches STARTED, per registerForActivityResult's contract.
        AndroidFolderPickerBridge.register(this)

        gamepadController = AndroidGamepadController(applicationContext)
        gamepadController.start()

        val fs = createFileSystemAdapter()
        // Fixed, never-moved location: just enough to remember where the *real* home dir is.
        // No first-run picker on Android (unlike desktop) — defaults straight to app-private
        // storage, which always works with zero permission dialogs; migrating to a SAF folder
        // later is opt-in from Settings.
        val anchorDir = defaultAppDataDir()
        val homeManager = AppHomeManager(fs, anchorDir, defaultHomeDir = anchorDir)

        setContent {
            var homeDir by remember { mutableStateOf(homeManager.currentHomeDir()) }

            // Recreate the whole data layer whenever the home dir changes (a migration) so the
            // app always reads/writes from the currently active folder.
            key(homeDir) {
                val controller = remember(homeDir) {
                    fs.ensureDirectory(homeDir)
                    val configRepository = AppConfigRepository(fs, configFilePath = "$homeDir/config.json")
                    val swapEngine = ProfileSwapEngine(fs, ProfileStorageResolver(profilesRootDir = "$homeDir/profiles"))
                    AppController(configRepository, swapEngine)
                }

                App(
                    controller = controller,
                    gamepad = gamepadController,
                    homeDir = homeDir,
                    onMigrateHome = { newDir ->
                        // pickFolderDialog always hands back a raw SAF tree URI here; encode it
                        // so ProfileStorageResolver/AppConfigRepository's plain "$root/child"
                        // string concatenation keeps working once nested paths are appended.
                        val encoded = if (newDir.startsWith("content://")) encodeSafRoot(newDir) else newDir
                        // Runs on activityScope, not the caller's — surviving navigation away from
                        // Settings mid-copy — then hands the result back via await().
                        val error = activityScope.async {
                            try {
                                homeManager.migrateHomeTo(encoded) { progress -> controller.reportProgress(progress) }
                                null
                            } catch (e: Exception) {
                                e.message ?: "No se pudo migrar la carpeta de datos."
                            } finally {
                                controller.reportProgress(null)
                            }
                        }.await()
                        if (error == null) homeDir = encoded
                        error
                    },
                    supportsHomeMigration = true,
                )
            }
        }
    }

    override fun onDestroy() {
        gamepadController.stop()
        activityScope.cancel()
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
