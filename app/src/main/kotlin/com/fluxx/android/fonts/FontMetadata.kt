package com.fluxx.android.fonts

import com.fluxx.android.model.*
import org.json.JSONArray
import org.json.JSONObject

data class FontAxisMetadata(val tag: String,val name: String,val minimum: Float,val default: Float,val maximum: Float) {
    init {
        require(tag.length==4 && tag.all { it.code in 32..126 })
        require(name.length<=128 && '\u0000' !in name)
        require(minimum.isFinite() && default.isFinite() && maximum.isFinite() && minimum<=default && default<=maximum)
    }
}
data class FontNamedInstance(val index: Int,val name: String,val values: List<Float>) {
    init { require(index in 0..255 && name.length<=128 && '\u0000' !in name && values.all { it.isFinite() }) }
}
data class FontFaceMetadata(val index: Int,val family: String,val style: String,val postScriptName: String,
    val weight: Int,val width: Int,val slant: Float,val axes: List<FontAxisMetadata>,val instances: List<FontNamedInstance>,val preview: String="Aa AV office") {
    init {
        require(index in 0..255)
        require(listOf(family,style,postScriptName).all { it.isNotBlank() && it.length<=128 && '\u0000' !in it })
        require(weight in 1..1000 && width in 1..9 && slant.isFinite())
        require(preview.isNotEmpty() && preview.length<=64);TextLimits.validateSource(preview)
        require(axes.size<=16 && axes.map { it.tag }.distinct().size==axes.size)
        require(instances.size<=256 && instances.map { it.index }.distinct().size==instances.size)
        for(instance in instances) {
            require(instance.values.size==axes.size)
            instance.values.forEachIndexed { a,value -> require(value in axes[a].minimum..axes[a].maximum) }
        }
    }
    /** A preset selection replaces custom coordinates atomically, including all axes. */
    fun selectInstance(current: TextFontRef,instance: Int): TextFontRef {
        require(current.faceIndex==index) { "Named instance belongs to a different face" }
        // Choosing a preset replaces every custom coordinate in one Source Text edit.
        return reference(requireNotNull(current.sha256),instance)
    }

    /** Coordinates are immutable/hold evaluated, clamped before both HB and FT preparation. */
    fun reference(hash: String,instance: Int=-1,requested: Map<String,Float> = emptyMap()): TextFontRef {
        require(requested.keys.all { tag -> axes.any { it.tag==tag } }) { "Unknown variable-font axis" }
        require(requested.values.all { it.isFinite() }) { "Variable-font axes must be finite" }
        val selected=if(instance<0) null else requireNotNull(instances.find { it.index==instance }) { "Unknown named instance" }
        val coordinates=axes.mapIndexed { a,axis ->
            val value=requested[axis.tag] ?: selected?.values?.get(a) ?: axis.default
            TextFontAxis(axis.tag,AnimatableProperty1D(staticValue=value.coerceIn(axis.minimum,axis.maximum)))
        }
        return TextFontRef(family=family,sha256=hash,faceIndex=index,namedInstance=instance,
            axes=FrozenList.of(coordinates),postScriptName=postScriptName,styleName=selected?.name ?: style)
    }
}
data class FontLibraryEntry(val sha256: String,val bytes: Long,val faces: List<FontFaceMetadata>) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..FontWorkerService.MAX_FONT_BYTES.toLong())
        require(faces.isNotEmpty() && faces.size<=256 && faces.map { it.index }.distinct().size==faces.size)
    }
}

/** Metadata only: does not open fonts or load Android Typefaces. */
internal object FontMetadataCodec {
    const val MAX_INDEX_BYTES=8*1024*1024
    fun inspect(json: String): List<FontFaceMetadata> {
        require(json.toByteArray(Charsets.UTF_8).size<=FontWorkerService.MAX_METADATA_BYTES)
        val root=JSONObject(json);require(root.getInt("version")==1) { "Unsupported font metadata version" }
        return decodeFaces(root.getJSONArray("faces"))
    }
    fun decode(bytes: ByteArray): List<FontLibraryEntry> {
        require(bytes.size<=MAX_INDEX_BYTES)
        val root=JSONObject(bytes.toString(Charsets.UTF_8));require(root.getInt("version")==1) { "Unsupported font library version" }
        val rows=root.getJSONArray("fonts");require(rows.length()<=256)
        return List(rows.length()) { i -> val row=rows.getJSONObject(i)
            FontLibraryEntry(row.getString("sha256"),row.getLong("bytes"),decodeFaces(row.getJSONArray("faces")))
        }.also { entries -> require(entries.map { it.sha256 }.distinct().size==entries.size);require(entries.sumOf { it.bytes }<=256L*1024*1024) }
    }
    fun encode(entries: List<FontLibraryEntry>): ByteArray {
        require(entries.size<=256 && entries.sumOf { it.bytes }<=256L*1024*1024)
        val rows=JSONArray()
        for(entry in entries) rows.put(JSONObject().put("sha256",entry.sha256).put("bytes",entry.bytes).put("faces",encodeFaces(entry.faces)))
        return JSONObject().put("version",1).put("fonts",rows).toString().toByteArray(Charsets.UTF_8).also { require(it.size<=MAX_INDEX_BYTES) }
    }
    private fun decodeFaces(rows: JSONArray): List<FontFaceMetadata> {
        require(rows.length() in 1..256)
        return List(rows.length()) { i ->
            val row=rows.getJSONObject(i);val axes=row.getJSONArray("axes");val instances=row.getJSONArray("instances")
            require(axes.length()<=16 && instances.length()<=256)
            FontFaceMetadata(row.getInt("index"),row.getString("family"),row.getString("style"),row.getString("postscript"),
                row.getInt("weight"),row.getInt("width"),row.getDouble("slant").toFloat(),
                List(axes.length()) { a -> val axis=axes.getJSONObject(a)
                    FontAxisMetadata(axis.getString("tag"),axis.getString("name"),axis.getDouble("min").toFloat(),axis.getDouble("default").toFloat(),axis.getDouble("max").toFloat()) },
                List(instances.length()) { n -> val instance=instances.getJSONObject(n);val values=instance.getJSONArray("values");require(values.length()<=16)
                    FontNamedInstance(instance.getInt("index"),instance.getString("name"),List(values.length()) { a -> values.getDouble(a).toFloat() }) },row.optString("preview","Aa AV office"))
        }.also { require(it.map { face -> face.index }.distinct().size==it.size) }
    }
    private fun encodeFaces(faces: List<FontFaceMetadata>): JSONArray=JSONArray().apply {
        for(face in faces) put(JSONObject().put("index",face.index).put("family",face.family).put("style",face.style)
            .put("postscript",face.postScriptName).put("weight",face.weight).put("width",face.width).put("slant",face.slant).put("preview",face.preview)
            .put("axes",JSONArray().apply { for(axis in face.axes) put(JSONObject().put("tag",axis.tag).put("name",axis.name)
                .put("min",axis.minimum).put("default",axis.default).put("max",axis.maximum)) })
            .put("instances",JSONArray().apply { for(instance in face.instances) put(JSONObject().put("index",instance.index).put("name",instance.name)
                .put("values",JSONArray(instance.values))) }))
    }
}
