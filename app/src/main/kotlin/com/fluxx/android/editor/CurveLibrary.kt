package com.fluxx.android.editor

import com.fluxx.android.model.EasingCurve
import com.fluxx.android.model.EasingLimits
import com.fluxx.android.model.EasingPreset
import java.io.File

/**
 * GRAPH G2L: the app-level curve library (not per project, never in a project document). Curves are applied to keys
 * by value through SetKeyEasing; the library only stores named copies and the user's pinned order.
 */
data class UserCurve(val id: String, val name: String, val curve: EasingCurve)

/** One library entry: a built-in preset or a user curve. Types are not entries (they cannot be pinned or edited). */
sealed interface LibraryEntry {
    val id: String; val name: String; val curve: EasingCurve; val user: Boolean
    data class Preset(val preset: EasePreset) : LibraryEntry {
        override val id get() = CurveLibrary.presetId(preset); override val name get() = preset.name
        override val curve get() = preset.curve; override val user get() = false
    }
    data class User(val value: UserCurve) : LibraryEntry {
        override val id get() = value.id; override val name get() = value.name
        override val curve get() = value.curve; override val user get() = true
    }
}

/** Persisted state: user curves newest first, pinned ids in the user's order, and the playhead-dot preference. */
data class CurveLibraryState(val user: List<UserCurve> = emptyList(), val pinned: List<String> = CurveLibrary.DEFAULT_PINNED,
    val showPlayheadDot: Boolean = true)

/** Pure library logic (JVM tested). Every operation returns a new state; invalid requests return the state unchanged. */
object CurveLibrary {
    const val MAX_USER = 60
    const val MAX_PINNED = 15
    const val NAME_LIMIT = 24
    const val PRESET_PREFIX = "preset:"
    const val USER_PREFIX = "user:"

    /** Stable ids derived from each preset's own id string (never from its position in the list). */
    fun presetId(p: EasePreset) = PRESET_PREFIX + p.id
    private val presets: Map<String, EasePreset> by lazy { EasePresets.ALL.associateBy { presetId(it) } }

    /** First-run pinned set: Ease In-Out, Back, Expo and Circ variants plus the most used Penner / Material curves. */
    val DEFAULT_PINNED: List<String> = listOf("css.ease_in_out", "back.in", "back.out", "back.in_out", "expo.in", "expo.out",
        "expo.in_out", "circ.in_out", "cubic.in_out", "quad.out", "material.standard", "material.emphasized").map { PRESET_PREFIX + it }

    fun entry(state: CurveLibraryState, id: String): LibraryEntry? =
        presets[id]?.let { LibraryEntry.Preset(it) } ?: state.user.firstOrNull { it.id == id }?.let { LibraryEntry.User(it) }

    data class Sections(val pinned: List<LibraryEntry>, val presets: List<LibraryEntry>, val mine: List<LibraryEntry>)
    /** A pinned curve appears only in Pinned; unpinned ones in their home section (Presets in order, My curves newest first). */
    fun sections(state: CurveLibraryState): Sections {
        val pinned = state.pinned.mapNotNull { entry(state, it) }
        val ids = pinned.map { it.id }.toSet()
        return Sections(pinned, EasePresets.ALL.map { LibraryEntry.Preset(it) }.filter { it.id !in ids },
            state.user.map { LibraryEntry.User(it) }.filter { it.id !in ids })
    }

