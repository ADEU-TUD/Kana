package io.github.thepytales.llmhub.research

import io.github.thepytales.llmhub.ui.trLater

/**
 * Zitierprüfung im Dokument (AP-138 b): every source cited in the text has an entry in the reference list and every
 * entry is cited; literal quotations without a page are marked. Works without the model and without the internet.
 * Recognized citations: "(Müller, 2020)", "(Müller & Schmidt 2020, S. 12)", "(vgl. Müller et al., 2019a; Kunter u. a. 2011)",
 * "Müller (2020)", "Müller und Schmidt (2020, S. 4)" and numbers "[3]", "[2, 5–7]".
 */
object CitationCheck {
    enum class Kind(val label: String) {
        NOT_IN_LIST(trLater("Beleg ohne Eintrag im Literaturverzeichnis")),
        NOT_CITED(trLater("Eintrag wird im Text nicht zitiert")),
        QUOTE_WITHOUT_PAGE(trLater("Wörtliches Zitat ohne Seitenangabe")),
        QUOTE_WITHOUT_SOURCE(trLater("Wörtliches Zitat ohne Beleg")),
    }

    data class Finding(val kind: Kind, val text: String, val context: String, val offset: Int = -1)

    /** A citation found in the text: first author's surname, year (with a/b) or number, page if given. */
    data class Cite(val author: String, val year: String, val number: Int?, val page: String, val start: Int, val end: Int, val text: String)

    data class Report(val findings: List<Finding>, val citations: List<Cite>, val entries: Int, val quotes: Int) {
        val ok: Boolean get() = findings.isEmpty()
        fun count(kind: Kind) = findings.count { it.kind == kind }

        /** Plain report (for Kana, the clipboard). */
        fun text(): String = buildString {
            append("${citations.size} Belege im Text, $entries Einträge im Literaturverzeichnis, $quotes wörtliche Zitate. ")
            if (findings.isEmpty()) append("Alles passt zusammen.")
            else Kind.entries.filter { k -> findings.any { it.kind == k } }.forEach { k ->
                append("\n\n").append(io.github.thepytales.llmhub.ui.tr(k.label)).append(" (").append(count(k)).append("):")
                findings.filter { it.kind == k }.forEach { append("\n- ").append(it.text) }
            }
        }
    }

