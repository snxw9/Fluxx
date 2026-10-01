// FlatBuffers table binding for project.fbs (additive v7 fields).
package fluxx.schema

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer

class Marker : Table() {
    fun __assign(position: Int, buffer: ByteBuffer): Marker { __reset(position, buffer); return this }
    val id: ULong get() { val o = __offset(4); return if (o != 0) bb.getLong(o + bb_pos).toULong() else 0UL }
    val timeUs: Long get() { val o = __offset(6); return if (o != 0) bb.getLong(o + bb_pos) else 0L }
    val colorArgb: Int get() { val o = __offset(8); return if (o != 0) bb.getInt(o + bb_pos) else -26624 }
    val description: String? get() { val o = __offset(10); return if (o != 0) __string(o + bb_pos) else null }
    companion object {
        fun createMarker(b: FlatBufferBuilder, id: ULong, timeUs: Long, colorArgb: Int, description: Int): Int {
            b.startTable(4)
            b.addLong(0, id.toLong(), 0L)
            b.addLong(1, timeUs, 0L)
            b.addInt(2, colorArgb, -26624)
            b.addOffset(3, description, 0)
            return b.endTable()
        }
    }
}
