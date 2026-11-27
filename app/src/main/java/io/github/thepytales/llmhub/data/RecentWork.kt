package io.github.thepytales.llmhub.data

import io.github.thepytales.llmhub.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Weiterarbeiten (AP-135 b, Analyse Q-02): what was worked on last in every editor – document, table, presentation,
 * design, video project, PDF, transcript – with the place where one stopped (page, slide, cell, time). The start page
 * and *Erstellen* show it; a click opens the file there again. The editors report while one works ([touch]); opening
 * from the list leaves the place for the editor to pick up once ([resume], [take]). Only on this device.
 */
object RecentWork {
    /** Kinds of [Entry.kind]. */
    const val DOC = "doc"
    const val SHEET = "sheet"
    const val SLIDES = "slides"
    const val DESIGN = "design"
    const val VIDEO = "video"
    const val PDF = "pdf"
    const val TRANSCRIPT = "transcript"

    @Serializable
    data class Entry(
        val kind: String,
        /** The document, design, video project or transcript id; the path for a PDF. */
        val ref: String,
        val title: String,
        /** Where one stopped, for the editor: „page:3“, „slide:4“, „cell:5:2“, „time:83.5“ (numbers from 0). */
        val at: String = "",
        /** The same for people: „Seite 4“, „Folie 5“, „Zelle C6“, „1:23“. */
        val place: String = "",
        val time: Long = System.currentTimeMillis(),
    ) {
        val key: String get() = "$kind:$ref"
    }

    /** How many entries are kept. */
    const val MAX = 12

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun file(c: AppContainer) = File(c.appContext.filesDir, "weiterarbeiten.json")

    private val _version = MutableStateFlow(0)
    /** Changes with every change of the list (for the screens). */
    val version: StateFlow<Int> = _version

    @Volatile
    private var cache: List<Entry>? = null

    @Synchronized
    fun all(c: AppContainer): List<Entry> = cache ?: runCatching { json.decodeFromString<List<Entry>>(file(c).readText()) }.getOrDefault(emptyList()).also { cache = it }

    /** The last [n] entries, newest first. */
    fun recent(c: AppContainer, n: Int = MAX): List<Entry> = all(c).take(n)

    /** The list after [e] was worked on: to the front, each file once, at most [MAX]. */
    internal fun add(entries: List<Entry>, e: Entry): List<Entry> = (listOf(e) + entries.filter { it.key != e.key }).take(MAX)

    /**
     * Notes that [e] is being worked on (at its place). Cheap to call often: an unchanged place of the newest entry is not
     * written again, a new place of it only every few seconds.
     */
    @Synchronized
    fun touch(c: AppContainer, e: Entry) {
        if (e.ref.isBlank()) return
        val list = all(c)
        // Opened without a place (the editor reports it once it knows it): the place from last time stays.
        @Suppress("NAME_SHADOWING") val e = if (e.at.isBlank()) list.firstOrNull { it.key == e.key }?.let { e.copy(at = it.at, place = it.place) } ?: e else e
        val first = list.firstOrNull()
        if (first != null && first.key == e.key && first.at == e.at && first.title == e.title) return
        val next = add(list, e)
        cache = next
        if (first != null && first.key == e.key && e.time - first.time < 3_000) { _version.value++; dirty = true; return }
        save(c, next)
    }

    @Volatile
    private var dirty = false

    /** Writes what [touch] kept back (when an editor closes). */
    @Synchronized
    fun flush(c: AppContainer) {
        if (dirty) cache?.let { save(c, it) }
    }

    /** Takes [key] off the list (the file is gone). */
    @Synchronized
    fun forget(c: AppContainer, key: String) {
        val next = all(c).filter { it.key != key }
        cache = next
        save(c, next)
    }

    /** Renames an entry (the document got a new title). */
    @Synchronized
    fun retitle(c: AppContainer, key: String, title: String) {
        val list = all(c)
        if (list.none { it.key == key && it.title != title }) return
        val next = list.map { if (it.key == key) it.copy(title = title) else it }
        cache = next
        save(c, next)
    }

    private fun save(c: AppContainer, entries: List<Entry>) {
        dirty = false
        runCatching {
            val f = file(c)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(entries))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        _version.value++
    }

    /** An entry to open (a click in „Weiterarbeiten“); the window's root opens it with [io.github.thepytales.llmhub.ui.openWork]. */
    val openRequest = MutableStateFlow<Entry?>(null)

    /** Places to go to once the editor of [Entry.key] opens (set by „Weiterarbeiten“). */
    private val pending = HashMap<String, String>()

    /** Leaves the place of [e] for its editor (call before opening it). */
    @Synchronized
    fun resume(e: Entry) {
        if (e.at.isNotBlank()) pending[e.key] = e.at
    }

    /** The place waiting for the editor of [kind]/[ref], without taking it (the document's tab, before its editor). */
    @Synchronized
    fun peek(kind: String, ref: String?): String? = pending["$kind:$ref"]

    /** The place to go to in the editor of [kind]/[ref], once; null when it was opened in another way. */
    @Synchronized
    fun take(kind: String, ref: String): String? = pending.remove("$kind:$ref")

    /** For tests. */
    @Synchronized
    internal fun reset() { cache = null; pending.clear(); dirty = false }

    /** „page:3“ → 3, null when [at] is something else. */
    fun number(at: String?, prefix: String): Int? = at?.takeIf { it.startsWith("$prefix:") }?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()

    /** „cell:5:2“ → 5 to 2. */
    fun cell(at: String?): Pair<Int, Int>? = at?.takeIf { it.startsWith("cell:") }?.split(':')?.let { p -> p.getOrNull(1)?.toIntOrNull()?.let { r -> p.getOrNull(2)?.toIntOrNull()?.let { r to it } } }

    /** „time:83.5“ → 83.5. */
    fun seconds(at: String?): Double? = at?.takeIf { it.startsWith("time:") }?.substringAfter(':')?.toDoubleOrNull()
}
