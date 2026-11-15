package io.github.thepytales.llmhub.docs

/**
 * Wissenschaft im Dokument (AP-126 c, like Word's *Referenzen*): Beschriftungen mit Nummer, Querverweise, Abbildungs- und
 * Tabellenverzeichnis, nummerierte Überschriften, Lesezeichen und Endnoten – as Markdown the editor and every export read:
 *
 * - **Abbildung mit Nummer**: the picture's attribute `nr=abb-hoersaal` (`![Der Hörsaal](a.png){width=50% nr=abb-hoersaal}`)
 *   – shown as „Abbildung 3: Der Hörsaal“; the id is the target of Querverweise.
 * - **Tabelle mit Nummer**: a line before the table, `<!-- tabelle: Kosten nach Phasen; nr=tab-kosten -->` – „Tabelle 2:
 *   Kosten nach Phasen“ above it (as Word and APA put it).
 * - **Querverweis**: a link to the id, `[Abbildung 3](#abb-hoersaal)`; its text follows the numbers ([refresh]).
 *   Headings are targets as `#kap-…` (from their text), Lesezeichen as `#lz-…` (paragraph marker `b=…`).
 * - **Verzeichnisse**: the lines `[Abbildungsverzeichnis]` and `[Tabellenverzeichnis]`, like `[Inhaltsverzeichnis]`.
 * - **Nummerierte Überschriften**: `<!-- ueberschriften: nummeriert -->` – 1, 1.1, 1.1.1 (the document title, the first
 *   heading of level 1, has none).
 * - **Endnoten**: `<!-- fussnoten: endnoten -->` – Word puts the notes at the end of the document.
 */
object DocFields {
    enum class Kind(val prefix: String) { FIGURE("abb"), TABLE("tab"), HEADING("kap"), BOOKMARK("lz") }

    /** A target of a Querverweis in document order: [label] is what a cross reference shows, [text] the caption or heading. */
    data class Entry(val id: String, val kind: Kind, val label: String, val text: String, val level: Int = 0, val number: String = "")

    data class TableCaption(val text: String, val id: String)

    const val FIGURES = "[Abbildungsverzeichnis]"
    const val TABLES = "[Tabellenverzeichnis]"

    fun isFigureList(text: String) = text.trim().equals(FIGURES, true) || text.trim().equals("[Abbildungen]", true)
    fun isTableList(text: String) = text.trim().equals(TABLES, true) || text.trim().equals("[Tabellen]", true)

    // ── Words of the document's language ───────────────────────────────────────────────────────────────

    private fun words(lang: String): Triple<String, String, String> = when (lang.lowercase().substringBefore('-')) {
        "de" -> Triple("Abbildung", "Tabelle", "Abschnitt")
        "fr" -> Triple("Figure", "Tableau", "Section")
        "es" -> Triple("Figura", "Tabla", "Sección")
        "it" -> Triple("Figura", "Tabella", "Sezione")
        "pl" -> Triple("Rysunek", "Tabela", "Rozdział")
        "cs" -> Triple("Obrázek", "Tabulka", "Oddíl")
        else -> Triple("Figure", "Table", "Section")
    }

    /** „Abbildung“ / „Figure“ … */
    fun figureWord(lang: String) = words(lang).first
    fun tableWord(lang: String) = words(lang).second

    /** Titles of the lists in the document's language. */
    fun listTitle(kind: Kind, lang: String): String = when {
        lang.startsWith("de") -> when (kind) { Kind.FIGURE -> "Abbildungsverzeichnis"; Kind.TABLE -> "Tabellenverzeichnis"; else -> "Inhaltsverzeichnis" }
        else -> when (kind) { Kind.FIGURE -> "List of Figures"; Kind.TABLE -> "List of Tables"; else -> "Contents" }
    }

    // ── Markers ─────────────────────────────────────────────────────────────────────────────────────────

    private val TABLE_CAPTION = Regex("^\\s*<!--\\s*tabelle\\s*:\\s*(.*?)\\s*-->\\s*$", RegexOption.IGNORE_CASE)
    private val NUMBERING = Regex("^\\s*<!--\\s*(?:ü|ue)berschriften\\s*:\\s*nummeriert\\s*-->\\s*$", RegexOption.IGNORE_CASE)
    private val ENDNOTES = Regex("^\\s*<!--\\s*fu(?:ß|ss)noten\\s*:\\s*endnoten\\s*-->\\s*$", RegexOption.IGNORE_CASE)
    private val ID = Regex("^[a-z][a-z0-9-]{0,60}$")

