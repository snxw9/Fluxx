package com.fluxx.android.model

data class Marker(val id: Long, val timeUs: Long, val colorArgb: Int = 0xFFFF9800.toInt(),
    val description: String = "") {
    init {
        require(id > 0)
        require(description.length <= 128 && '\n' !in description && '\r' !in description)
    }
}

/** Marker identity is scoped to its composition or owning layer. Copies retain local IDs. */
object Markers {
    fun validate(markers: List<Marker>, rate: FrameRate? = null, anchor: Long = 0) {
        require(markers.map { it.id }.toSet().size == markers.size) { "Duplicate marker ID" }
        var previousTime: Long? = null
        var previousFrame: Long? = null
        for (marker in markers) {
            require(previousTime == null || previousTime < marker.timeUs) { "Markers must be sorted" }
            val time = Math.addExact(anchor, marker.timeUs)
            // Negative preserved content times cannot be visited, but still occupy distinct frames.
            val frame = rate?.let { signedFrame(it, time) }
            require(frame == null || previousFrame == null || previousFrame < frame) { "Duplicate marker frame" }
            previousTime = marker.timeUs
            previousFrame = frame
        }
    }

    fun signedFrame(rate: FrameRate, time: Long): Long =
        if (time >= 0) rate.nearestFrame(time) else -rate.nearestFrame(Math.negateExact(time))

    /** Returns an existing object; no collection, iterator, or boxed colour is allocated. */
    fun atFrame(markers: List<Marker>, anchor: Long, time: Long, rate: FrameRate): Marker? {
        val frame = rate.nearestFrame(time)
        var low = 0
        var high = markers.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val marker = markers[mid]
            val markerTime = Math.addExact(anchor, marker.timeUs)
            // Preserved negative content must never resolve to a seekable frame-zero marker.
            if (markerTime < 0) { low = mid + 1; continue }
            val candidate = rate.nearestFrame(markerTime)
            when {
                candidate < frame -> low = mid + 1
                candidate > frame -> high = mid - 1
                else -> return marker
            }
        }
        return null
    }

    fun active(project: ProjectDocument, layer: CompositionLayer?, time: Long): Marker? {
        val comp = project.composition
        if (layer != null) atFrame(layer.markers, layer.resolvedKeyframeAnchorUs, time, comp.frameRate)?.let { return it }
        return atFrame(comp.markers, 0, time, comp.frameRate)
    }

    fun upsert(markers: List<Marker>, marker: Marker, anchor: Long, rate: FrameRate): FrozenList<Marker> {
        val frame = signedFrame(rate, Math.addExact(anchor, marker.timeUs))
        val existing = markers.firstOrNull { signedFrame(rate, Math.addExact(anchor, it.timeUs)) == frame }
        val replacement = marker.copy(id = existing?.id ?: marker.id)
        return FrozenList.of((markers.filterNot { it.id == replacement.id || it.id == marker.id } + replacement).sortedBy { it.timeUs })
    }

    fun normalize(markers: List<Marker>, anchor: Long, rate: FrameRate): FrozenList<Marker> =
        FrozenList.of(markers.sortedBy { it.timeUs }.distinctBy { signedFrame(rate, Math.addExact(anchor, it.timeUs)) })
}
