package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.ui.trLater

/**
 * Untertitel-Stile für kurze Clips (AP-129, wie die Caption-Stile in CapCut und Clipchamp): besides the quiet box at
 * the bottom („Standard“, for lectures and teaching videos) large outlined text in the lower middle („Groß“) and a few
 * words at a time with the spoken word highlighted („Wort für Wort“) – read without sound on a phone. The project keeps
 * its cues as they are; only what is drawn ([display]) changes, so a switch back is lossless and SRT/VTT stay plain.
 */
object SubtitleStyles {
    const val STANDARD = "standard"
    const val GROSS = "gross"
    const val WOERTER = "woerter"

    val STYLES = listOf(STANDARD to trLater("Standard"), GROSS to trLater("Groß"), WOERTER to trLater("Wort für Wort"))

    /** Colour of the spoken word in „Wort für Wort“ (a warm yellow: readable on every picture, not a TUD colour). */
    const val HIGHLIGHT = "#FFD60A"

    /** Most words shown at once in „Wort für Wort“. */
    const val WORDS = 3

    fun of(style: String?): String = STYLES.firstOrNull { it.first == style }?.first ?: STANDARD

    /** The renderer reads inline marks (`**`, `_`, `~~`, `` ` ``, tags); a subtitle word loses those characters. */
    private fun clean(s: String) = s.filterNot { it in "*_~`<>" }.ifEmpty { "–" }

    /** Groups of at most [max] words; a group ends after a word with . , ! ? : ; (so a sentence never runs on). */
    internal fun groups(words: List<String>, max: Int = WORDS): List<List<String>> {
        val out = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        for (w in words) {
            cur += w
            if (cur.size >= max || w.last() in ".,!?:;…") { out += cur; cur = mutableListOf() }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /**
     * The cues as they are drawn for [p]: unchanged for „Standard“ and „Groß“; for „Wort für Wort“ one cue per word,
     * showing its group of up to three words with that word highlighted (inline markup of the renderer). Times of
     * the words are spread over the cue by their length (subtitles from SRT/VTT have no word times).
     */
    fun display(p: VideoProject): List<SubtitleCue> = if (of(p.subtitleStyle) != WOERTER) p.subtitles else words(p.subtitles)

    internal fun words(cues: List<SubtitleCue>): List<SubtitleCue> = cues.sortedBy { it.startSec }.flatMap { cue ->
        val words = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || cue.endSec <= cue.startSec) return@flatMap emptyList()
        val weights = words.map { it.length + 2.0 }
        val total = weights.sum()
        val span = cue.endSec - cue.startSec
        var t = cue.startSec
        val times = weights.map { wgt -> val a = t; t += span * wgt / total; a to t }
        var k = 0
        groups(words).flatMap { g ->
            val first = k
            k += g.size
            g.indices.map { j ->
                val text = g.indices.joinToString(" ") { i -> if (i == j) "<span style=\"color:$HIGHLIGHT\">${clean(g[i])}</span>" else clean(g[i]) }
                val (a, b) = times[first + j]
                SubtitleCue(a, b, text)
            }
        }
    }

    /** The text of a drawn cue without markup (for the outline and for tests). */
    fun plain(text: String): String = text.replace(Regex("<[^>]+>"), "")
}
