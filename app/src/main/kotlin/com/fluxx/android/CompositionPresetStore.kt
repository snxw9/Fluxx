package com.fluxx.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class CompositionPreset(val name: String, val width: Int, val height: Int, val fps: Int, val aspectRatioLabel: String) {
    init {
        require(name.isNotBlank() && width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 &&
            width.toLong() * height <= 1920L * 1080 && fps in 1..120) { "Invalid saved preset" }
        val aspect = aspectRatioLabel.split(':').map { it.toIntOrNull() }
        require(aspect.size == 2 && aspect.all { it != null && it > 0 }) { "Invalid preset aspect ratio" }
    }
}

/** First local key/value store in Fluxx; no database or second project index. */
class CompositionPresetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("composition_presets", Context.MODE_PRIVATE)
    suspend fun load(): List<CompositionPreset> = withContext(Dispatchers.IO) { lock.withLock { read() } }
    suspend fun save(preset: CompositionPreset): List<CompositionPreset> = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = read()
            require(preset.name.isNotBlank()) { "Enter a preset name" }
            require(values.none { it.name.equals(preset.name, ignoreCase = true) }) { "A preset with that name already exists" }
            write(values + preset)
        }
    }
    suspend fun delete(name: String): List<CompositionPreset> = withContext(Dispatchers.IO) {
        lock.withLock { write(read().filterNot { it.name == name }) }
    }
    private fun read(): List<CompositionPreset> {
        val array = JSONArray(preferences.getString("presets", "[]"))
        return List(array.length()) { index ->
            val value = array.getJSONObject(index)
            CompositionPreset(value.getString("name"), value.getInt("width"), value.getInt("height"),
                value.getInt("fps"), value.getString("aspectRatioLabel"))
        }
    }
    private fun write(values: List<CompositionPreset>): List<CompositionPreset> {
        val array = JSONArray()
        values.forEach { preset -> array.put(JSONObject().put("name", preset.name)
            .put("width", preset.width).put("height", preset.height).put("fps", preset.fps)
            .put("aspectRatioLabel", preset.aspectRatioLabel)) }
        check(preferences.edit().putString("presets", array.toString()).commit()) { "Could not save presets" }
        return values
    }
    companion object { private val lock = Mutex() }
}
