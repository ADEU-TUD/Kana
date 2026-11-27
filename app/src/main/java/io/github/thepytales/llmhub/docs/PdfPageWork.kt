package io.github.thepytales.llmhub.docs

import io.github.thepytales.llmhub.ui.tr
import io.github.thepytales.llmhub.ui.trLater
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Wasserzeichen, Kopf- und Fußzeile (AP-137 b): a text („Entwurf“, „Vertraulich“, „Seite {n} von {N}“, „{datum}“) or a
 * picture on the pages of a PDF – across the page (diagonal), in the middle, or in one of the six places of head and
 * foot. Placeholders: {n} page, {N} pages, {datum} today, {datei} name of the file (also {date}, {file}, {Seite},
 * {Seiten}). [pages] limits the pages („1-3, 5“; empty: all); [opacity] 0…1; [size] 0 = automatic (diagonal: as large
 * as fits, head and foot: 9 pt).
 */
@Serializable
data class PdfStamp(
    val text: String = "",
    val place: Place = Place.DIAGONAL,
    val imagePath: String? = null,
    val size: Float = 0f,
    val opacity: Float = 0.3f,
    val color: Int = 0x808080,
    val pages: String = "",
    val bold: Boolean = true,
) {
    enum class Place(val label: String, val edge: Boolean) {
        DIAGONAL(trLater("Quer über die Seite"), false),
        CENTER(trLater("Mitte"), false),
        HEADER_LEFT(trLater("Kopfzeile links"), true),
        HEADER_CENTER(trLater("Kopfzeile Mitte"), true),
        HEADER_RIGHT(trLater("Kopfzeile rechts"), true),
        FOOTER_LEFT(trLater("Fußzeile links"), true),
        FOOTER_CENTER(trLater("Fußzeile Mitte"), true),
        FOOTER_RIGHT(trLater("Fußzeile rechts"), true);

        val header: Boolean get() = this == HEADER_LEFT || this == HEADER_CENTER || this == HEADER_RIGHT
    }

    val empty: Boolean get() = text.isBlank() && imagePath.isNullOrBlank()

    /** The text for page [n] (1-based) of [total]. */
    fun resolve(n: Int, total: Int, date: String = today(), file: String = ""): String = text
        .replace("{n}", n.toString()).replace("{Seite}", n.toString()).replace("{page}", n.toString())
        .replace("{N}", total.toString()).replace("{Seiten}", total.toString()).replace("{pages}", total.toString())
        .replace("{datum}", date).replace("{Datum}", date).replace("{date}", date)
        .replace("{datei}", file).replace("{Datei}", file).replace("{file}", file)

    /** Whether the stamp goes on page [index] (0-based). */
    fun on(index: Int, total: Int): Boolean = pages.isBlank() || index in parsePageRanges(pages, total)

    companion object {
        fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())

        /** The usual ones, one click each in the dialog and the Stapel (in the language of the interface). */
        val ENTWURF get() = PdfStamp(tr("ENTWURF"), Place.DIAGONAL, opacity = 0.22f, color = 0x9E9E9E)
        val VERTRAULICH get() = PdfStamp(tr("VERTRAULICH"), Place.DIAGONAL, opacity = 0.22f, color = 0xC62828)
        val KOPIE get() = PdfStamp(tr("KOPIE"), Place.DIAGONAL, opacity = 0.22f, color = 0x9E9E9E)
        val PAGE_OF get() = PdfStamp(tr("Seite {n} von {N}"), Place.FOOTER_CENTER, opacity = 1f, color = 0x404040, bold = false)
        val DATE get() = PdfStamp("{datum}", Place.HEADER_RIGHT, opacity = 1f, color = 0x404040, bold = false)

        /** A stamp from a short wish of Kana: „Entwurf“, „Vertraulich“, „Seite x von y“, „Datum“ or any text. */
        fun fromWish(wish: String): PdfStamp {
            val w = wish.trim()
            val low = w.lowercase()
            return when {
                low == "entwurf" || low == "draft" -> ENTWURF
                low == "vertraulich" || low == "confidential" -> VERTRAULICH
                low == "kopie" || low == "copy" -> KOPIE
                low.startsWith("seite x") || low.startsWith("seitenzahl") || low == "seite {n} von {n}" -> PAGE_OF
                low == "datum" || low == "date" -> DATE
                else -> PdfStamp(w, Place.DIAGONAL, opacity = 0.22f, color = 0x9E9E9E)
            }
        }
    }
}

/** Lesezeichen (AP-137 d): editing the outline as a flat list with levels, and making it from the headings. */
object PdfBookmarks {
    /** [list] with entry [i] one level deeper (at most one deeper than the one before it). */
    fun indent(list: List<PdfOutlineEntry>, i: Int): List<PdfOutlineEntry> {
        if (i <= 0 || i >= list.size) return list
        val max = list[i - 1].level + 1
        if (list[i].level >= max) return list
        return shift(list, i, +1)
    }

    /** [list] with entry [i] one level higher; its children move with it. */
    fun outdent(list: List<PdfOutlineEntry>, i: Int): List<PdfOutlineEntry> {
        if (i !in list.indices || list[i].level == 0) return list
        return shift(list, i, -1)
    }

    /** The entry and the ones below it (its children) change their level by [by]. */
    private fun shift(list: List<PdfOutlineEntry>, i: Int, by: Int): List<PdfOutlineEntry> {
        val end = subtreeEnd(list, i)
        return list.mapIndexed { k, e -> if (k in i until end) e.copy(level = (e.level + by).coerceAtLeast(0)) else e }
    }

    /** Index after the last child of entry [i]. */
    fun subtreeEnd(list: List<PdfOutlineEntry>, i: Int): Int {
        var k = i + 1
        while (k < list.size && list[k].level > list[i].level) k++
        return k
    }

