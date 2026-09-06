package com.multiprofileemu.app

import com.multiprofileemu.fs.DesktopFileSystemAdapter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppHomeManagerTest {
    private val fs = DesktopFileSystemAdapter()

    @Test
    fun migrateHomeTo_copiesExistingDataAndPointsAtNewLocation() {
        val root = Files.createTempDirectory("multiprofileemu-home-test").toFile()
        try {
            val anchor = File(root, "anchor").apply { mkdirs() }
            val oldHome = File(root, "old-home").apply { mkdirs() }
            File(oldHome, "config.json").writeText("""{"fake":"config"}""")

            val manager = AppHomeManager(fs, anchorDir = anchor.path, defaultHomeDir = oldHome.path)
            assertTrue(manager.isFirstRun())

            manager.setHomeDir(oldHome.path)
            assertFalse(manager.isFirstRun())
            assertEquals(oldHome.path, manager.currentHomeDir())

            val newHome = File(root, "external-drive/new-home")
            manager.migrateHomeTo(newHome.path)

            assertEquals(newHome.path, manager.currentHomeDir())
            assertEquals("""{"fake":"config"}""", File(newHome, "config.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
