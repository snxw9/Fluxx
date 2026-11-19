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
    /** TEXT PERF 11 keeps the destination in AppNavigation; the 11a (before) build kept it in an Activity field. */
    private fun destinationOf(activity: MainActivity): Any? = runCatching {
        ViewModelProvider(activity)[AppNavigation::class.java].destination
    }.getOrElse { destinationState(activity).value }
    private fun openEditor(activity: MainActivity) {
        runCatching { ViewModelProvider(activity)[AppNavigation::class.java].destination = AppDestination.Editor(null) }
            .getOrElse { destinationState(activity).value = AppDestination.Editor(null) }
    }
    private fun screenshot(name: String) {
        val label = InstrumentationRegistry.getArguments().getString("textRunLabel") ?: return
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "text/$label").also { it.mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { java.io.File(directory, "perf11-editor-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun editorOf(activity: MainActivity) = ViewModelProvider(activity)[EditorViewModel::class.java]

    private class Observed(val identity: Int, val destination: Any?, val hasDocument: Boolean)
    private fun observe(scenario: ActivityScenario<MainActivity>): Observed {
        var result: Observed? = null
        scenario.onActivity { activity ->
            result = Observed(System.identityHashCode(activity), destinationOf(activity),
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
                    openEditor(activity)
                }
                settle()
                val opened = observe(scenario); screenshot("1-portrait")
                assertTrue("Editor did not open", opened.destination is AppDestination.Editor && opened.hasDocument)

                shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 1"); settle()
                val rotated = observe(scenario); screenshot("2-landscape")
                report.append("rotation: recreated=${rotated.identity != opened.identity} destination=${rotated.destination} document=${rotated.hasDocument}; ")
                shell("settings put system user_rotation $rotation"); settle()

                shell("wm size 1080x1500"); settle()
                val resized = observe(scenario); screenshot("3-resized-1080x1500")
                report.append("resize: recreated=${resized.identity != opened.identity} destination=${resized.destination} document=${resized.hasDocument}; ")
                shell("wm size reset"); settle()

                scenario.recreate(); settle()
                val recreated = observe(scenario); screenshot("4-recreated")
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

    /**
     * TEXT PERF 11 Part 2 in the real app: MainActivity's own preview (the controller the Play button drives) plays a
     * 30-layer size animation; Home (KEYCODE_HOME) during playback; 4 s in the background; return to the task; Play
     * again. Samples the editor playhead (what the timeline shows) every 10 ms with wall time, and writes the cadence.
     * `-e lifecycleLayout stacked|spaced` picks the fixture layout.
     */
    @Test fun homeDuringPlaybackInTheEditor() {
        val context = instrumentation.targetContext
        val label = InstrumentationRegistry.getArguments().getString("textRunLabel") ?: "editor-lifecycle"
        val stacked = InstrumentationRegistry.getArguments().getString("lifecycleLayout") != "spaced"
        val imported = InstrumentationRegistry.getArguments().getString("lifecycleFont") == "variable"
        val library = com.fluxx.android.fonts.FontLibraryRepository.getInstance(context)
        var added: String? = null
        val font = if (!imported) null else kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val file = java.io.File(context.cacheDir, "perf11-lifecycle-font.otf")
            instrumentation.context.assets.open("SourceSerif4Variable-Roman.otf").use { input -> file.outputStream().use { input.copyTo(it) } }
            library.load()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val entry = library.state.value.find { it.sha256 == digest } ?: library.import(android.net.Uri.fromFile(file)).also { added = it.sha256 }
            file.delete()
            entry.faces.single().reference(entry.sha256, requested = mapOf("wght" to 400f, "opsz" to 20f))
        }
        val project = com.fluxx.android.render.TextMultiLayerFixture.project(context, 30, true,
            com.fluxx.android.render.TextMultiLayerFixture.Kind.SIZE, stacked, font)
        class Sample(val ms: Long, val us: Long, val playing: Boolean)
        val report = org.json.JSONObject().put("stacked", stacked).put("importedFont", imported)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun previewOf(activity: MainActivity): com.fluxx.android.render.PreviewController? {
                val field = MainActivity::class.java.getDeclaredField("preview\$delegate").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") return (field.get(activity) as androidx.compose.runtime.State<com.fluxx.android.render.PreviewController?>).value
            }
            fun sample(ms: Long): List<Sample> {
                val out = ArrayList<Sample>(); val start = android.os.SystemClock.uptimeMillis()
                while (android.os.SystemClock.uptimeMillis() - start < ms) {
                    instrumentation.runOnMainSync {
                        val activity = currentActivity() ?: return@runOnMainSync
                        val preview = previewOf(activity)
                        out.add(Sample(android.os.SystemClock.uptimeMillis() - start, editorOf(activity).state.playheadUs, preview?.state?.value?.playing == true))
                    }
                    Thread.sleep(10)
                }
                return out
            }
            fun cadence(samples: List<Sample>): org.json.JSONObject {
                val changes = samples.zipWithNext().filter { (a, b) -> b.us != a.us }
                val jumps = changes.map { (a, b) -> b.us - a.us }
                val stalls = samples.zipWithNext().fold(Pair(0L, 0L)) { (best, run), (a, b) ->
                    val next = if (b.us == a.us && b.playing) run + (b.ms - a.ms) else 0L; Pair(maxOf(best, next), next) }.first
                return org.json.JSONObject().put("updates", changes.size).put("firstUs", samples.firstOrNull()?.us ?: -1)
                    .put("lastUs", samples.lastOrNull()?.us ?: -1).put("maxJumpUs", jumps.maxOrNull() ?: 0)
                    .put("jumpsOver150ms", jumps.count { it > 150_000 }).put("longestStallMs", stalls)
                    .put("playingAtEnd", samples.lastOrNull()?.playing ?: false)
                    .put("trace", org.json.JSONArray(samples.filterIndexed { i, s -> i == 0 || s.us != samples[i - 1].us }.take(300).map { "${it.ms}:${it.us / 1000}" }))
            }
            scenario.onActivity { activity -> editorOf(activity).dispatch(EditorAction.Load(project)); openEditor(activity) }
            val until = android.os.SystemClock.uptimeMillis() + 20_000
            var ready = false
            while (!ready && android.os.SystemClock.uptimeMillis() < until) { scenario.onActivity { ready = previewOf(it) != null }; Thread.sleep(50) }
            Thread.sleep(3_000) // First frame and tier settle.
            scenario.onActivity { previewOf(it)?.play() }
            report.put("beforeHome", cadence(sample(2_500)))
            shell("input keyevent KEYCODE_HOME")
            val stopped = sample(4_000)
            report.put("whileStopped", org.json.JSONObject().put("firstUs", stopped.firstOrNull()?.us ?: -1).put("lastUs", stopped.lastOrNull()?.us ?: -1))
            context.startActivity(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            // -e returnPlayDelayMs 0: Play as soon as the returned editor has its preview controller (the cold renderer
            // is still drawing its first frame), as a user who taps Play straight away does.
            val playDelay = (InstrumentationRegistry.getArguments().getString("returnPlayDelayMs") ?: "2000").toLong()
            report.put("returnPlayDelayMs", playDelay)
            if (playDelay > 0) report.put("afterReturnIdle", cadence(sample(playDelay)))
            else { val wait = android.os.SystemClock.uptimeMillis() + 10_000; var has = false
                while (!has && android.os.SystemClock.uptimeMillis() < wait) { instrumentation.runOnMainSync { has = currentActivity()?.let { previewOf(it) } != null }; Thread.sleep(5) } }
            instrumentation.runOnMainSync { currentActivity()?.let { previewOf(it)?.play() } }
            report.put("afterReturnPlay", cadence(sample(4_000)))
            scenario.onActivity { previewOf(it)?.pause() }
        } } finally { added?.let { hash -> kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { runCatching { library.delete(hash) } } } }
        val directory = java.io.File(context.getExternalFilesDir(null), "text/$label").also { it.mkdirs() }
        java.io.File(directory, "perf11-editor-home-${if (stacked) "stacked" else "spaced"}${if (imported) "-imported" else ""}-delay${report.optLong("returnPlayDelayMs")}.json").writeText(report.toString(2))
        android.util.Log.i("FluxxPerf11", "editor home: ${report.toString().take(3000)}")
    }
    private fun currentActivity(): MainActivity? =
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            ?: androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.STOPPED).filterIsInstance<MainActivity>().firstOrNull()
}
