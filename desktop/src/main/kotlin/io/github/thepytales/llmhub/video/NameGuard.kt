package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.data.PiiDetector
import io.github.thepytales.llmhub.data.PiiKind
import io.github.thepytales.llmhub.transcribe.Transcript
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * A place in the sound of a file where a name is spoken (AP-128): [fromSec]..[toSec] of the source [path]; in the
 * export and the preview it is beeped, muted or the voice is disguised ([mode]); [checked] = looked at in the list.
 * Kept by source time, so cuts and moves of the clip do not lose it.
 */
@Serializable
data class Bleep(
    val id: String = newVideoId(),
    val path: String,
    val fromSec: Double,
    val toSec: Double,
    val name: String,
    val mode: String = NameGuard.PIEP,
    val checked: Boolean = false,
)

/**
 * Datenschutz in Unterrichtsvideos (AP-128), all on the device: finds the names in the transcripts of the clips (the
 * Namensschutz of the app plus a list of one's own, e.g. the participants), turns them into [Bleep]s, masks them as
 * „[Name]“ in subtitles and the text of the video, and builds the ffmpeg filters that beep, mute or disguise them.
 */
object NameGuard {
    const val PIEP = "piep"
    const val STUMM = "stumm"
    const val STIMME = "stimme"
    const val MASK = "[Name]"
    /** Seconds before and after a name that go with it (the word times are not exact). */
    const val PAD = 0.06

    /** The names in [text]: the rules of the Namensschutz (AP-67) and the names of [own] that occur. */
    fun names(text: String, own: List<String>): List<String> =
        (PiiDetector.find(text, ownTerms = own).filter { it.kind == PiiKind.NAME || it.kind == PiiKind.TERM }.map { it.value } +
            own.filter { o -> parts(listOf(o)).any { part -> words(text).any { matches(it, setOf(part)) } } }).distinct()

    /** The list of one's own (one name per line, also „Nachname, Vorname“ or separated by commas/semicolons). */
    fun ownList(text: String): List<String> = text.lines().flatMap { it.split(';') }.map { it.trim() }.filter { it.length >= 2 }

    /** The words of [names] (at least two letters), lower case: „Anna Müller“ → anna, müller. */
    internal fun parts(names: Collection<String>): Set<String> =
        names.flatMap { n -> n.split(Regex("[\\s,]+")) }.map(::norm).filter { it.length >= 2 }.toSet()

    internal fun norm(word: String): String = word.lowercase(Locale.GERMAN).trim { !it.isLetterOrDigit() }