    // ---- Names ---------------------------------------------------------------------------------------------------
    /** "Curve N" with N the lowest unused number. */
    fun defaultName(state: CurveLibraryState): String {
        val used = state.user.map { it.name }.toSet()
        var n = 1; while ("Curve $n" in used) n++
        return "Curve $n"
    }
    /** Trimmed, NUL/newline-free, at most [NAME_LIMIT] characters, unique with a numeric suffix ("Name 2"). */
    fun uniqueName(state: CurveLibraryState, requested: String, except: String? = null): String {
        val clean = requested.replace("\u0000", "").replace('\n', ' ').trim().take(NAME_LIMIT).trim()
        val base = clean.ifEmpty { defaultName(state) }
        val used = state.user.filter { it.id != except }.map { it.name }.toSet()
        if (base !in used) return base
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = base.take(NAME_LIMIT - suffix.length).trimEnd() + suffix
            if (candidate !in used) return candidate
            n++
        }
    }

    // ---- User curves -----------------------------------------------------------------------------------------------
    /** Only bezier-equivalent types, Bounce and Elastic can be saved (named bezier types are stored as their values). */
    fun savable(curve: EasingCurve) = curve.type != EasingPreset.HOLD
    fun canAdd(state: CurveLibraryState) = state.user.size < MAX_USER
    fun asStored(curve: EasingCurve): EasingCurve = curve.bezierOrNull()?.let { EasingCurve.bezier(it[0], it[1], it[2], it[3]) } ?: curve

    /** Adds a curve at the top of My curves (not pinned). Returns the new state and the new id, or null when refused. */
    fun add(state: CurveLibraryState, name: String, curve: EasingCurve, id: String = USER_PREFIX + java.util.UUID.randomUUID()): Pair<CurveLibraryState, String>? {
        if (!savable(curve) || !canAdd(state)) return null
        val stored = UserCurve(id, uniqueName(state, name), asStored(curve))
        return state.copy(user = listOf(stored) + state.user) to id
    }
    fun rename(state: CurveLibraryState, id: String, name: String): CurveLibraryState {
        val c = state.user.firstOrNull { it.id == id } ?: return state
        // An empty value keeps the old name.
        if (name.isBlank()) return state
        return state.copy(user = state.user.map { if (it.id == id) c.copy(name = uniqueName(state, name, except = id)) else it })
    }
    /** Edits the stored curve only (keys that used it keep their own values). */
    fun edit(state: CurveLibraryState, id: String, curve: EasingCurve): CurveLibraryState {
        if (!savable(curve)) return state
        return state.copy(user = state.user.map { if (it.id == id) it.copy(curve = asStored(curve)) else it })
    }
    fun delete(state: CurveLibraryState, id: String): CurveLibraryState =
        state.copy(user = state.user.filterNot { it.id == id }, pinned = state.pinned - id)

    // ---- Pinning (drag only) ---------------------------------------------------------------------------------------
    fun pinnable(state: CurveLibraryState, id: String) = entry(state, id) != null
    /**
     * Drop [id] at pinned position [index]. Already pinned: a reorder. With room: inserted, later ones shift down.
     * Full: the curve in that slot returns to its home section and [id] takes the slot; the others keep their order.
     */
    fun pinAt(state: CurveLibraryState, id: String, index: Int): CurveLibraryState {
        if (!pinnable(state, id)) return state
        val list = state.pinned.toMutableList()
        val from = list.indexOf(id)
        if (from >= 0) {
            list.removeAt(from)
            list.add(index.coerceIn(0, list.size), id)
            return state.copy(pinned = list)
        }
        if (list.size < MAX_PINNED) { list.add(index.coerceIn(0, list.size), id); return state.copy(pinned = list) }
        list[index.coerceIn(0, list.lastIndex)] = id
        return state.copy(pinned = list)
    }
    fun unpin(state: CurveLibraryState, id: String) = state.copy(pinned = state.pinned - id)
    fun movePinned(state: CurveLibraryState, from: Int, to: Int): CurveLibraryState {
        if (from !in state.pinned.indices) return state
        val list = state.pinned.toMutableList(); val id = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), id)
        return state.copy(pinned = list)
    }

    // ---- Names for the header ----------------------------------------------------------------------------------------
    /** The current curve's name: its type tile, else a preset, else a user curve, else "Custom" (ε 0.005). */
    fun nameFor(state: CurveLibraryState, curve: EasingCurve): String {
        val choice = EaseChoice.of(curve)
        if (choice != EaseChoice.CUSTOM) return choice.label
        EasePresets.match(curve)?.let { return it.name }
        state.user.firstOrNull { EaseMath.same(it.curve, curve) }?.let { return it.name }
        return "Custom"
    }
    fun matches(entry: LibraryEntry, curve: EasingCurve) = EaseMath.same(entry.curve, curve)
}

/** A tiny JSON reader/writer for the library file and `.fluxxcurves` (no JSON dependency in the app). */
internal object MiniJson {
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
    fun num(f: Float): String = if (f == f.toLong().toFloat() && kotlin.math.abs(f) < 1e9f) f.toLong().toString() else f.toString()

    /** Parses a JSON value: Map, List, String, Double, Boolean or null. Throws IllegalArgumentException when malformed. */
    fun parse(text: String): Any? {
        val p = Parser(text); p.ws(); val v = p.value(0); p.ws()
        require(p.i == text.length) { "Trailing characters" }
        return v
    }
    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int): Any? {
            require(depth < 32) { "Too deep" }
            require(i < s.length) { "Unexpected end" }
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> { word("true"); true }; 'f' -> { word("false"); false }; 'n' -> { word("null"); null }
                else -> number()
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) { ws(); val k = string(); ws(); expect(':'); ws(); m[k] = value(depth + 1); ws(); if (s.getOrNull(i) != ',') break; i++ }
            expect('}'); return m
        }
        fun arr(depth: Int): List<Any?> {
            i++; val l = ArrayList<Any?>(); ws()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) { ws(); l.add(value(depth + 1)); ws(); if (s.getOrNull(i) != ',') break; i++ }
            expect(']'); return l
        }
        fun expect(c: Char) { require(s.getOrNull(i) == c) { "Expected $c at $i" }; i++ }
        fun word(w: String) { require(s.startsWith(w, i)) { "Expected $w" }; i += w.length }
        fun string(): String {
            expect('"'); val b = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> { require(i < s.length); when (val e = s[i++]) {
                        '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> { require(i + 4 <= s.length); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException("Bad escape $e") } }
                    else -> b.append(c)
                }
            }
        }
        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            require(i > start) { "Expected a value at $start" }
            return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Bad number")
        }
    }
}

