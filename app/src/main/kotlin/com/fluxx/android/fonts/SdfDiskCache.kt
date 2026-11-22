package com.fluxx.android.fonts

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Preparation-only, disposable, integrity-checked per-glyph cache. Exact user.* identities
 * hash font SHA-256, face, named instance and variation coordinates in FontLibraryRepository.
 * The name pins the SDF generator version, ppem, spread and overlap mode: a generator change (TEXT FIX 9b: coverage
 * rebuild of FreeType sign errors) can never re-seed bitmaps made by an older generator; those files are deleted.
 *
 * TEXT PERF 11: one shared instance per directory keeps a verified in-memory copy of the records. Before, every
 * imported-font preparation re-listed the directory, re-read and re-hashed every record of the font and touched each
 * file's timestamp (about half of a warm 400 ms request on the A16), then re-listed and re-stat'ed the whole directory
 * after storing. Now a font's records are read and verified once per process; new records are written to disk as
 * before (atomic, digest-prefixed) and the disk cap is enforced from a running total. Keys are unchanged: generator
 * version + ppem + spread (file prefix), exact font identity (font bytes, face, instance, coordinates) and glyph id.
 */
class SdfDiskCache(private val directory: File, private val cap: Long=64L*1024*1024, private val memoryCap: Long=16L*1024*1024) {
    companion object {
        /** Must equal the native coverage_sdf::GeneratorVersion. */
        const val GENERATOR=2
        const val PREFIX="v$GENERATOR-48-8"
        fun key(font: String,glyph: Int)="$PREFIX-$font-$glyph.sdf"
        /** Seeds sent with one preparation request (the worker's limit). */
        const val SEED_BYTES=8L*1024*1024
        private val shared=HashMap<String,SdfDiskCache>()
        /** The process-wide cache for [directory]. */
        fun shared(directory: File,cap: Long=64L*1024*1024,memoryCap: Long=16L*1024*1024): SdfDiskCache = synchronized(shared) {
            shared.getOrPut(directory.absolutePath) { SdfDiskCache(directory,cap,memoryCap) }
        }
    }
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun file(font: String,glyph: Int)=File(directory,key(font,glyph))
    private fun identity(font: String)=font.matches(Regex("user\\.[a-f0-9]{64}"))
    /** Verified records per font identity (access order: least recently used font first), glyph -> record bytes. */
    private val fonts=LinkedHashMap<String,LinkedHashMap<Int,ByteArray>>(16,.75f,true)
    private var memoryBytes=0L
    /** Fonts whose disk records have been read into [fonts] in this process. */
    private val loaded=HashSet<String>()
    private var staleDropped=false
    /** Bytes of .sdf files on disk, known after the first listing (-1 until then). */
    private var diskBytes=-1L
    /** Bitmaps from another generator version are never reused (they may carry FreeType's overlap holes). */
    private fun dropStale() {
        if(staleDropped) return
        staleDropped=true
        directory.listFiles().orEmpty().filter {it.name.endsWith(".sdf") && !it.name.startsWith("$PREFIX-")}.forEach {it.delete()}
    }
    /** Reads [font]'s verified records once per instance. [touch]: a seed use refreshes the files' disk recency. */
    private fun load(font: String,touch: Boolean) {
        if(!loaded.add(font)) return
        val records=fonts.getOrPut(font) { LinkedHashMap() }
        val files=directory.listFiles().orEmpty().filter { it.name.startsWith("$PREFIX-$font-") && it.name.endsWith(".sdf") }
        val now=System.currentTimeMillis()
        for(f in files.sortedByDescending { it.lastModified() }) {
            if(f.length() !in 33..(4L*1024*1024+120)) continue
            val record=try {
                val envelope=f.readBytes();val data=envelope.copyOfRange(32,envelope.size)
                require(MessageDigest.isEqual(envelope.copyOfRange(0,32),digest(data)))
                val input=DataInputStream(ByteArrayInputStream(data));val name=readString(input)
                val glyph=input.readInt()
                require(name==font && f==file(font,glyph))
                glyph to data
            } catch(_: Exception) { f.delete();continue }
            if(records.put(record.first,record.second)==null) memoryBytes+=record.second.size
            if(touch) f.setLastModified(now) // Disk LRU: once per font per process, not per glyph per request.
        }
        trimMemory(font)
    }
    private fun trimMemory(keep: String) {
        val iterator=fonts.entries.iterator()
        while(memoryBytes>memoryCap && iterator.hasNext()) {
            val entry=iterator.next()
            if(entry.key==keep) continue
            memoryBytes-=entry.value.values.sumOf { it.size.toLong() };loaded.remove(entry.key);iterator.remove()
        }
    }
    @Synchronized fun seeds(fonts: Set<String>): ByteArray {
        if(!directory.isDirectory) return ByteArray(0)
        dropStale()
        val records=ArrayList<ByteArray>();var total=8L
        for(font in fonts) {
            if(!identity(font)) continue
            load(font,true)
            for(record in this.fonts[font]?.values.orEmpty()) {
                if(record.size+total>SEED_BYTES) continue
                records.add(record);total+=record.size
            }
        }
        if(records.isEmpty()) return ByteArray(0)
        return ByteArrayOutputStream(total.toInt()).also { stream -> DataOutputStream(stream).use { out ->
            out.writeInt(0x46534331);out.writeInt(records.size);records.forEach(out::write)
        } }.toByteArray()
    }
    /** Packet has already passed native validation; bounds are repeated here before disk I/O. */
    @Synchronized fun storePacket(packet: ByteArray) {
        require(packet.size<=32*1024*1024)
        val raw=ByteArrayInputStream(packet);val input=DataInputStream(raw)
        require(input.readInt()==0x46545031 && input.readInt()==1)
        readString(input);skip(input,40)
        repeat(count(input,512)) {readString(input)}
        skip(input,Math.multiplyExact(count(input,4097),12))
        skip(input,Math.multiplyExact(count(input,16384),160))
        skip(input,Math.multiplyExact(count(input,4096),24))
        skip(input,Math.multiplyExact(count(input,4096),36))
        val fresh=ArrayList<Triple<String,Int,ByteArray>>()
        repeat(count(input,16384)) {
            val start=packet.size-raw.available();val font=readString(input);val glyph=input.readInt()
            val overlap=input.readInt();val w=input.readInt();val h=input.readInt();input.readInt();input.readInt()
            val pixels=count(input,4*1024*1024);require(w in 0..2048 && h in 0..2048 && pixels==w*h && overlap in 0..1)
            skip(input,pixels);val end=packet.size-raw.available()
            if(identity(font) && overlap==1) fresh.add(Triple(font,glyph,packet.copyOfRange(start,end)))
        }
        require(raw.available()==0)
        for((font,glyph,record) in fresh) {
            load(font,false) // A font first seen through a packet still learns what the disk already holds.
            val records=fonts.getOrPut(font) { LinkedHashMap() }
            if(records.containsKey(glyph)) continue
            records[glyph]=record;memoryBytes+=record.size
            check(directory.isDirectory || directory.mkdirs())
            val target=file(font,glyph)
            if(!target.exists()) {
                val temporary=File.createTempFile("sdf-",".tmp",directory)
                try {
                    temporary.outputStream().use {it.write(digest(record));it.write(record)}
                    try { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
                    catch(_: java.nio.file.AtomicMoveNotSupportedException) {Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)}
                } finally {temporary.delete()}
                if(diskBytes>=0) diskBytes+=target.length()
            }
        }
        fresh.firstOrNull()?.let { trimMemory(it.first) }
        if(diskBytes<0 || diskBytes>cap) {
            // First store of the process, or over the cap: one listing, oldest files first.
            val files=directory.listFiles().orEmpty().filter {it.name.endsWith(".sdf")}.sortedBy {it.lastModified()}
            var bytes=files.sumOf {it.length()}
            for(f in files) if(bytes>cap) { val size=f.length(); if(f.delete()) { bytes-=size; forget(f.name) } }
            diskBytes=bytes
        }
    }
    /** Memory stays a subset of disk: an evicted file's record leaves memory too. */
    private fun forget(name: String) {
        if(!name.startsWith("$PREFIX-") || !name.endsWith(".sdf")) return
        val body=name.removePrefix("$PREFIX-").removeSuffix(".sdf");val dash=body.lastIndexOf('-')
        if(dash<=0) return
        val glyph=body.substring(dash+1).toIntOrNull() ?: return
        fonts[body.substring(0,dash)]?.remove(glyph)?.let { memoryBytes-=it.size }
    }
    private fun count(input: DataInputStream,max: Int)=input.readInt().also {require(it in 0..max)}
    private fun readString(input: DataInputStream): String {
        val n=count(input,1024);val bytes=ByteArray(n);input.readFully(bytes);return bytes.toString(Charsets.UTF_8)
    }
    private fun skip(input: DataInputStream,count: Int) {require(input.skipBytes(count)==count)}
}
