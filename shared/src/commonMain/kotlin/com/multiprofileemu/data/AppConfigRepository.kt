package com.multiprofileemu.data

import com.multiprofileemu.fs.FileSystemAdapter
import com.multiprofileemu.model.SaveGroup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the list of [SaveGroup]s as a single JSON file. */
class AppConfigRepository(
    private val fs: FileSystemAdapter,
    private val configFilePath: String,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun loadGroups(): List<SaveGroup> {
        val text = fs.readTextFile(configFilePath) ?: return emptyList()
        return try {
            json.decodeFromString<List<SaveGroup>>(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveGroups(groups: List<SaveGroup>) {
        fs.writeTextFile(configFilePath, json.encodeToString(groups))
    }
}