/** Curve (de)serialisation shared by the library file and `.fluxxcurves`. */
internal object CurveCodec {
    private val WIRE = mapOf(EasingPreset.LINEAR to "linear", EasingPreset.EASY_EASE to "easy_ease", EasingPreset.CUSTOM to "custom",
        EasingPreset.BOUNCE to "bounce", EasingPreset.ELASTIC to "elastic")
    private val BACK = WIRE.entries.associate { it.value to it.key }
    fun write(name: String, c: EasingCurve, id: String? = null): String =
        "{" + (id?.let { "\"id\":${MiniJson.quote(it)}," } ?: "") + "\"name\":${MiniJson.quote(name)},\"type\":${MiniJson.quote(WIRE.getValue(c.type))}," +
            "\"a\":${MiniJson.num(c.a)},\"b\":${MiniJson.num(c.b)},\"c\":${MiniJson.num(c.c)},\"d\":${MiniJson.num(c.d)}}"
    /** A curve from a parsed object: the type must be known; values are clamped to the typed-entry limits. Null if invalid. */
    fun read(o: Any?): Pair<String, EasingCurve>? {
        val m = o as? Map<*, *> ?: return null
        val name = m["name"] as? String ?: return null
        val type = BACK[m["type"] as? String ?: return null] ?: return null
        fun f(k: String) = (m[k] as? Double)?.toFloat()?.takeIf { it.isFinite() }
        val a = f("a") ?: return null; val b = f("b") ?: return null
        val curve = when (type) {
            EasingPreset.BOUNCE -> EasingCurve.bounce(kotlin.math.round(a).coerceIn(EasingLimits.BOUNCE_COUNT_MIN, EasingLimits.BOUNCE_COUNT_MAX),
                b.coerceIn(EasingLimits.BOUNCE_RESTITUTION_MIN, EasingLimits.BOUNCE_RESTITUTION_MAX))
            EasingPreset.ELASTIC -> EasingCurve.elastic(a.coerceIn(EasingLimits.ELASTIC_OSCILLATIONS_MIN, EasingLimits.ELASTIC_OSCILLATIONS_MAX),
                b.coerceIn(EasingLimits.ELASTIC_DAMPING_MIN, EasingLimits.ELASTIC_DAMPING_MAX))
            else -> { val c = f("c") ?: return null; val d = f("d") ?: return null
                EasingCurve.bezier(EaseMath.clampX(a), EaseMath.clampY(b), EaseMath.clampX(c), EaseMath.clampY(d)) }
        }
        return name to curve
    }
}

/** The library file (app files dir): versioned JSON, written atomically (temp + rename). */
internal object CurveLibraryFile {
    const val FORMAT = "fluxx.curve-library"
    const val VERSION = 1
    fun encode(s: CurveLibraryState): String = "{\"format\":${MiniJson.quote(FORMAT)},\"version\":$VERSION,\"showPlayheadDot\":${s.showPlayheadDot}," +
        "\"pinned\":[${s.pinned.joinToString(",") { MiniJson.quote(it) }}],\"curves\":[${s.user.joinToString(",") { CurveCodec.write(it.name, it.curve, it.id) }}]}"
    /** Null for a corrupt, foreign or newer-version file (the caller keeps a backup and starts from defaults). */
    fun decode(text: String): CurveLibraryState? = runCatching {
        val m = MiniJson.parse(text) as? Map<*, *> ?: return null
        if (m["format"] != FORMAT) return null
        val v = (m["version"] as? Double)?.toInt() ?: return null
        if (v != VERSION) return null
        val user = (m["curves"] as? List<*>).orEmpty().mapNotNull { o ->
            val id = (o as? Map<*, *>)?.get("id") as? String ?: return@mapNotNull null
            CurveCodec.read(o)?.let { (name, c) -> UserCurve(id, name.take(CurveLibrary.NAME_LIMIT), c) }
        }.distinctBy { it.id }.take(CurveLibrary.MAX_USER)
        val state = CurveLibraryState(user, emptyList(), m["showPlayheadDot"] as? Boolean ?: true)
        val pinned = (m["pinned"] as? List<*>).orEmpty().mapNotNull { it as? String }.distinct()
            .filter { CurveLibrary.entry(state, it) != null }.take(CurveLibrary.MAX_PINNED)
        state.copy(pinned = pinned)
    }.getOrNull()
}

