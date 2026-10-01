package com.fluxx.android.model

import org.json.JSONArray
import org.json.JSONObject

internal object PaletteCodec {
    fun encode(library: PaletteLibrary): ByteArray {
        val rows=JSONArray()
        for(palette in library.palettes) {
            val colors=JSONArray()
            palette.swatches.forEach { colors.put(it.toHexString(true)) }
            rows.put(JSONObject().put("id",palette.id).put("name",palette.name ?: JSONObject.NULL).put("swatches",colors))
        }
        return JSONObject().put("version",1).put("activePaletteId",library.activePaletteId ?: JSONObject.NULL)
            .put("palettes",rows).toString().toByteArray(Charsets.UTF_8)
    }
    fun decode(bytes: ByteArray): PaletteLibrary {
        val json=JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version")==1) { "Unsupported palette file version" }
        val rows=json.getJSONArray("palettes")
        val palettes=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i)
            val colors=row.getJSONArray("swatches")
            ColorPalette(row.getString("id"),if(row.isNull("name")) null else row.getString("name").trim().ifBlank { null },
                (0 until colors.length()).map { index ->
                    val hex=colors.getString(index)
                    require(hex.length==8) { "Invalid palette ARGB colour" }
                    requireNotNull(FluxxColor.parseHex(hex)) { "Invalid palette colour" }
                })
        }
        require(palettes.map { it.id }.distinct().size==palettes.size) { "Duplicate palette IDs" }
        val active=json.optString("activePaletteId").takeIf { id -> palettes.any { it.id==id } } ?: palettes.firstOrNull()?.id
        return PaletteLibrary(palettes,active)
    }
}
