package com.fluxx.android.ui

import com.fluxx.android.ProjectManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * MP1.2 A2 (Snxw's approved exception, this pass only): Project 1 loaded through the app's own loader
 * ([ProjectManager.loadProject], read-only) and played in-process from the start to the end through the normal preview.
 * The loaded document lives in this test's editor only: the host has no save path (no autosave, no Save), and nothing
 * here writes, renames or exports it. The file's size and mtime are checked before and after (and by adb stat outside).
 */
class Mp12Project1Test : Mp12Base() {
    @Test fun project1Playthrough() {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP12: Project 1 not found; skipping", file.isFile)
        assumeTrue("MP12: Project 1 has a pending atomic-save file (.bak / .new); not touching it", !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        val before = file.length() to file.lastModified()
        say("Project 1 before: size ${before.first} mtime ${before.second}")
        val project = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
        host(project)
        Thread.sleep(3000)
        val limit = (project.composition.resolvedDurationUs / 1000 * 2 + 60_000).coerceAtMost(25 * 60_000L)
        try { playThrough("Project 1", project, limit) }
        finally {
            val after = file.length() to file.lastModified()
            say("Project 1 after: size ${after.first} mtime ${after.second} -> ${if (after == before) "UNCHANGED" else "CHANGED"}")
            check(after == before) { "Project 1 changed on disk" }
        }
    }
}
