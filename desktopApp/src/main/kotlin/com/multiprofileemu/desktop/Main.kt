package com.multiprofileemu.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiprofileemu.app.AppController
import com.multiprofileemu.app.AppHomeManager
import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.ProfileStorageResolver
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.fs.createFileSystemAdapter
import com.multiprofileemu.input.createGamepadController
import com.multiprofileemu.ui.App
import com.multiprofileemu.ui.FirstRunHomeScreen
import com.multiprofileemu.util.defaultAppDataDir

fun main() = application {
    val fs = remember { createFileSystemAdapter() }
    // Fixed, never-moved location: just enough to remember where the *real* home dir is.
    val anchorDir = remember { defaultAppDataDir() }
    val homeManager = remember { AppHomeManager(fs, anchorDir, defaultHomeDir = anchorDir) }

    var homeDir by remember {
        mutableStateOf(if (homeManager.isFirstRun()) null else homeManager.currentHomeDir())
    }

    val gamepad = remember { createGamepadController() }
    LaunchedEffect(Unit) { gamepad.start() }

    Window(onCloseRequest = ::exitApplication, title = "MultiProfileEmu") {
        val resolvedHomeDir = homeDir
        if (resolvedHomeDir == null) {
            FirstRunHomeScreen(
                defaultPath = anchorDir,
                onChoose = { chosen ->
                    homeManager.setHomeDir(chosen)
                    homeDir = chosen
                },
            )
        } else {
            // Recreate the whole data layer whenever the home dir changes (first choice or a
            // later migration) so the app always reads/writes from the currently active folder.
            key(resolvedHomeDir) {
                val controller = remember(resolvedHomeDir) {
                    fs.ensureDirectory(resolvedHomeDir)
                    val configRepository = AppConfigRepository(fs, configFilePath = "$resolvedHomeDir/config.json")
                    val swapEngine = ProfileSwapEngine(fs, ProfileStorageResolver(profilesRootDir = "$resolvedHomeDir/profiles"))
                    AppController(configRepository, swapEngine)
                }

                App(
                    controller = controller,
                    gamepad = gamepad,
                    homeDir = resolvedHomeDir,
                    onMigrateHome = { newDir ->
                        try {
                            homeManager.migrateHomeTo(newDir)
                            homeDir = newDir
                            null
                        } catch (e: Exception) {
                            e.message ?: "No se pudo migrar la carpeta de datos."
                        }
                    },
                )
            }
        }
    }
}
