package com.fluxx.android.fonts

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Preparation-only, disposable, integrity-checked per-glyph cache. Exact user.* identities
 * hash font SHA-256, face, named instance and variation coordinates in FontLibraryRepository.
 * Settings version additionally pins FreeType/SDF ppem, spread and overlap mode. */
class SdfDiskCache(private val directory: File,private val cap: Long=64L*1024*1024) {
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun file(font: String,glyph: Int)=File(directory,"v1-48-8-$font-$glyph.sdf")
    private fun identity(font: String)=font.matches(Regex("user\\.[a-f0-9]{64}"))
    @Synchronized fun seeds(fonts: Set<String>): ByteArray {
        if(!directory.isDirectory) return ByteArray(0)
        val records=ArrayList<ByteArray>();var total=8
        val files=directory.listFiles().orEmpty().filter { f -> fonts.any {identity(it) && f.name.startsWith("v1-48-8-$it-")} }
            .sortedByDescending { it.lastModified() }
        for(f in files) {
            if(f.length() !in 33..(4L*1024*1024+120) || f.length()+total>8L*1024*1024) continue
            val record=try {
                val envelope=f.readBytes();val data=envelope.copyOfRange(32,envelope.size)
                require(MessageDigest.isEqual(envelope.copyOfRange(0,32),digest(data)))
                val input=DataInputStream(ByteArrayInputStream(data));val font=readString(input)
                require(font in fonts && identity(font));require(f==file(font,input.readInt()))
                data
            } catch(_: Exception) { f.delete();continue }
            records.add(record);total+=record.size;f.setLastModified(System.currentTimeMillis())
        }
        if(records.isEmpty()) return ByteArray(0)
        return ByteArrayOutputStream(total).also { stream -> DataOutputStream(stream).use { out ->
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
        repeat(count(input,16384)) {
            val start=packet.size-raw.available();val font=readString(input);val glyph=input.readInt()
            val overlap=input.readInt();val w=input.readInt();val h=input.readInt();input.readInt();input.readInt()
            val pixels=count(input,4*1024*1024);require(w in 0..2048 && h in 0..2048 && pixels==w*h && overlap in 0..1)
            skip(input,pixels);val end=packet.size-raw.available()
            if(identity(font) && overlap==1) {
                check(directory.isDirectory || directory.mkdirs())
                val record=packet.copyOfRange(start,end);val target=file(font,glyph)
                if(!target.exists()) {
                    val temporary=File.createTempFile("sdf-",".tmp",directory)
                    try {
                        temporary.outputStream().use {it.write(digest(record));it.write(record)}
                        try { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
                        catch(_: java.nio.file.AtomicMoveNotSupportedException) {Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)}
                    } finally {temporary.delete()}
                }
                target.setLastModified(System.currentTimeMillis())
            }
        }
        require(raw.available()==0)
        val files=directory.listFiles().orEmpty().filter {it.name.endsWith(".sdf")}.sortedBy {it.lastModified()}
        var bytes=files.sumOf {it.length()};for(f in files) if(bytes>cap) {val size=f.length();if(f.delete()) bytes-=size}
    }
    private fun count(input: DataInputStream,max: Int)=input.readInt().also {require(it in 0..max)}
    private fun readString(input: DataInputStream): String {
        val n=count(input,1024);val bytes=ByteArray(n);input.readFully(bytes);return bytes.toString(Charsets.UTF_8)
    }
    private fun skip(input: DataInputStream,count: Int) {require(input.skipBytes(count)==count)}
}