    private const val NAME = "(?:(?:von|van|de|der|den|zu)\\s+)*\\p{Lu}[\\p{L}'’-]+"
    private const val YEAR = "(?:\\d{4}[a-z]?|o\\.\\s?J\\.|n\\.\\s?d\\.|im Druck|in press)"
    private const val PAGE = "(?:S\\.|p\\.|pp\\.|Seite)\\s*\\d+[\\d\\s–-]*f{0,2}\\.?"
    private val AUTHORS = "$NAME(?:\\s*(?:,|&|und|and|/)\\s*$NAME)*(?:\\s+(?:et\\s+al\\.|u\\.\\s?a\\.))?"
    /** One item inside brackets: "vgl. Müller & Schmidt, 2020a, S. 12" (also several years: "Müller 2019, 2020"). */
    private val ITEM = Regex("(?:(?:vgl\\.|siehe|see|cf\\.|z\\.\\s?B\\.|e\\.\\s?g\\.,?)\\s+)?($AUTHORS)[,\\s]+($YEAR(?:\\s*,\\s*$YEAR)*)(?:\\s*[,:]\\s*($PAGE|\\d+[\\d–-]*f{0,2}\\.?))?")
    private val BRACKETS = Regex("\\(([^()]{4,300})\\)")
    private val NARRATIVE = Regex("($AUTHORS)\\s+\\(($YEAR)(?:\\s*[,:]\\s*($PAGE|\\d+[\\d–-]*))?\\)")
    private val NUMBERS = Regex("\\[(\\d{1,3}(?:\\s*[-–,]\\s*\\d{1,3})*)(?:\\s*,\\s*($PAGE))?]")
    private val QUOTES = Regex("„([^“”\"]{3,})[“\"]|“([^”]{3,})”|»([^«]{3,})«|(?<![\\p{L}\\d])\"([^\"\\n]{3,})\"")
    private val SENTENCE_END = Regex("[.!?](?=\\s|$)")
    private val HEADING = Regex("^#{1,6}\\s*(Literatur(verzeichnis)?|Quellen(verzeichnis)?|Literatur und Quellen|References|Bibliography|Works Cited)\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** The body text (without the reference list and HTML comments) and the lines of the reference list. */
    fun split(markdown: String): Pair<String, List<String>> {
        val m = HEADING.findAll(markdown).lastOrNull() ?: return markdown.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "") to emptyList()
        val level = m.value.takeWhile { it == '#' }.length
        val rest = markdown.substring(m.range.last + 1)
        val next = Regex("^#{1,$level}\\s", RegexOption.MULTILINE).find(rest)
        val listPart = if (next != null) rest.substring(0, next.range.first) else rest
        val body = markdown.substring(0, m.range.first) + (if (next != null) rest.substring(next.range.first) else "")
        val lines = listPart.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").lines().map { it.trim().removePrefix("- ").trim() }.filter { it.length > 8 }
        return body.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "") to lines
    }

    /** All citations of [text]. */
    fun citations(text: String): List<Cite> {
        val out = mutableListOf<Cite>()
        BRACKETS.findAll(text).forEach { b ->
            val inner = b.groupValues[1]
            ITEM.findAll(inner).forEach { m ->
                val authors = m.groupValues[1]
                m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { y ->
                    out += Cite(firstFamily(authors), y, null, m.groupValues[3], b.range.first, b.range.last + 1, b.value)
                }
            }
        }
        // "Müller (2020)": the bracket alone has no author, so it was not read above.
        NARRATIVE.findAll(text).forEach { m ->
            out += Cite(firstFamily(m.groupValues[1]), m.groupValues[2], null, m.groupValues[3], m.range.first, m.range.last + 1, m.value)
        }
        NUMBERS.findAll(text).forEach { m ->
            numbers(m.groupValues[1]).forEach { n -> out += Cite("", "", n, m.groupValues[2], m.range.first, m.range.last + 1, m.value) }
        }
        return out.sortedBy { it.start }
    }

    private fun numbers(s: String): List<Int> = s.split(',').flatMap { part ->
        val r = part.split(Regex("[-–]")).mapNotNull { it.trim().toIntOrNull() }
        if (r.size == 2 && r[1] >= r[0] && r[1] - r[0] < 50) (r[0]..r[1]).toList() else r
    }

    /** "von Aster & Müller" → "Aster"; "Müller et al." → "Müller". */
    private fun firstFamily(authors: String): String {
        val first = authors.split(Regex("\\s*(?:,|&|\\bund\\b|\\band\\b|/)\\s*")).first().replace(Regex("\\s+(et\\s+al\\.|u\\.\\s?a\\.)$"), "").trim()
        return Reference.normalize(first.replace(Regex("^((von|van|de|der|den|zu)\\s+)+", RegexOption.IGNORE_CASE), ""))
    }

    private fun yearKey(y: String) = Regex("\\d{4}").find(y)?.value ?: Reference.normalize(y).replace(Regex("[^a-z]"), "")

    private fun familyKey(p: Person) = Reference.normalize(p.family.replace(Regex("^((von|van|de|der|den|zu)\\s+)+", RegexOption.IGNORE_CASE), ""))

    private fun matches(c: Cite, r: Reference): Boolean {
        val first = r.creators.firstOrNull()?.let(::familyKey) ?: Reference.normalize(r.title.split(' ').firstOrNull().orEmpty())
        if (first.isEmpty() || c.author.isEmpty()) return false
        val nameOk = first == c.author || first.endsWith(" " + c.author) || c.author.endsWith(" $first") ||
            (r.creators.firstOrNull()?.literal == true && Reference.normalize(r.creators.first().family).startsWith(c.author))
        val ry = if (r.year.isBlank()) "oj" else yearKey(r.year)
        val cy = yearKey(c.year).let { if (it == "nd" || it == "imdruck" || it == "inpress") "oj" else it }
        return nameOk && (ry == cy || cy == "oj" && r.year.isBlank())
    }

    /**
     * Checks [markdown]. [entries] are the works of the reference list; null reads them from the list in the text
     * (heading "Literatur", "Literaturverzeichnis", "References" …). [numbered] says whether "[3]" refers to the
     * position in the list (numbered styles).
     */
    fun check(markdown: String, entries: List<Reference>? = null, numbered: Boolean? = null): Report {
        val (body, lines) = split(markdown)
        val listed = entries ?: lines.map { line -> FreeText.parse(line.replace(Regex("^\\[\\d+]\\s*|^\\d+\\.\\s+"), "").replace("*", "")) }
        val isNumbered = numbered ?: (lines.isNotEmpty() && lines.count { Regex("^\\[\\d+]|^\\d+\\.\\s").containsMatchIn(it) } >= lines.size / 2)
        val cites = citations(body).filter { it.number == null || isNumbered }
        val findings = mutableListOf<Finding>()
        val used = mutableSetOf<Int>()
        cites.forEach { c ->
            val idx = if (c.number != null) (c.number - 1).takeIf { it in listed.indices } else listed.indexOfFirst { matches(c, it) }
            if (idx == null || idx < 0) {
                findings += Finding(Kind.NOT_IN_LIST, c.text.trim(), context(body, c.start, c.end), c.start)
            } else used += idx
        }
        listed.forEachIndexed { i, r ->
            if (i !in used) findings += Finding(Kind.NOT_CITED, (lines.getOrNull(i) ?: r.short()).replace("*", "").take(160), "")
        }
        // Literal quotations: four words or more (shorter ones are mostly terms), with a source and a page in the same sentence.
        var quotes = 0
        QUOTES.findAll(body).forEach { q ->
            val inner = q.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (inner.trim().split(Regex("\\s+")).size < 4) return@forEach
            quotes++
            val sentenceStart = body.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), (q.range.first - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val after = body.substring(q.range.last + 1)
            val endRel = SENTENCE_END.find(after)?.range?.first?.coerceAtMost(240) ?: after.length.coerceAtMost(240)
            val end = q.range.last + 1 + endRel
            val near = cites.filter { it.start >= sentenceStart && it.start <= end + 1 }
            val tail = body.substring(q.range.last + 1, (end + 1).coerceAtMost(body.length))
            val page = near.any { it.page.isNotBlank() } || Regex("\\(\\s*(?:$PAGE|\\d+[\\d–-]*f{0,2}\\.?)\\s*\\)|[,:]\\s*$PAGE").containsMatchIn(tail)
            val snippet = "„" + inner.trim().take(90) + (if (inner.length > 90) "…" else "") + "“"
            when {
                near.isEmpty() && !page -> findings += Finding(Kind.QUOTE_WITHOUT_SOURCE, snippet, context(body, q.range.first, q.range.last + 1), q.range.first)
                !page -> findings += Finding(Kind.QUOTE_WITHOUT_PAGE, snippet, context(body, q.range.first, end), q.range.first)
            }
        }
        return Report(findings, cites, listed.size, quotes)
    }

    private fun context(text: String, start: Int, end: Int): String {
        val a = (start - 60).coerceAtLeast(0)
        val b = (end + 40).coerceAtMost(text.length)
        return (if (a > 0) "…" else "") + text.substring(a, b).replace(Regex("\\s+"), " ").trim() + (if (b < text.length) "…" else "")
    }
}
