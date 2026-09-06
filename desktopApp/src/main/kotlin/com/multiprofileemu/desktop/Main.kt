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
import com.multiprofileemu.model.SaveGroup
import com.multiprofileemu.model.SaveProfile
import com.multiprofileemu.ui.App
import com.multiprofileemu.ui.FirstRunHomeScreen
import com.multiprofileemu.util.defaultAppDataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        exitProcess(runCli(args))
    }
    runGui()
}

private fun runGui() = application {
    val fs = remember { createFileSystemAdapter() }
    // Fixed, never-moved location: just enough to remember where the *real* home dir is.
    val anchorDir = remember { defaultAppDataDir() }
    val homeManager = remember { AppHomeManager(fs, anchorDir, defaultHomeDir = anchorDir) }
    // Outlives any screen's rememberCoroutineScope() — a migration in flight must finish even if
    // the user closes the Settings dialog mid-copy. See AppController for the same reasoning.
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

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
                        val error = appScope.async {
                            try {
                                homeManager.migrateHomeTo(newDir) { progress -> controller.reportProgress(progress) }
                                null
                            } catch (e: Exception) {
                                e.message ?: "No se pudo migrar la carpeta de datos."
                            } finally {
                                controller.reportProgress(null)
                            }
                        }.await()
                        if (error == null) homeDir = newDir
                        error
                    },
                )
            }
        }
    }
}

/**
 * Headless command-line mode — meant to run as a pre-launch step before an emulator, e.g. from a
 * Steam (or Steam ROM Manager) launch-options script: switch to the right profile first, then
 * start the actual game/emulator. Never touches Compose/GLFW, so it stays instant.
 *
 * Exit codes: 0 = success (including "already active", a safe no-op), 1 = group/profile not
 * found, 2 = usage error.
 */
private fun runCli(args: Array<String>): Int {
    val fs = createFileSystemAdapter()
    val anchorDir = defaultAppDataDir()
    val homeManager = AppHomeManager(fs, anchorDir, defaultHomeDir = anchorDir)
    val homeDir = if (homeManager.isFirstRun()) anchorDir else homeManager.currentHomeDir()
    val configRepository = AppConfigRepository(fs, configFilePath = "$homeDir/config.json")
    val swapEngine = ProfileSwapEngine(fs, ProfileStorageResolver(profilesRootDir = "$homeDir/profiles"))

    return when (args[0].lowercase()) {
        "switch" -> cmdSwitch(args, configRepository, swapEngine)
        "list" -> cmdList(configRepository)
        "help", "-h", "--help" -> {
            println(usageText())
            0
        }
        else -> {
            System.err.println("Comando desconocido: '${args[0]}'.\n")
            System.err.println(usageText())
            2
        }
    }
}

private fun cmdSwitch(args: Array<String>, configRepository: AppConfigRepository, swapEngine: ProfileSwapEngine): Int {
    if (args.size < 3) {
        System.err.println("Uso: switch <grupo> <perfil>")
        return 2
    }
    val groupQuery = args[1]
    val profileQuery = args[2]
    val groups = configRepository.loadGroups()

    val group = findGroup(groups, groupQuery)
    if (group == null) {
        System.err.println("No se encontró el grupo '$groupQuery'. Usá 'list' para ver los grupos disponibles.")
        return 1
    }

    val profile = findProfile(group, profileQuery)
    if (profile == null) {
        System.err.println("No se encontró el perfil '$profileQuery' en '${group.name}'. Usá 'list' para ver los perfiles disponibles.")
        return 1
    }

    if (profile.id == group.activeProfileId) {
        println("'${profile.name}' ya es el perfil activo en '${group.name}'; no hay nada que hacer.")
        return 0
    }

    val updatedGroup = swapEngine.switchProfile(group, profile.id)
    configRepository.saveGroups(groups.map { if (it.id == updatedGroup.id) updatedGroup else it })
    println("Perfil activo de '${group.name}' cambiado a '${profile.name}'.")
    return 0
}

private fun cmdList(configRepository: AppConfigRepository): Int {
    val groups = configRepository.loadGroups()
    if (groups.isEmpty()) {
        println("No hay carpetas de saves configuradas todavía. Abrí MultiProfileEmu para crear una.")
        return 0
    }
    for (group in groups) {
        println(group.name)
        for (profile in group.profiles) {
            val marker = if (profile.id == group.activeProfileId) "*" else " "
            println("  [$marker] ${profile.name}")
        }
    }
    return 0
}

private fun findGroup(groups: List<SaveGroup>, query: String): SaveGroup? =
    groups.firstOrNull { it.id == query } ?: groups.firstOrNull { it.name.equals(query, ignoreCase = true) }

private fun findProfile(group: SaveGroup, query: String): SaveProfile? =
    group.profiles.firstOrNull { it.id == query } ?: group.profiles.firstOrNull { it.name.equals(query, ignoreCase = true) }

private fun usageText(): String = """
    MultiProfileEmu — línea de comandos

    Uso:
      MultiProfileEmu switch <grupo> <perfil>   Cambia el perfil activo de <grupo> a <perfil>
                                                 (nombre o id; hace el respaldo automático de siempre)
      MultiProfileEmu list                      Lista los grupos y perfiles configurados, marcando el activo

    Pensado para encadenarse antes de abrir el emulador (por ejemplo desde los parámetros de
    lanzamiento de un acceso directo de Steam, vía un script que primero sincroniza el perfil y
    después inicia el juego). Ver README para un ejemplo.
""".trimIndent()
