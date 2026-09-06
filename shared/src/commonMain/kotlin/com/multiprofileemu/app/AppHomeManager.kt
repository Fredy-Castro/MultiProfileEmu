package com.multiprofileemu.app

import com.multiprofileemu.fs.FileSystemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class HomePointer(val homeDir: String)

/**
 * Resolves and persists where the app's real data (config.json + every profile snapshot) lives.
 *
 * The pointer file itself always stays at [anchorDir] — a fixed, platform-default location — so
 * the app can find it on every launch no matter where the user has since moved everything else.
 * Only the *contents* it points to are meant to be migrated freely (an external drive, a synced
 * folder, etc).
 */
class AppHomeManager(
    private val fs: FileSystemAdapter,
    private val anchorDir: String,
    private val defaultHomeDir: String,
) {
    private val pointerFilePath = "$anchorDir/location.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** True the very first time the app runs on this machine — no choice has been made yet. */
    fun isFirstRun(): Boolean = !fs.exists(pointerFilePath)

    fun currentHomeDir(): String {
        val text = fs.readTextFile(pointerFilePath) ?: return defaultHomeDir
        return try {
            json.decodeFromString<HomePointer>(text).homeDir
        } catch (e: Exception) {
            defaultHomeDir
        }
    }

    /** Records the chosen home dir without moving any data — used for "use the default folder". */
    fun setHomeDir(path: String) {
        fs.ensureDirectory(anchorDir)
        fs.writeTextFile(pointerFilePath, json.encodeToString(HomePointer(path)))
    }

    /**
     * Copies everything from the current home into [newHomeDir], then points there from now on.
     * Runs on [Dispatchers.IO]: this can copy an entire config + every profile snapshot, which on
     * Android (SAF) is Binder IPC per file and easily blows past the ANR threshold if run on the
     * caller's (UI) thread.
     */
    suspend fun migrateHomeTo(newHomeDir: String) = withContext(Dispatchers.IO) {
        val oldHomeDir = currentHomeDir()
        fs.ensureDirectory(newHomeDir)
        if (oldHomeDir != newHomeDir) {
            fs.copyDirectoryContents(oldHomeDir, newHomeDir)
        }
        setHomeDir(newHomeDir)
    }
}
