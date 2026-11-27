package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.creative.DesignText
import io.github.thepytales.llmhub.creative.FieldRich
import io.github.thepytales.llmhub.data.Design
import io.github.thepytales.llmhub.data.DesignElement
import io.github.thepytales.llmhub.data.DesignFormat
import io.github.thepytales.llmhub.data.DesignPage
import io.github.thepytales.llmhub.data.ElementKind
import io.github.thepytales.llmhub.docs.text.CharFormat
import io.github.thepytales.llmhub.ui.trLater

/**
 * Untertitel-Stile für kurze Clips (AP-129 c). The subtitles in the TUD design ([VideoTitles.subtitleDesign]) stay the
 * standard and are not changed; for short clips (Social Media, Hochformat) there are two more: **Groß** – large bold
 * words in the lower middle of the picture, where the apps of the platforms leave room – and **Wort für Wort** – a few
 * words at a time, the word being spoken in yellow (word times from the transcript where measured, else estimated by
 * length). Each style gives the pieces of a cue ([parts]) and their picture ([design]); export and preview use both.
 */
object CaptionStyles {
    const val TUD = "tud"
    const val BIG = "gross"
    const val WORDS = "wort"

    val STYLES = listOf(TUD to trLater("TUD (Standard)"), BIG to trLater("Groß"), WORDS to trLater("Wort für Wort"))

    fun label(style: String) = STYLES.firstOrNull { it.first == style }?.second ?: STYLES.first().second

    /** The word being spoken in „Wort für Wort“. */
    const val HIGHLIGHT = "#FFD43B"

    /** Words shown together in „Wort für Wort“ (fewer when they get long). */
    private const val CHUNK_WORDS = 3
    private const val CHUNK_CHARS = 18

    /** One picture of a cue: [text] from [start] to [end] (timeline seconds), word [highlight] of it marked (−1: none). */
    data class Part(val start: Double, val end: Double, val text: String, val highlight: Int = -1)

    /** The words of [cue] with their start times (measured from the transcript where given, else by their length). */
    internal fun timedWords(cue: SubtitleCue): List<Pair<String, Double>> {
        val words = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (cue.words.size == words.size && cue.words.zipWithNext().all { (a, b) -> b >= a }) return words.zip(cue.words.map { it.coerceIn(cue.startSec, cue.endSec) })
        // Estimated: each word takes time by its letters, plus a little for the gap.
        val weights = words.map { it.length + 2.0 }
        val total = weights.sum()
        val dur = cue.endSec - cue.startSec
        var t = cue.startSec
        return words.mapIndexed { i, w -> (w to t).also { t += dur * weights[i] / total } }
    }

    /** The pictures of [cue] in [style]: one for the whole cue, or one per word for „Wort für Wort“. */
    fun parts(style: String, cue: SubtitleCue): List<Part> {
        if (style != WORDS) return listOf(Part(cue.startSec, cue.endSec, cue.text))
        val words = timedWords(cue)
        if (words.isEmpty()) return emptyList()
        // Chunks of up to three words (fewer when long), each word one picture of its chunk.
        val chunks = mutableListOf<MutableList<Int>>()
        for (i in words.indices) {
            val cur = chunks.lastOrNull()
            val len = cur?.sumOf { words[it].first.length + 1 } ?: 0
            if (cur == null || cur.size >= CHUNK_WORDS || len + words[i].first.length > CHUNK_CHARS) chunks += mutableListOf(i) else cur += i
        }
        return chunks.flatMap { chunk ->
            val text = chunk.joinToString(" ") { words[it].first }
            chunk.mapIndexed { k, i ->
                val end = if (i + 1 < words.size) words[i + 1].second else cue.endSec
                Part(words[i].second, maxOf(end, words[i].second + 0.04), text, k)
            }
        }
    }

    /** The piece of [cue] shown at [t] (preview), or null. */
    fun partAt(style: String, cue: SubtitleCue, t: Double): Part? = parts(style, cue).lastOrNull { t >= it.start - 1e-6 && t < it.end }

    private const val MM = DesignFormat.MM_PER_PX
    private fun px(v: Float, scale: Float) = v * scale * MM
    private fun pt(px: Float, scale: Float) = px * scale * 0.75f

    /** Picture of [part] in [style] for a [width]×[height] frame; [raised] above a lower third. TUD is the design as it was. */
    fun design(style: String, part: Part, width: Int, height: Int, raised: Boolean = false): Design {
        if (style != BIG && style != WORDS) return VideoTitles.subtitleDesign(part.text, width, height, raised)
        val s = minOf(width, height) / 1080f
        val w = width * MM
        val h = height * MM
        val portrait = height > width
        val side = w * 0.07f
        val size = if (style == WORDS) 84f else 64f
        // The lower middle: in portrait at 70 % of the height (the apps put their buttons below), else above the edge.
        val bottom = (if (portrait) h * 0.72f else h - px(130f, s)) - if (raised) px(260f, s) else 0f
        val boxH = px(size * 3.2f, s)
        val raw = when {
            style == WORDS && part.highlight >= 0 -> highlighted(part.text, part.highlight)
            style == WORDS -> part.text.replace('\n', ' ')
            else -> part.text
        }
        val e = DesignElement(
            kind = ElementKind.TEXT, x = side, y = bottom - boxH, w = w - 2 * side, h = boxH, text = raw, rich = style == WORDS,
            fontSize = pt(size, s), bold = true, color = "#FFFFFF", align = "center", valign = "bottom", lineHeight = 1.15f, fit = true,
        )
        // The dark box only as large as the text (as for the TUD subtitles).
        val laid = DesignText.layout(e)
        val lineW = (0 until laid.layout.lineCount).maxOfOrNull { laid.layout.getLineRight(it) - laid.layout.getLineLeft(it) }?.div(DesignText.REF) ?: 0f
        val textH = laid.layout.size.height / DesignText.REF
        val pad = px(22f, s)
        val box = DesignElement(
            kind = ElementKind.RECT, x = (w - lineW) / 2 - pad, y = e.y + laid.top - pad * 0.5f, w = lineW + 2 * pad, h = textH + pad,
            fill = "#000000", opacity = 0.7f, radius = px(16f, s),
        )
        return Design(id = "video-sub-$style", title = part.text, format = "video", width = w, height = h, pages = listOf(DesignPage(background = "", elements = listOf(box, e))))
    }

    /** [text] with word [index] in [HIGHLIGHT], as the inline format of a text box. */
    internal fun highlighted(text: String, index: Int): String {
        val plain = text.replace('\n', ' ')
        val chars = MutableList(plain.length) { CharFormat.PLAIN }
        var word = -1
        var inWord = false
        plain.forEachIndexed { i, ch ->
            if (!ch.isWhitespace() && !inWord) { word++; inWord = true } else if (ch.isWhitespace()) inWord = false
            if (inWord && word == index) chars[i] = CharFormat(color = HIGHLIGHT.removePrefix("#"))
        }
        return FieldRich.write(plain, chars)
    }

    /** Key of a part's picture in the caches of the preview. */
    fun key(style: String, part: Part, p: VideoProject, px: Int, raised: Boolean): String =
        if (style != BIG && style != WORDS) "sub|${part.text}|${p.width}x${p.height}|$px|$raised"
        else "sub|$style|${part.text}|${part.highlight}|${p.width}x${p.height}|$px|$raised"
}
