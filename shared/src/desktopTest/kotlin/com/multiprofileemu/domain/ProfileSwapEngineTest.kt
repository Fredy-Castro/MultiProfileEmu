package com.multiprofileemu.domain

import com.multiprofileemu.fs.DesktopFileSystemAdapter
import com.multiprofileemu.model.SaveGroup
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProfileSwapEngineTest {
    private val fs = DesktopFileSystemAdapter()

    @Test
    fun switchProfile_backsUpOutgoingAndRestoresIncomingWithoutDataLoss() {
        val root = Files.createTempDirectory("multiprofileemu-test").toFile()
        try {
            val activeDir = File(root, "active").apply { mkdirs() }
            File(activeDir, "save.dat").writeText("dad-progress")

            val storage = ProfileStorageResolver(File(root, "profiles").path)
            val engine = ProfileSwapEngine(fs, storage)

            var group = SaveGroup(id = "g1", name = "Test Game", activeFolderPath = activeDir.path)

            // First-use: capture whatever is already in the active folder as "Dad".
            group = engine.createProfileFromActive(group, "Dad")
            val dadId = group.activeProfileId!!
            assertEquals(1, group.profiles.size)

            // A sibling starts a brand-new, empty save.
            group = engine.createEmptyProfile(group, "Kid")
            val kidId = group.profiles.first { it.name == "Kid" }.id

            // Switching to Kid must back up Dad's content before clearing the active folder.
            group = engine.switchProfile(group, kidId)
            assertEquals(kidId, group.activeProfileId)
            assertFalse(File(activeDir, "save.dat").exists())
            assertEquals("dad-progress", File(root, "profiles/g1/$dadId/save.dat").readText())

            // Kid plays and writes their own save while active.
            File(activeDir, "save.dat").writeText("kid-progress")

            // Switching back to Dad must back up Kid's new progress and restore Dad's untouched.
            group = engine.switchProfile(group, dadId)
            assertEquals(dadId, group.activeProfileId)
            assertEquals("dad-progress", File(activeDir, "save.dat").readText())
            assertEquals("kid-progress", File(root, "profiles/g1/$kidId/save.dat").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