    /** The caption of a table, from its line before the table. */
    fun tableCaption(text: String): TableCaption? {
        if ('\n' in text.trim()) return null
        val m = TABLE_CAPTION.find(text) ?: return null
        val body = m.groupValues[1]
        val nr = Regex(";\\s*nr\\s*=\\s*([a-z0-9-]+)\\s*$").find(body)
        val caption = (if (nr != null) body.substring(0, nr.range.first) else body).trim()
        return TableCaption(caption, nr?.groupValues?.get(1) ?: ("tab-" + slug(caption)))
    }

    /** The line of a table caption. */
    fun tableCaptionLine(c: TableCaption): String = "<!-- tabelle: ${c.text.replace("-->", "").replace("\n", " ").trim()}; nr=${c.id} -->"

    /** A setting line of this file (numbering, endnotes, table caption is not one: it is shown). */
    fun isSetting(line: String) = NUMBERING.matches(line) || ENDNOTES.matches(line)

    fun numberedHeadings(markdown: String) = markdown.lineSequence().any { NUMBERING.matches(it) }
    fun endnotes(markdown: String) = markdown.lineSequence().any { ENDNOTES.matches(it) }

    fun withNumberedHeadings(markdown: String, on: Boolean) = withSetting(markdown, NUMBERING, "<!-- ueberschriften: nummeriert -->", on)
    fun withEndnotes(markdown: String, on: Boolean) = withSetting(markdown, ENDNOTES, "<!-- fussnoten: endnoten -->", on)

    private fun withSetting(markdown: String, re: Regex, line: String, on: Boolean): String {
        val body = markdown.lines().filterNot { re.matches(it) }.joinToString("\n").trimEnd()
        return if (on) "$body\n\n$line\n" else "$body\n"
    }

