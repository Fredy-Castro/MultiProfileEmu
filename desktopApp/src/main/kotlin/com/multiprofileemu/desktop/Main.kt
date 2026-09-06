package com.multiprofileemu.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiprofileemu.app.AppController
import com.multiprofileemu.data.AppConfigRepository
import com.multiprofileemu.domain.ProfileStorageResolver
import com.multiprofileemu.domain.ProfileSwapEngine
import com.multiprofileemu.fs.createFileSystemAdapter
import com.multiprofileemu.input.createGamepadController
import com.multiprofileemu.ui.App
import com.multiprofileemu.util.defaultAppDataDir

fun main() = application {
    val fs = remember { createFileSystemAdapter() }
    val appDataDir = remember { defaultAppDataDir() }
    val controller = remember {
        fs.ensureDirectory(appDataDir)
        val configRepository = AppConfigRepository(fs, configFilePath = "$appDataDir/config.json")
        val swapEngine = ProfileSwapEngine(fs, ProfileStorageResolver(profilesRootDir = "$appDataDir/profiles"))
        AppController(configRepository, swapEngine)
    }
    val gamepad = remember { createGamepadController() }

    LaunchedEffect(Unit) { gamepad.start() }

    Window(onCloseRequest = ::exitApplication, title = "MultiProfileEmu") {
        App(controller, gamepad)
    }
}
