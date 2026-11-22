package com.fluxx.android

import com.fluxx.android.AppNavigation.Companion.Restore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** TEXT PERF 11 Part 3: recreation never closes the open project; process death reopens its last autosave. */
class AppNavigationTest {
    private val file = File("/data/user/0/com.fluxx.android/files/projects/Demo.fluxx").absoluteFile

    @Test fun editorSavesItsProjectFile() {
        assertEquals("editor" to file.absolutePath, AppNavigation.save(AppDestination.Editor(file)))
        assertEquals("editor" to null, AppNavigation.save(AppDestination.Editor(null)))
        assertEquals("shell" to null, AppNavigation.save(AppDestination.Shell))
        assertEquals("settings" to null, AppNavigation.save(AppDestination.Settings))
    }

    @Test fun survivingViewModelIsAuthoritative() {
        // Configuration recreation (rotation without configChanges, dark mode, locale): the ViewModel still holds the
        // destination and the unsaved document, whatever the saved state says.
        for ((saved, path) in listOf("editor" to file.absolutePath, "editor" to null, "shell" to null, "settings" to null, null to null))
            assertEquals(Restore.Keep, AppNavigation.restore(false, saved, path))
    }

    @Test fun newProcessReopensTheSavedProject() {
        assertEquals(Restore.Reopen(file), AppNavigation.restore(true, "editor", file.absolutePath))
        assertEquals(Restore.Show(AppDestination.Settings), AppNavigation.restore(true, "settings", null))
        // Nothing on disk to restore, a shell, or a first launch.
        assertEquals(Restore.Show(AppDestination.Shell), AppNavigation.restore(true, "editor", null))
        assertEquals(Restore.Show(AppDestination.Shell), AppNavigation.restore(true, "shell", null))
        assertEquals(Restore.Show(AppDestination.Shell), AppNavigation.restore(true, null, null))
    }

    @Test fun roundTripThroughSavedState() {
        for (destination in listOf(AppDestination.Editor(file), AppDestination.Settings, AppDestination.Shell)) {
            val (key, path) = AppNavigation.save(destination)
            val expected = if (destination is AppDestination.Editor) Restore.Reopen(file) else Restore.Show(destination)
            assertEquals(expected, AppNavigation.restore(true, key, path))
        }
    }
}