    /** An id from a text: „Der Hörsaal im HSZ“ → „der-hoersaal-im-hsz“ (at most 40 characters). */
    fun slug(text: String): String {
        val plain = text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("<!--.*?-->"), "").replace(Regex("[*_`~]"), "")
        val s = java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        return s.ifEmpty { "ziel" }
    }

    /** [base] made unique against [taken] (adds -2, -3 …). */
    fun unique(base: String, taken: Collection<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    fun validId(id: String) = ID.matches(id)

    // ── Numbers ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Numbers of headings of [levels] (1–4, in document order): the first heading of level 1 is the title (null); the
     * smallest level after it counts as 1, the next as 1.1 and so on.
     */
    fun headingNumbers(levels: List<Int>): List<String?> {
        val title = levels.indexOfFirst { it == 1 }.takeIf { it >= 0 && levels.take(it).isEmpty() }
        val top = levels.filterIndexed { i, _ -> i != title }.minOrNull() ?: return levels.map { null }
        val counters = IntArray(6)
        return levels.mapIndexed { i, l ->
            if (i == title) return@mapIndexed null
            val depth = (l - top).coerceIn(0, 5)
            counters[depth]++
            for (k in depth + 1 until 6) counters[k] = 0
            // A level skipped above (### right after the title) counts as 1 there.
            (0..depth).joinToString(".") { k -> counters[k].coerceAtLeast(1).toString() }
        }
    }

    private val HEADING = Regex("^(#{1,4})\\s+(.+?)\\s*#*\\s*$")
    private val IMAGE_NR = Regex("\\bnr\\s*=\\s*\"?([a-z0-9-]+)")
    private val BOOKMARK = Regex("^<!--a:[crj]?(?:;[a-z]+=[0-9a-z.\\-]+)*?;b=([0-9a-z\\-]+?)(?=;|-->)")

    /** The text of a heading line without marks: „## **Methode**“ → „Methode“. */
    private fun headingText(raw: String) = raw.replace(Regex("<!--.*?-->"), "").replace(Regex("\\*\\*|__|\\*|`"), "").trim()

    /** All targets of [markdown] in document order, with their labels in the document's language. */
    fun entries(markdown: String, lang: String = DocMarkup.language(markdown)): List<Entry> {
        val (fig, tab, sec) = words(lang)
        val numbered = numberedHeadings(markdown)
        data class Raw(val kind: Kind, val id: String, val text: String, val level: Int = 0)
        val raw = mutableListOf<Raw>()
        var inCode = false
        val ids = mutableSetOf<String>()
        for (line in markdown.lines()) {
            val t = line.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) { inCode = !inCode; continue }
            if (inCode) continue
            HEADING.find(t)?.let { m ->
                val text = headingText(m.groupValues[2])
                val id = unique("kap-" + slug(text), ids).also { ids += it }
                raw += Raw(Kind.HEADING, id, text, m.groupValues[1].length)
                return@let
            }
            DocMarkup.image(t)?.let { img ->
                val nr = img.nr ?: return@let
                raw += Raw(Kind.FIGURE, nr, img.alt)
                ids += nr
            }
            tableCaption(t)?.let { raw += Raw(Kind.TABLE, it.id, it.text); ids += it.id }
            BOOKMARK.find(t)?.let { m ->
                val id = "lz-" + m.groupValues[1]
                val text = t.replace(Regex("^<!--.*?-->"), "").replace(Regex("[*_`]"), "").trim()
                raw += Raw(Kind.BOOKMARK, id, text)
                ids += id
            }
        }
        val headingNos = headingNumbers(raw.filter { it.kind == Kind.HEADING }.map { it.level })
        var h = 0
        var figures = 0
        var tables = 0
        return raw.map { r ->
            when (r.kind) {
                Kind.FIGURE -> { figures++; Entry(r.id, r.kind, "$fig $figures", r.text, number = "$figures") }
                Kind.TABLE -> { tables++; Entry(r.id, r.kind, "$tab $tables", r.text, number = "$tables") }
                Kind.HEADING -> {
                    val no = headingNos[h++]
                    Entry(r.id, r.kind, if (numbered && no != null) "$sec $no" else "„${r.text}“", r.text, r.level, if (numbered) no.orEmpty() else "")
                }
                Kind.BOOKMARK -> Entry(r.id, r.kind, "„" + r.text.take(40).let { if (r.text.length > 40) "$it…" else it } + "“", r.text)
            }
        }
    }

    // ── For the exports ─────────────────────────────────────────────────────────────────────────────────

    private val LINK = Regex("\\[([^\\]\\n]*)]\\(#([a-z0-9-]+)\\)")
    private val GENERATED = Regex("^(?:(?:Abbildung|Tabelle|Abschnitt|Figure|Table|Section|Tableau|Figura|Tabla|Sección|Tabella|Sezione|Rysunek|Tabela|Rozdział|Obrázek|Tabulka|Oddíl)\\s+[\\d.]+|[„“\"«].*[“”\"»])$")

    /** Is [text] what a cross reference wrote (and may be renewed): „Abbildung 3“, „Abschnitt 1.2“, „„Einleitung““. */
    fun isGeneratedRef(text: String) = GENERATED.matches(text.trim())

    /** [markdown] with the text of every cross reference renewed from the current numbers. */
    fun refresh(markdown: String, entries: List<Entry> = entries(markdown)): String {
        if ("](#" !in markdown) return markdown
        val labels = entries.associate { it.id to it.label }
        return LINK.replace(markdown) { m ->
            val label = labels[m.groupValues[2]]
            if (label == null || !isGeneratedRef(m.groupValues[1]) || label == m.groupValues[1]) m.value
            else "[" + label.replace("]", ")") + "](#" + m.groupValues[2] + ")"
        }
    }

    /** Heading lines with their numbers (exports): „## Methode“ → „## 1.2 Methode“ (an en space after the number). */
    fun numberHeadings(markdown: String): String {
        if (!numberedHeadings(markdown)) return markdown
        val lines = markdown.lines().toMutableList()
        val at = mutableListOf<Int>()
        val levels = mutableListOf<Int>()
        var inCode = false
        lines.forEachIndexed { i, l ->
            val t = l.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) return@forEachIndexed
            HEADING.find(t)?.let { at += i; levels += it.groupValues[1].length }
        }
        headingNumbers(levels).forEachIndexed { k, no ->
            if (no == null) return@forEachIndexed
            val i = at[k]
            val m = Regex("^(\\s*#{1,4}\\s+)(<!--.*?-->)?(.*)$").find(lines[i]) ?: return@forEachIndexed
            lines[i] = m.groupValues[1] + m.groupValues[2] + no + NUMBER_GAP + m.groupValues[3]
        }
        return lines.joinToString("\n")
    }

    /** Between a heading's number and its text (an en space – the heading's id leaves it out). */
    const val NUMBER_GAP = "\u2002"

    /** The id of a heading in the exports, from its text (with the number left out). */
    fun headingId(text: String, taken: MutableSet<String>): String =
        unique("kap-" + slug(headingText(text).replace(Regex("^[\\d.]+$NUMBER_GAP"), "")), taken).also { taken += it }

    /** What every export does first: numbers for the headings and cross references renewed. */
    fun forExport(markdown: String): String {
        if ("](#" !in markdown && !numberedHeadings(markdown)) return markdown
        return numberHeadings(refresh(markdown))
    }
}
