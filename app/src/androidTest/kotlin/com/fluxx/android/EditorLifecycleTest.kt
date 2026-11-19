package com.fluxx.android

import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEXT PERF 11 Part 3: rotation, a window resize and an activity recreation must not close the open project. The
 * editor is entered with an in-memory document (no project file is created, opened or saved), so no user project is
 * touched. Rotation uses `settings put system user_rotation`, resize uses `wm size`; both are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EditorLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().readText().trim()
        }
    private val document = ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 3_000_000,
        layers = FrozenList.of(listOf(CompositionLayer(4242, LayerType.TEXT, timing = ClipTiming(durationUs = 3_000_000),
            text = TextProperties(source = AnimatableString(staticValue = "Lifecycle AV gy"), size = AnimatableProperty1D(staticValue = 90f)))))))

    private fun destinationState(activity: MainActivity): MutableState<Any?> {
        val field = MainActivity::class.java.getDeclaredField("currentDestination\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") return field.get(activity) as MutableState<Any?>
    }
    private fun editorOf(activity: MainActivity) = ViewModelProvider(activity)[EditorViewModel::class.java]

    private class Observed(val identity: Int, val destination: Any?, val hasDocument: Boolean)
    private fun observe(scenario: ActivityScenario<MainActivity>): Observed {
        var result: Observed? = null
        scenario.onActivity { activity ->
            result = Observed(System.identityHashCode(activity), destinationState(activity).value,
                editorOf(activity).state.project.composition.layers.any { it.id == 4242L })
        }
        return requireNotNull(result)
    }
    private fun settle() { instrumentation.waitForIdleSync(); Thread.sleep(2_500); instrumentation.waitForIdleSync() }

    @Test fun rotationResizeAndRecreationKeepTheProjectOpen() {
        val rotation = shell("settings get system user_rotation"); val accelerometer = shell("settings get system accelerometer_rotation")
        val report = StringBuilder()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    editorOf(activity).dispatch(EditorAction.Load(document))
                    destinationState(activity).value = AppDestination.Editor(null)
                }
                settle()
                val opened = observe(scenario)
                assertTrue("Editor did not open", opened.destination is AppDestination.Editor && opened.hasDocument)

                shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 1"); settle()
                val rotated = observe(scenario)
                report.append("rotation: recreated=${rotated.identity != opened.identity} destination=${rotated.destination} document=${rotated.hasDocument}; ")
                shell("settings put system user_rotation $rotation"); settle()

                shell("wm size 1080x1500"); settle()
                val resized = observe(scenario)
                report.append("resize: recreated=${resized.identity != opened.identity} destination=${resized.destination} document=${resized.hasDocument}; ")
                shell("wm size reset"); settle()

                scenario.recreate(); settle()
                val recreated = observe(scenario)
                report.append("recreate: destination=${recreated.destination} document=${recreated.hasDocument}")
                android.util.Log.i("FluxxPerf11", "editor lifecycle: $report")

                assertTrue("Rotation closed the project: $report", rotated.destination is AppDestination.Editor && rotated.hasDocument)
                assertTrue("Resize closed the project: $report", resized.destination is AppDestination.Editor && resized.hasDocument)
                assertTrue("Recreation closed the project: $report", recreated.destination is AppDestination.Editor && recreated.hasDocument)
            } finally {
                shell("settings put system user_rotation $rotation"); shell("settings put system accelerometer_rotation $accelerometer"); shell("wm size reset")
            }
        }
    }
}