    /** Moves entry [i] with its children one place up (before its previous sibling) or down ([up] false). */
    fun move(list: List<PdfOutlineEntry>, i: Int, up: Boolean): List<PdfOutlineEntry> {
        if (i !in list.indices) return list
        val end = subtreeEnd(list, i)
        val block = list.subList(i, end)
        val level = list[i].level
        return if (up) {
            // The previous entry of the same level (or the parent's start when there is none).
            var p = i - 1
            while (p >= 0 && list[p].level > level) p--
            if (p < 0 || list[p].level < level) return list
            list.subList(0, p) + block + list.subList(p, i) + list.subList(end, list.size)
        } else {
            if (end >= list.size || list[end].level < level) return list
            val nextEnd = subtreeEnd(list, end)
            list.subList(0, i) + list.subList(end, nextEnd) + block + list.subList(nextEnd, list.size)
        }
    }

    /** Deletes entry [i]; its children move up one level. */
    fun delete(list: List<PdfOutlineEntry>, i: Int): List<PdfOutlineEntry> {
        if (i !in list.indices) return list
        val end = subtreeEnd(list, i)
        return list.mapIndexedNotNull { k, e -> when { k == i -> null; k in i + 1 until end -> e.copy(level = e.level - 1); else -> e } }
    }

    /** Levels that never jump by more than one (a reader would not show such a tree) and pages within the PDF. */
    fun normalized(list: List<PdfOutlineEntry>, pages: Int): List<PdfOutlineEntry> {
        var prev = -1
        return list.filter { it.title.isNotBlank() }.map { e ->
            val level = e.level.coerceIn(0, prev + 1)
            prev = level
            e.copy(title = e.title.trim(), level = level, page = e.page.coerceIn(1, pages.coerceAtLeast(1)))
        }
    }

    private val PAGE_NUMBER = Regex("^(Seite\\s+)?\\d{1,4}(\\s*(/|von)\\s*\\d{1,4})?$", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("^(\\d{1,2}(\\.\\d{1,2}){0,3})\\.?\\s+\\S")

    /**
     * Headings of a PDF from its text: lines clearly larger than the body text (or bold-looking numbered ones such as
     * „2.1 Methode“), short, not at the very top or bottom of the page; up to three levels by size. Running heads that
     * repeat on most pages are left out.
     */
    fun fromHeadings(runs: List<PdfTextRun>, sizes: List<Pair<Float, Float>>): List<PdfOutlineEntry> {
        data class Line(val page: Int, val y: Float, val h: Float, val text: String)
        val lines = runs.filter { it.text.isNotBlank() }.groupBy { it.page }.toSortedMap().flatMap { (page, list) ->
            val sorted = list.sortedWith(compareBy({ it.y }, { it.x }))
            val out = mutableListOf<MutableList<PdfTextRun>>()
            sorted.forEach { r ->
                val last = out.lastOrNull()
                if (last != null && abs((last.first().y + last.first().h / 2) - (r.y + r.h / 2)) < maxOf(last.first().h, r.h) * 0.5f) last += r else out += mutableListOf(r)
            }
            out.map { parts -> Line(page, parts.minOf { it.y }, parts.maxOf { it.h }, parts.sortedBy { it.x }.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()) }
        }
        if (lines.isEmpty()) return emptyList()
        val pageCount = lines.map { it.page }.distinct().size
        // Running heads (also with their page number), watermarks and the like: the same text on most pages.
        fun often(key: (Line) -> String) = lines.groupBy(key).filter { (_, ls) -> ls.map { it.page }.distinct().size >= maxOf(2.0, pageCount * 0.6) }.keys
        val repeated = if (pageCount < 2) emptySet() else often { it.text }
        val repeatedNumbered = if (pageCount < 3) emptySet() else often { it.text.replace(Regex("\\d+"), "#") }
        val body = lines.filter { l ->
            val ph = sizes.getOrNull(l.page)?.second ?: Float.MAX_VALUE
            val edge = l.y < ph * 0.06f || l.y > ph * 0.93f
            !(edge && PAGE_NUMBER.matches(l.text)) && l.text !in repeated && l.text.replace(Regex("\\d+"), "#") !in repeatedNumbered
        }
        val bodySize = body.flatMap { l -> List(l.text.length.coerceAtMost(200)) { l.h } }.sorted().let { if (it.isEmpty()) 10f else it[it.size / 2] }
        val heads = body.filter { l ->
            val t = l.text
            t.length in 2..120 && !t.endsWith(",") && !t.endsWith(";") && t.any { it.isLetter() } &&
                (l.h >= bodySize * 1.2f || (NUMBERED.containsMatchIn(t) && l.h >= bodySize * 1.08f && t.length < 80 && !t.endsWith(".")))
        }
        if (heads.isEmpty()) return emptyList()
        // Sizes to levels: the largest size is level 0, up to three levels (sizes within 8 % count as one).
        val distinct = mutableListOf<Float>()
        heads.map { it.h }.sortedDescending().forEach { h -> if (distinct.none { abs(it - h) <= it * 0.08f }) distinct += h }
        fun level(l: Line): Int {
            NUMBERED.find(l.text)?.let { m -> return (m.groupValues[1].count { it == '.' }).coerceAtMost(2) }
            return distinct.indexOfFirst { abs(it - l.h) <= it * 0.08f }.coerceIn(0, 2)
        }
        return normalized(heads.map { PdfOutlineEntry(it.text.trimEnd('.', ':'), it.page + 1, level(it)) }, sizes.size.coerceAtLeast(heads.maxOf { it.page } + 1))
    }
}