/** `.fluxxcurves` import / export: the user's curves only (built-ins ship with every install). */
object CurveExchange {
    const val FORMAT = "fluxx.curves"
    const val VERSION = 1
    const val MAX_BYTES = 1024 * 1024
    fun export(state: CurveLibraryState): String =
        "{\"format\":${MiniJson.quote(FORMAT)},\"version\":$VERSION,\"curves\":[${state.user.joinToString(",") { CurveCodec.write(it.name, it.curve) }}]}"

    data class ImportResult(val state: CurveLibraryState, val added: Int, val skipped: Int, val full: Boolean, val error: String? = null) {
        val message: String get() = error ?: buildList {
            add("Imported $added ${if (added == 1) "curve" else "curves"}")
            if (skipped > 0) add("Skipped $skipped")
            if (full) add("Library is full")
        }.joinToString(" · ")
    }
    /**
     * Reads [bytes] into [state]: rejects an unknown format/version, files over 1 MB and malformed JSON; per curve
     * validates the type, clamps values, skips invalid entries and exact duplicates (same name and values), suffixes
     * name clashes and stops at the 60-curve cap. Nothing is executed and nothing touches the network.
     */
    fun import(state: CurveLibraryState, bytes: ByteArray, ids: () -> String = { CurveLibrary.USER_PREFIX + java.util.UUID.randomUUID() }): ImportResult {
        if (bytes.size > MAX_BYTES) return ImportResult(state, 0, 0, false, "File is larger than 1 MB")
        val root = runCatching { MiniJson.parse(String(bytes, Charsets.UTF_8)) }.getOrNull() as? Map<*, *>
            ?: return ImportResult(state, 0, 0, false, "Not a Fluxx curves file")
        if (root["format"] != FORMAT) return ImportResult(state, 0, 0, false, "Not a Fluxx curves file")
        if ((root["version"] as? Double)?.toInt() != VERSION) return ImportResult(state, 0, 0, false, "Unsupported curves file version")
        var s = state; var added = 0; var skipped = 0; var full = false
        // Oldest first so the file's first curve ends up on top of My curves, as it was exported.
        for (o in (root["curves"] as? List<*>).orEmpty().reversed()) {
            val parsed = CurveCodec.read(o)
            if (parsed == null) { skipped++; continue }
            val (name, curve) = parsed
            val clean = name.trim().take(CurveLibrary.NAME_LIMIT)
            if (s.user.any { it.name == clean && EaseMath.same(it.curve, curve) }) { skipped++; continue }
            if (!CurveLibrary.canAdd(s)) { full = true; skipped++; continue }
            val r = CurveLibrary.add(s, clean, curve, ids())
            if (r == null) { skipped++; continue }
            s = r.first; added++
        }
        return ImportResult(s, added, skipped, full)
    }
}

/**
 * The app-level store: one JSON file in the app's files dir, written atomically. A corrupt or newer-version file is
 * kept as `curve_library.json.bak` and the library starts from defaults; it never crashes the app.
 */
class CurveLibraryStore internal constructor(private val file: File?) {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(load())
    val state: kotlinx.coroutines.flow.StateFlow<CurveLibraryState> = mutable

    private fun load(): CurveLibraryState {
        val f = file ?: return CurveLibraryState()
        if (!f.exists()) return CurveLibraryState()
        val text = runCatching { f.readText() }.getOrNull()
        return text?.let { CurveLibraryFile.decode(it) } ?: run {
            runCatching { f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true) }
            CurveLibraryState()
        }
    }
    /** One persisted write per change. */
    fun update(change: (CurveLibraryState) -> CurveLibraryState) {
        val next = change(mutable.value)
        if (next == mutable.value) return
        mutable.value = next
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(CurveLibraryFile.encode(next))
            if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) }
        }
    }
    companion object {
        const val FILE = "curve_library.json"
        @Volatile private var instance: CurveLibraryStore? = null
        fun get(context: android.content.Context): CurveLibraryStore = instance ?: synchronized(this) {
            instance ?: CurveLibraryStore(File(context.applicationContext.filesDir, FILE)).also { instance = it }
        }
        /** Tests: an in-memory store (no file); returns the previous instance. */
        internal fun replaceForTest(replacement: CurveLibraryStore?): CurveLibraryStore? = synchronized(this) { instance.also { instance = replacement } }
        internal fun inMemory(state: CurveLibraryState = CurveLibraryState()) = CurveLibraryStore(null).also { it.mutable.value = state }
        internal fun at(file: File) = CurveLibraryStore(file)
    }
}