    private fun words(text: String) = text.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }

    /** Whether [word] is one of [parts], also in the genitive („Müllers“, „Anna's“). */
    internal fun matches(word: String, parts: Set<String>): Boolean {
        val n = norm(word).removeSuffix("'s").removeSuffix("’s")
        return n in parts || (n.length > 3 && n.endsWith("s") && n.dropLast(1) in parts)
    }

    /**
     * The places of names in the sound of [p]'s clips (each file once, only within a clip's In–Out). Places already in
     * [p] keep their way and their tick; places of one's own that no name covers any more stay.
     */
    fun find(p: VideoProject, transcripts: Map<String, Transcript>, own: List<String>): List<Bleep> {
        val found = mutableListOf<Bleep>()
        for (path in p.clips.map { it.path }.distinct()) {
            val t = transcripts[path] ?: continue
            val ranges = p.clips.filter { it.path == path && !it.fx.freeze }.map { it.inSec to it.outSec }
            for (s in t.segments) {
                if (s.text.isBlank()) continue
                val parts = parts(names(s.text, own))
                if (parts.isEmpty()) continue
                var cur: Bleep? = null
                for ((a, b, w) in TextCut.words(s.text, s.start, s.end, s.words.map { Triple(it.start, it.end, it.text) })) {
                    val clean = w.trim { !it.isLetterOrDigit() }
                    if (matches(w, parts) && ranges.any { a < it.second && b > it.first }) {
                        cur = if (cur != null && a - cur.toSec < 0.3) cur.copy(toSec = b + PAD, name = cur.name + " " + clean)
                        else { cur?.let { found += it }; Bleep(path = path, fromSec = maxOf(0.0, a - PAD), toSec = b + PAD, name = clean) }
                    } else { cur?.let { found += it }; cur = null }
                }
                cur?.let { found += it }
            }
        }
        val merged = found.map { b -> p.bleeps.firstOrNull { it.overlaps(b) }?.let { old -> b.copy(id = old.id, mode = old.mode, checked = old.checked) } ?: b }
        return (merged + p.bleeps.filter { old -> merged.none { it.overlaps(old) } }).sortedWith(compareBy({ it.path }, { it.fromSec }))
    }

    private fun Bleep.overlaps(o: Bleep) = path == o.path && fromSec < o.toSec && toSec > o.fromSec

    /** Where [b] lies on the timeline of [p] (the first clip that shows it), or null when no clip shows it. */
    fun timelineAt(p: VideoProject, b: Bleep): Double? {
        val starts = p.starts()
        p.clips.forEachIndexed { i, c ->
            if (c.path == b.path && b.fromSec < c.outSec && b.toSec > c.inSec && !c.fx.freeze) return starts[i] + (maxOf(b.fromSec, c.inSec) - c.inSec) / c.speed
        }
        return null
    }

    /** Whether the word from [a] to [b] of [path] is covered by a place of [p]. */
    fun covered(p: VideoProject, path: String, a: Double, b: Double): Boolean =
        p.bleeps.any { it.path == path && (a + b) / 2 >= it.fromSec - 1e-6 && (a + b) / 2 <= it.toSec + 1e-6 }

    /** [text] with every word of [names] as „[Name]“ (several in a row become one). */
    fun mask(text: String, names: Collection<String>): String {
        val parts = parts(names)
        if (parts.isEmpty()) return text
        val out = Regex("[\\p{L}\\p{N}'’-]+").replace(text) { m -> if (matches(m.value, parts)) MASK else m.value }
        return out.replace(Regex("\\[Name](\\s+\\[Name])+"), MASK)
    }

    /**
     * The filters for the sound of [path] read from source second [ss] for [dur] s (input label [input]): returns the
     * graph lines to add before and the head of the chain to continue with – it ends with „,“ (more filters follow)
     * or with a label „[…]“. [format] is the sample format of the graph; [tag] makes the labels unique.
     */
    fun soundChain(bleeps: List<Bleep>, path: String, ss: Double, dur: Double, input: String, format: String, tag: String): Pair<List<String>, String> {
        val mine = bleeps.filter { it.path == path && it.toSec > ss && it.fromSec < ss + dur }
        if (mine.isEmpty()) return emptyList<String>() to "$input$format,"
        fun expr(list: List<Bleep>) = list.joinToString("+") { "between(t,${f(maxOf(0.0, it.fromSec - ss))},${f(it.toSec - ss)})" }
        val silent = mine.filter { it.mode != STIMME }
        val voice = mine.filter { it.mode == STIMME }
        val beep = mine.filter { it.mode == PIEP }
        var head = "$input$format"
        if (silent.isNotEmpty()) head += ",volume=0:enable='${expr(silent)}'"
        // Disguised: coarse bits, a fast tremolo and the band of a telephone – the words stay, the voice is not
        // recognisable. (vibrato and acrusher's log mode gave NaN in the graph, which made loudnorm silence the film.)
        if (voice.isNotEmpty()) {
            val e = expr(voice)
            head += ",acrusher=bits=6:mode=lin:aa=0.5:enable='$e',tremolo=f=32:d=0.8:enable='$e',bandpass=f=1400:width_type=h:w=1800:enable='$e'"
        }
        if (beep.isEmpty()) return emptyList<String>() to "$head,"
        val lines = listOf(
            "$head[nb$tag]",
            "sine=frequency=1000:sample_rate=48000:duration=${f(dur)},$format,volume=0.3,volume=0:enable='not(${expr(beep)})'[ns$tag]",
            "[nb$tag][ns$tag]amix=inputs=2:duration=first:normalize=0[nm$tag]",
        )
        return lines to "[nm$tag]"
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
}
