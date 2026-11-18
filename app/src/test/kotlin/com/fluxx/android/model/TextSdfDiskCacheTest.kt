package com.fluxx.android.model

import com.fluxx.android.fonts.SdfDiskCache
import java.io.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextSdfDiskCacheTest {
    @get:Rule val folder=TemporaryFolder()
    private val font="user."+"a".repeat(64)
    private fun packet(glyph: Int): ByteArray = ByteArrayOutputStream().also {stream -> DataOutputStream(stream).use {out ->
        fun string(s: String) {val b=s.toByteArray();out.writeInt(b.size);out.write(b)}
        out.writeInt(0x46545031);out.writeInt(1);string("fluxx.sans")
        out.writeInt(1000);out.writeInt(800);out.writeInt(-200);out.writeInt(0)
        out.writeDouble(800.0);out.writeDouble(-200.0);out.writeDouble(0.0)
        repeat(5) {out.writeInt(0)} // fonts, lines, glyphs, characters, clusters
        out.writeInt(1);string(font);out.writeInt(glyph);out.writeInt(1)
        out.writeInt(2);out.writeInt(2);out.writeInt(0);out.writeInt(2);out.writeInt(4);out.write(byteArrayOf(1,2,3,4))
    }}.toByteArray()
    @Test fun individualGlyphsSurviveRecreationAndCorruptionIsADisposableMiss() {
        val directory=folder.newFolder();SdfDiskCache(directory).storePacket(packet(7))
        val cache=SdfDiskCache(directory);val seeds=cache.seeds(setOf(font))
        DataInputStream(ByteArrayInputStream(seeds)).use {input -> assertEquals(0x46534331,input.readInt());assertEquals(1,input.readInt())}
        assertTrue(cache.seeds(setOf("user."+"b".repeat(64))).isEmpty())
        val file=directory.listFiles()!!.single();val bytes=file.readBytes();bytes[bytes.lastIndex]=99;file.writeBytes(bytes)
        assertTrue(cache.seeds(setOf(font)).isEmpty());assertFalse(file.exists())
        cache.storePacket(packet(7));assertFalse(cache.seeds(setOf(font)).isEmpty())
    }
    @Test fun capEvictsLeastRecentlyUsedGlyphWithoutChangingIdentityOrPixels() {
        val directory=folder.newFolder();val first=packet(1)
        SdfDiskCache(directory).storePacket(first);val old=directory.listFiles()!!.single();val cap=old.length()+1
        val cache=SdfDiskCache(directory,cap);old.setLastModified(1)
        cache.storePacket(packet(2))
        assertFalse(old.exists());assertTrue(directory.listFiles()!!.sumOf {it.length()}<=cap)
        val seeds=cache.seeds(setOf(font));DataInputStream(ByteArrayInputStream(seeds)).use {input ->
            input.readInt();assertEquals(1,input.readInt());val identity=ByteArray(input.readInt());input.readFully(identity)
            assertEquals(font,identity.toString(Charsets.UTF_8));assertEquals(2,input.readInt())
        }
    }
    @Test fun malformedPacketCannotPublishAnUnboundedGlyph() {
        val cache=SdfDiskCache(folder.newFolder())
        assertThrows(IllegalArgumentException::class.java) {cache.storePacket(ByteArray(40))}
        assertTrue(cache.seeds(setOf(font)).isEmpty())
    }
    @Test fun keyCarriesTheGeneratorVersionAndOlderBitmapsAreNeverReused() {
        // TEXT FIX 9b: FreeType SDFs with overlap holes were cached as v1; the coverage-correct generator is v2.
        assertEquals(2,SdfDiskCache.GENERATOR)
        val native=File("../core-engine/src/main/cpp/text/sdf_coverage.h").readText()
        assertTrue("Kotlin and native generator versions agree",native.contains("constexpr unsigned GeneratorVersion = ${SdfDiskCache.GENERATOR};"))
        assertEquals("v2-48-8-$font-7.sdf",SdfDiskCache.key(font,7))
        val directory=folder.newFolder()
        // A v1 bitmap left by an older build is a valid record with the old name: it must not seed and must be deleted.
        SdfDiskCache(directory).storePacket(packet(7));val current=directory.listFiles()!!.single()
        val stale=File(directory,"v1-48-8-$font-7.sdf");current.copyTo(stale);current.delete()
        val cache=SdfDiskCache(directory)
        assertTrue(cache.seeds(setOf(font)).isEmpty());assertFalse(stale.exists())
        cache.storePacket(packet(7));assertEquals(listOf(SdfDiskCache.key(font,7)),directory.listFiles()!!.map {it.name})
    }
}
