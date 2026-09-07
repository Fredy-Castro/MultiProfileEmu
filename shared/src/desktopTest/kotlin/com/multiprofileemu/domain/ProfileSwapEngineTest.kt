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

    /**
     * Real-world emulator setups (e.g. Flatpak installs on Bazzite/SteamOS via EmuDeck) often
     * point the active save folder at a symlink rather than a plain directory. Regression test
     * for a real data-loss bug found on hardware: `Files.walk()` without `FOLLOW_LINKS` treats a
     * symlinked root as an opaque leaf and never descends into it, so the outgoing profile's
     * backup silently copied zero files right before the real files (at the symlink target) were
     * wiped by clearDirectoryContents — which, using java.io.File, does resolve the symlink.
     */
    @Test
    fun switchProfile_backsUpOutgoingWhenActiveFolderIsASymlink() {
        val root = Files.createTempDirectory("multiprofileemu-test").toFile()
        try {
            val realActiveDir = File(root, "real-active").apply { mkdirs() }
            File(realActiveDir, "save.dat").writeText("dad-progress")
            val activeLink = File(root, "active-link")
            try {
                Files.createSymbolicLink(activeLink.toPath(), realActiveDir.toPath())
            } catch (e: java.nio.file.FileSystemException) {
                // Creating symlinks needs a privilege this machine's account doesn't have (e.g.
                // Windows without Developer Mode/admin) — nothing to do with the bug being tested,
                // so skip rather than fail. Runs for real on any CI/dev box with real symlink
                // support (every Linux/macOS box, and Windows with Developer Mode on).
                org.junit.Assume.assumeNoException("symlinks unsupported on this machine", e)
                return
            }

            val storage = ProfileStorageResolver(File(root, "profiles").path)
            val engine = ProfileSwapEngine(fs, storage)

            var group = SaveGroup(id = "g1", name = "Test Game", activeFolderPath = activeLink.path)
            group = engine.createProfileFromActive(group, "Dad")
            val dadId = group.activeProfileId!!
            group = engine.createEmptyProfile(group, "Kid")
            val kidId = group.profiles.first { it.name == "Kid" }.id

            group = engine.switchProfile(group, kidId)

            // The real bug: this file existed at the symlink target and should have been backed
            // up into Dad's storage before the active folder was cleared for Kid.
            assertEquals("dad-progress", File(root, "profiles/g1/$dadId/save.dat").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
