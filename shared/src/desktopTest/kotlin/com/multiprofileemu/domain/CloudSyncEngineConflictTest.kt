package com.multiprofileemu.domain

import com.multiprofileemu.cloud.DesktopRcloneRunner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises CloudSyncEngine's conflict detection/resolution against the real `rclone` binary —
 * this project never reimplements rclone's own logic, so the only trustworthy way to verify
 * `--conflict-resolve none` actually behaves the way [CloudSyncEngine.findConflicts]/
 * [CloudSyncEngine.resolveConflict] assume is to run it for real. Uses two plain local
 * directories standing in for "local" and "cloud" so no Drive remote/credentials are needed —
 * rclone bisync treats two bare filesystem paths exactly like it treats a local path and a
 * configured remote. Skips (rather than fails) on a machine without rclone installed.
 */
class CloudSyncEngineConflictTest {
    private val runner = DesktopRcloneRunner(
        configPath = Files.createTempFile("mpe-rclone-test", ".conf").toString(),
    )
    private val engine = CloudSyncEngine(runner)

    @Test
    fun findConflicts_and_resolveConflict_roundTrip() = runBlocking {
        org.junit.Assume.assumeTrue("rclone no está instalado en esta máquina", runner.isAvailable())

        val root = Files.createTempDirectory("mpe-conflict-test").toFile()
        try {
            val local = File(root, "local").apply { mkdirs() }
            val cloud = File(root, "cloud").apply { mkdirs() }
            File(local, "save.dat").writeText("baseline")
            // rclone bisync refuses to run ("Safety abort: all files were changed") when literally
            // every file differs — a guard against e.g. an unmounted remote looking like "everything
            // was deleted". A second, never-touched file keeps this scenario realistic (most save
            // files untouched, one specific file conflicts) without needing --force.
            File(local, "other.dat").writeText("never touched")

            // Establish the bisync baseline (identical on both sides) — required before any
            // regular bisync run, same as CloudSyncEngine.sync() does for a brand-new pair.
            assertEquals(0, runner.run(bisyncArgs(local.path, cloud.path, resync = true), {}))
            assertEquals("baseline", File(cloud, "save.dat").readText())

            // Change the SAME file differently on both sides since the last run — a real conflict.
            File(local, "save.dat").writeText("local-change")
            File(cloud, "save.dat").writeText("cloud-change")

            assertEquals(0, runner.run(bisyncArgs(local.path, cloud.path, resync = false), {}))

            val conflicts = engine.findConflicts(local.path)
            assertEquals(1, conflicts.size)
            val conflict = conflicts.single()
            assertEquals("save.dat", conflict.relativePath)
            assertNotNull(conflict.localModified)
            assertNotNull(conflict.cloudModified)

            assertEquals(null, engine.resolveConflict(local.path, conflict, keepLocal = true))

            // Propagate: a normal follow-up bisync should push the resolved file and clean up the
            // leftover .local/.cloud copies on the other side too — no special-case code for this,
            // it's the same deletion-propagation bisync already does for any other file. --force
            // matches what AppController.resolveConflicts() passes for this exact follow-up call:
            // with only 3 files total, deleting the 2 leftover conflict copies trips rclone's own
            // ">50% deletes" safety abort, same as it would on a real, small save folder.
            assertEquals(0, runner.run(bisyncArgs(local.path, cloud.path, resync = false, force = true), {}))

            assertEquals("local-change", File(local, "save.dat").readText())
            assertEquals("local-change", File(cloud, "save.dat").readText())
            assertTrue(engine.findConflicts(local.path).isEmpty())
            for (dir in listOf(local, cloud)) {
                val leftoverConflictFiles = dir.listFiles { f -> f.name != "save.dat" && f.name != "other.dat" }
                assertTrue(leftoverConflictFiles.isNullOrEmpty(), "leftover files in $dir: ${leftoverConflictFiles?.map { it.name }}")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun bisyncArgs(localPath: String, cloudPath: String, resync: Boolean, force: Boolean = false): List<String> = buildList {
        add("bisync"); add(localPath); add(cloudPath)
        add("--conflict-resolve"); add("none")
        add("--conflict-suffix"); add("local,cloud")
        if (resync) add("--resync")
        if (force) add("--force")
    }
}
