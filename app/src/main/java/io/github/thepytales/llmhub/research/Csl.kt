package io.github.thepytales.llmhub.research

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A citation style in the Citation Style Language (CSL 1.0, the format of Zotero, Mendeley and the official style
 * collection with over 10 000 styles of publishers and journals, AP-138 a). The interpreter covers what reference lists
 * and in-text citations of the app need: macros, text, names (et al., initials, sort order, substitute), dates (the app
 * knows the year, for web pages the date read), numbers, labels, groups, conditions, affixes, italics/bold, quotes,
 * text case, sorting and year suffixes. Italics are written as Markdown (`*…*`) like the built-in styles.
 * Not covered (rarely needed for reference lists): notes positions (ibid.), disambiguation by given names, locators.
 */
class CslStyle private constructor(
    /** Short id of the style ("ieee", "harvard-cite-them-right"), also the file name. */
    val id: String,
    val title: String,
    /** "author-date", "numeric", "note", "author" or "label". */
    val format: String,
    val defaultLocale: String?,
    /** Id of the independent style a dependent style (a journal) uses; set only before the parent is attached. */
    val parentId: String?,
    private val styleAttrs: Map<String, String>,
    private val macros: Map<String, CslNode>,
    private val locales: List<CslNode>,
    private val citation: CslNode?,
    private val bibliography: CslNode?,
    /** The XML as loaded (for storing). */
    val xml: String,
) {
    val numeric: Boolean get() = format == "numeric" || format == "label"
    val dependent: Boolean get() = parentId != null && bibliography == null && citation == null
    val language: String get() = (defaultLocale ?: "").substringBefore('-').lowercase()
    val german: Boolean get() = language == "de"

    /** The built-in style the rest of the app falls back to (needed fields, in-text citation without the CSL file). */
    val fallback: CitationStyle get() = when {
        numeric -> CitationStyle.IEEE
        german -> CitationStyle.DIN_ISO_690
        else -> CitationStyle.CHICAGO
    }

    /** Key in the settings and in the marker of a document. */
    val key: String get() = KEY_PREFIX + id

    /** This dependent style with the rules of its [parent] (title, id and locale of the dependent style stay). */
    fun withParent(parent: CslStyle): CslStyle = CslStyle(
        id, title, format.ifBlank { parent.format }, defaultLocale ?: parent.defaultLocale, null,
        parent.styleAttrs + (if (defaultLocale != null) mapOf("default-locale" to defaultLocale) else emptyMap()),
        parent.macros, locales + parent.locales, parent.citation, parent.bibliography, parent.xml,
    )

    // ── Public rendering ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The reference list entries of [refs] as the style sorts them (alphabetical by the first author when the style
     * gives no order), with a/b after the year and the numbers of numbered styles.
     */
    fun bibliography(refs: List<Reference>, lang: String? = null): List<Pair<Reference, String>> {
        val bib = bibliography ?: return refs.map { it to CitationFormatter.format(it, fallback) }
        val sorted = sort(refs, bib, lang)
        val suffixes = yearSuffixes(sorted)
        val substitute = bib.attrs["subsequent-author-substitute"]
        var previousNames: String? = null
        return sorted.mapIndexed { i, r ->
            val ctx = Ctx(r, Mode.BIBLIOGRAPHY, terms(lang, r), i + 1, suffixes[r.id].orEmpty(), inherited(bib))
            var line = renderLayout(bib, ctx)
            val names = ctx.firstNames
            if (substitute != null && names != null && names.isNotBlank()) {
                if (names == previousNames) line = line.replaceFirst(names, substitute)
                previousNames = names
            }
            r to tidy(line)
        }
    }

    /**
     * The in-text citation of [items] (reference and its number in the list), e.g. "(Müller, 2020)" or "[3]". Works
     * of the same authors and year get the a/b of [suffixes].
     */
    fun cite(items: List<Pair<Reference, Int>>, suffixes: Map<String, String> = emptyMap(), lang: String? = null): String {
        val cit = citation ?: return items.joinToString("; ") { (r, n) -> io.github.thepytales.llmhub.docs.DocCitations.inText(r, fallback, n) }
        val layout = cit.child("layout") ?: return ""
        val delimiter = layout.attrs["delimiter"] ?: ""
        val parts = items.map { (r, n) ->
            val ctx = Ctx(r, Mode.CITATION, terms(lang, r), n, suffixes[r.id].orEmpty(), inherited(cit))
            renderChildren(layout, ctx)
        }.filter { it.isNotBlank() }
        val body = parts.joinToString(delimiter)
        return tidy(format(layout, body, terms(lang), applyAffixes = true))
    }

    /** a/b for works of the same first authors and year (author-date styles). */
    fun yearSuffixes(refs: List<Reference>): Map<String, String> {
        if (numeric) return emptyMap()
        val out = mutableMapOf<String, String>()
        refs.groupBy { r -> r.creators.joinToString("|") { Reference.normalize(it.family) } + "#" + r.year }
            .filter { (key, group) -> group.size > 1 && !key.startsWith("#") }
            .forEach { (_, group) ->
                group.sortedWith { a, b -> COLLATOR.compare(a.title, b.title) }.forEachIndexed { i, r -> out[r.id] = ('a' + i).toString() }
            }
        return out
    }

    // ── Sorting ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun sort(refs: List<Reference>, bib: CslNode, lang: String?): List<Reference> {
        val keys = bib.child("sort")?.children?.filter { it.name == "key" }.orEmpty()
        val alphabetical = refs.sortedWith { a, b -> COLLATOR.compare(Bibliography.sortKey(a), Bibliography.sortKey(b)) }
        if (keys.isEmpty()) return alphabetical
        val position = alphabetical.withIndex().associate { it.value.id to it.index }
        val t = terms(lang)
        fun keyOf(r: Reference, k: CslNode): String {
            val ctx = Ctx(r, Mode.SORT, t, (position[r.id] ?: 0) + 1, "", inherited(bib) + k.attrs.filterKeys { it.startsWith("names-") }
                .mapKeys { it.key.replace("names-", "et-al-") } + ("name-as-sort-order" to "all"))
            val v = k.attrs["variable"]
            return when {
                v == "citation-number" -> "%06d".format(ctx.number)
                v != null && v in NAME_VARS -> people(r, v).joinToString(" ") { "${it.family} ${it.given}" }
                v != null && v in DATE_VARS -> date(r, v)?.let { "%04d%02d%02d".format(it.year ?: 0, it.month ?: 0, it.day ?: 0) } ?: ""
                v != null -> variable(r, v, ctx)
                k.attrs["macro"] != null -> plain(macros[k.attrs["macro"]]?.let { renderChildren(it, ctx) }.orEmpty())
                else -> ""
            }
        }
        return alphabetical.sortedWith { a, b ->
            for (k in keys) {
                val ka = keyOf(a, k)
                val kb = keyOf(b, k)
                // Empty keys go last (CSL rule).
                val c = when {
                    ka.isEmpty() && kb.isEmpty() -> 0
                    ka.isEmpty() -> 1
                    kb.isEmpty() -> -1
                    else -> COLLATOR.compare(ka, kb).let { if (k.attrs["sort"] == "descending") -it else it }
                }
                if (c != 0) return@sortedWith c
            }
            (position[a.id] ?: 0) - (position[b.id] ?: 0)
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────────────────────────────────────

    private enum class Mode { CITATION, BIBLIOGRAPHY, SORT }

    private class Ctx(
        val ref: Reference,
        val mode: Mode,
        val terms: Terms,
        val number: Int,
        val suffix: String,
        /** Name options inherited from the style and from cs:citation or cs:bibliography. */
        val nameOpts: Map<String, String>,
    ) {
        /** Variables a substitute already used (rendered only once). */
        val suppressed = mutableSetOf<String>()
        var called = 0
        var filled = 0
        var firstNames: String? = null
        /** Variables that gave text while a substitute was tried. */
        var recording: MutableSet<String>? = null
    }

    private fun inherited(node: CslNode): Map<String, String> = styleAttrs.filterKeys { it in NAME_OPTIONS } + node.attrs.filterKeys { it in NAME_OPTIONS }

    private fun renderLayout(area: CslNode, ctx: Ctx): String {
        val layout = area.child("layout") ?: return ""
        // second-field-align: the first field (number) stands apart from the rest.
        val body = if (area.attrs["second-field-align"] != null) {
            val parts = renderList(layout, ctx).filter { it.isNotEmpty() }
            if (parts.size > 1) parts.first().trimEnd() + " " + parts.drop(1).joinToString("") else parts.joinToString("")
        } else renderChildren(layout, ctx)
        return format(layout, body, ctx.terms, applyAffixes = true)
    }

    private fun renderChildren(node: CslNode, ctx: Ctx, delimiter: String = ""): String =
        renderList(node, ctx).filter { it.isNotEmpty() }.joinToString(delimiter)

    /** The outputs of the children; the elements of a chosen branch count as children (for the delimiter of a group). */
    private fun renderList(node: CslNode, ctx: Ctx): List<String> = buildList {
        node.children.forEach { child ->
            if (child.name == "choose") chosen(child, ctx)?.let { addAll(renderList(it, ctx)) } else add(render(child, ctx))
        }
    }

    private fun render(node: CslNode, ctx: Ctx): String {
        val raw = when (node.name) {
            "text" -> text(node, ctx)
            "names" -> names(node, ctx)
            "date" -> dateNode(node, ctx)
            "number" -> number(node, ctx)
            "label" -> label(node, ctx, node.attrs["variable"].orEmpty())
            "group" -> group(node, ctx)
            "choose" -> return choose(node, ctx)
            else -> ""
        }
        if (raw.isEmpty()) return ""
        val out = format(node, raw, ctx.terms, applyAffixes = true)
        return if (node.attrs["display"] == "left-margin") "$out " else out
    }

    private fun text(node: CslNode, ctx: Ctx): String {
        val a = node.attrs
        a["variable"]?.let { v ->
            ctx.called++
            if (v in ctx.suppressed) return ""
            val value = variable(ctx.ref, v, ctx, short = a["form"] == "short")
            if (value.isNotBlank()) { ctx.filled++; ctx.recording?.add(v) }
            return value
        }
        a["macro"]?.let { m ->
            val macro = macros[m] ?: return ""
            // A macro counts like the variables inside it (for the suppression of empty groups).
            return renderChildren(macro, ctx)
        }
        a["term"]?.let { t -> return ctx.terms.get(t, a["form"] ?: "long", plural = a["plural"] == "true") }
        a["value"]?.let { return it }
        return ""
    }

    private fun group(node: CslNode, ctx: Ctx): String {
        val calledBefore = ctx.called
        val filledBefore = ctx.filled
        val out = renderChildren(node, ctx, node.attrs["delimiter"] ?: "")
        val called = ctx.called - calledBefore
        val filled = ctx.filled - filledBefore
        return if (called > 0 && filled == 0) "" else out
    }

    private fun choose(node: CslNode, ctx: Ctx): String = chosen(node, ctx)?.let { renderChildren(it, ctx) }.orEmpty()

    private fun chosen(node: CslNode, ctx: Ctx): CslNode? = node.children.firstOrNull { branch ->
        when (branch.name) {
            "if", "else-if" -> condition(branch, ctx)
            "else" -> true
            else -> false
        }
    }

    private fun condition(node: CslNode, ctx: Ctx): Boolean {
        val tests = mutableListOf<Boolean>()
        val r = ctx.ref
        node.attrs.forEach { (attr, value) ->
            val values = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
            when (attr) {
                "type" -> values.forEach { tests += it == cslType(r) }
                "variable" -> values.forEach { tests += has(r, it, ctx) }
                "is-numeric" -> values.forEach { tests += isNumeric(variable(r, it, ctx)) }
                "is-uncertain-date" -> values.forEach { _ -> tests += false }
                "locator" -> values.forEach { _ -> tests += false }
                "position" -> values.forEach { tests += it == "first" }
                "disambiguate" -> tests += false
                "context" -> values.forEach { tests += (it == "citation") == (ctx.mode == Mode.CITATION) }
            }
        }
        if (tests.isEmpty()) return false
        return when (node.attrs["match"] ?: "all") {
            "any" -> tests.any { it }
            "none" -> tests.none { it }
            else -> tests.all { it }
        }
    }

    // ── Names ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun names(node: CslNode, ctx: Ctx, inheritedName: CslNode? = null, inheritedEtAl: CslNode? = null, inheritedLabel: CslNode? = null): String {
        val vars = node.attrs["variable"].orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nameNode = node.child("name") ?: inheritedName
        val etAlNode = node.child("et-al") ?: inheritedEtAl
        val labelNode = node.child("label") ?: inheritedLabel
        val labelFirst = node.children.indexOfFirst { it.name == "label" }.let { it >= 0 && it < node.children.indexOfFirst { c -> c.name == "name" } }
        ctx.called++
        val lists = vars.filter { it !in ctx.suppressed }.map { it to people(ctx.ref, it) }.filter { it.second.isNotEmpty() }
        if (lists.isEmpty()) {
            val substitute = node.child("substitute") ?: return ""
            for (s in substitute.children) {
                val before = ctx.filled
                val used = mutableSetOf<String>()
                ctx.recording = used
                val out = if (s.name == "names" && s.child("name") == null) {
                    format(s, names(s, ctx, nameNode, etAlNode, labelNode), ctx.terms, true)
                } else render(s, ctx)
                ctx.recording = null
                if (out.isNotBlank()) {
                    ctx.suppressed += used
                    // What stood in for the authors is not repeated later (e.g. the title).
                    s.attrs["variable"]?.split(Regex("\\s+"))?.let { ctx.suppressed += it }
                    if (s.name == "text") s.attrs["variable"]?.let { ctx.suppressed += it }
                    if (ctx.filled == before) ctx.filled++
                    if (ctx.firstNames == null) ctx.firstNames = out
                    return out
                }
            }
            return ""
        }
        ctx.filled++
        val delimiter = node.attrs["delimiter"] ?: ", "
        val out = lists.joinToString(delimiter) { (v, people) ->
            val opts = ctx.nameOpts + nameNode?.attrs.orEmpty()
            val namesText = if (opts["form"] == "count") people.size.toString() else nameList(people, opts, nameNode, etAlNode, ctx)
            val label = labelNode?.let { l -> format(l, label(l, ctx, v, people.size), ctx.terms, true) }.orEmpty()
            when {
                label.isEmpty() -> namesText
                labelFirst -> label + namesText
                else -> namesText + label
            }
        }
        if (ctx.firstNames == null) ctx.firstNames = out
        return out
    }

    private fun nameList(people: List<Person>, opts: Map<String, String>, nameNode: CslNode?, etAlNode: CslNode?, ctx: Ctx): String {
        val etAlMin = opts["et-al-min"]?.toIntOrNull() ?: 0
        val etAlFirst = opts["et-al-use-first"]?.toIntOrNull() ?: 1
        val useLast = opts["et-al-use-last"] == "true"
        val shorten = etAlMin > 0 && people.size >= etAlMin && etAlFirst < people.size
        val shown = if (shorten) people.take(etAlFirst.coerceAtLeast(1)) else people
        val delimiter = opts["delimiter"] ?: ", "
        val and = when (opts["and"]) {
            "text" -> ctx.terms.get("and")
            "symbol" -> "&"
            else -> null
        }
        val sortOrder = opts["name-as-sort-order"]
        val rendered = shown.mapIndexed { i, p ->
            val inverted = sortOrder == "all" || sortOrder == "first" && i == 0
            formatName(p, opts, inverted, nameNode, ctx) to inverted
        }
        val sb = StringBuilder()
        rendered.forEachIndexed { i, (text, _) ->
            if (i > 0) {
                val last = i == rendered.lastIndex && !shorten
                if (last && and != null) {
                    val precedes = when (opts["delimiter-precedes-last"] ?: "contextual") {
                        "always" -> true
                        "never" -> false
                        "after-inverted-name" -> rendered[i - 1].second
                        else -> rendered.size > 2
                    }
                    sb.append(if (precedes) delimiter else " ").append(and).append(" ")
                } else sb.append(delimiter)
            }
            sb.append(text)
        }
        if (shorten) {
            if (useLast && people.size > shown.size + 1) {
                sb.append(delimiter).append("… ").append(formatName(people.last(), opts, sortOrder == "all", nameNode, ctx))
            } else {
                val term = etAlNode?.attrs?.get("term") ?: "et-al"
                val etAl = ctx.terms.get(term).let { t -> etAlNode?.let { format(it, t, ctx.terms, true) } ?: t }
                val precedes = when (opts["delimiter-precedes-et-al"] ?: "contextual") {
                    "always" -> true
                    "never" -> false
                    "after-inverted-name" -> rendered.last().second
                    else -> shown.size > 1
                }
                sb.append(if (precedes) delimiter.trimEnd() + " " else " ").append(etAl)
            }
        }
        return sb.toString()
    }

    private fun formatName(p: Person, opts: Map<String, String>, inverted: Boolean, nameNode: CslNode?, ctx: Ctx): String {
        val parts = nameNode?.children?.filter { it.name == "name-part" }.orEmpty()
        fun part(kind: String, text: String): String {
            val n = parts.firstOrNull { it.attrs["name"] == kind } ?: return text
            return format(n, text, ctx.terms, true)
        }
        if (p.literal || p.given.isBlank()) return part("family", p.family)
        val family = part("family", p.family)
        if (opts["form"] == "short") return family
        val initializeWith = opts["initialize-with"]
        val given = part("given", if (initializeWith != null && opts["initialize"] != "false") initials(p.given, initializeWith) else p.given)
        return if (inverted) family + (opts["sort-separator"] ?: ", ") + given else "$given $family"
    }

    /** "Hans-Peter Karl" with ". " → "H.-P. K."; with "." → "H.-P.K.". */
    private fun initials(given: String, with: String): String {
        val separator = if (with.endsWith(" ")) " " else ""
        val mark = with.trimEnd()
        return given.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(separator) { word ->
            word.split('-').filter { it.isNotEmpty() }.joinToString("-") { it.first().uppercaseChar() + mark }
        }
    }

    // ── Labels, numbers, dates ───────────────────────────────────────────────────────────────────────────────

    private fun label(node: CslNode, ctx: Ctx, variable: String, count: Int? = null): String {
        if (variable.isEmpty() || variable == "locator") return ""
        val term = if (variable == "page") "page" else variable
        val plural = when (node.attrs["plural"] ?: "contextual") {
            "always" -> true
            "never" -> false
            else -> count?.let { it > 1 } ?: variable(ctx.ref, variable, ctx).let { v -> Regex("[-–—&,]").containsMatchIn(v) }
        }
        if (count == null && variable(ctx.ref, variable, ctx).isBlank()) return ""
        return ctx.terms.get(term, node.attrs["form"] ?: "long", plural)
    }

    private fun number(node: CslNode, ctx: Ctx): String {
        val v = node.attrs["variable"] ?: return ""
        ctx.called++
        val value = variable(ctx.ref, v, ctx)
        if (value.isBlank()) return ""
        ctx.filled++
        val n = value.trim().toIntOrNull() ?: return value
        return when (node.attrs["form"]) {
            "ordinal" -> ctx.terms.ordinal(n)
            "long-ordinal" -> ctx.terms.longOrdinal(n)
            "roman" -> roman(n)
            else -> n.toString()
        }
    }

    private class CslDate(val year: Int?, val month: Int?, val day: Int?, val literal: String = "")

    private fun date(r: Reference, v: String): CslDate? = when (v) {
        "issued" -> r.year.trim().takeIf { it.isNotEmpty() }?.let { y ->
            val digits = Regex("\\d{4}").find(y)?.value?.toIntOrNull()
            if (digits != null && y.length <= 5) CslDate(digits, null, null) else CslDate(null, null, null, y)
        }
        "accessed" -> r.accessed.takeIf { it.isNotBlank() }?.let { a ->
            runCatching { LocalDate.parse(a.take(10)) }.getOrNull()?.let { CslDate(it.year, it.monthValue, it.dayOfMonth) } ?: CslDate(null, null, null, a)
        }
        else -> null
    }

    private fun dateNode(node: CslNode, ctx: Ctx): String {
        val v = node.attrs["variable"] ?: return ""
        ctx.called++
        val d = date(ctx.ref, v) ?: return ""
        ctx.filled++
        if (d.literal.isNotEmpty()) return d.literal
        val wanted = node.attrs["date-parts"] ?: "year-month-day"
        val localized = node.attrs["form"]
        val partNodes: List<CslNode> = if (localized != null) {
            val base = ctx.terms.dateForm(localized)
            // Date-part children of a localized date only change the formatting of that part.
            base.map { b -> node.children.firstOrNull { it.name == "date-part" && it.attrs["name"] == b.attrs["name"] }?.let { o -> CslNode("date-part", b.attrs + o.attrs.filterKeys { it != "prefix" && it != "suffix" }, emptyList(), "") } ?: b }
                .filter { p -> when (p.attrs["name"]) { "day" -> wanted == "year-month-day"; "month" -> wanted != "year"; else -> true } }
        } else node.children.filter { it.name == "date-part" }
        val delimiter = node.attrs["delimiter"] ?: ""
        val out = partNodes.mapNotNull { p ->
            val text = when (p.attrs["name"]) {
                "year" -> d.year?.toString()
                "month" -> d.month?.let { m ->
                    when (p.attrs["form"] ?: "long") {
                        "numeric" -> m.toString()
                        "numeric-leading-zeros" -> "%02d".format(m)
                        "short" -> ctx.terms.get("month-%02d".format(m), "short")
                        else -> ctx.terms.get("month-%02d".format(m))
                    }
                }
                "day" -> d.day?.let { dd ->
                    when (p.attrs["form"]) {
                        "numeric-leading-zeros" -> "%02d".format(dd)
                        "ordinal" -> ctx.terms.ordinal(dd)
                        else -> dd.toString()
                    }
                }
                else -> null
            } ?: return@mapNotNull null
            format(p, text, ctx.terms, true)
        }
        val joined = out.joinToString(delimiter).trim()
        // The year suffix (a/b) follows the year where the style does not print it itself.
        return if (v == "issued" && ctx.suffix.isNotEmpty() && !usesYearSuffix) joined + ctx.suffix else joined
    }

    private val usesYearSuffix: Boolean by lazy { "year-suffix" in xml }

    // ── Variables ────────────────────────────────────────────────────────────────────────────────────────────

    private fun people(r: Reference, v: String): List<Person> = when (v) {
        "author" -> r.authors
        "editor" -> r.editors
        "container-author" -> emptyList()
        else -> emptyList()
    }

    private fun has(r: Reference, v: String, ctx: Ctx): Boolean = when {
        v in NAME_VARS -> people(r, v).isNotEmpty()
        v in DATE_VARS -> date(r, v) != null
        else -> variable(r, v, ctx).isNotBlank()
    }

    private fun variable(r: Reference, v: String, ctx: Ctx, short: Boolean = false): String = when (v) {
        "title" -> if (short) r.title.substringBefore(": ").substringBefore(". ") else r.title
        "title-short" -> r.title.substringBefore(": ").substringBefore(". ")
        "container-title" -> if (r.type == RefType.WEB && r.container.isBlank()) "" else r.container
        "container-title-short", "journalAbbreviation" -> r.container
        "volume" -> r.volume
        "issue" -> r.issue
        "page" -> r.pages.replace(Regex("\\s*[-–—]+\\s*"), ctx.terms.get("page-range-delimiter").ifBlank { "–" })
        "page-first" -> r.pages.split(Regex("[-–—]")).first().trim()
        "edition" -> r.edition.let { e -> if (e.filter(Char::isDigit) == "1" && e.length <= 3) "" else e }
        "publisher" -> when (r.type) {
            RefType.THESIS -> r.institution.ifBlank { r.publisher }
            RefType.REPORT -> r.publisher.ifBlank { r.institution }
            else -> r.publisher
        }
        "publisher-place" -> r.place
        "authority" -> if (r.type == RefType.REPORT) r.institution else ""
        "DOI" -> r.doi
        "URL" -> if (r.doi.isNotBlank() && r.type != RefType.WEB) "" else r.url
        "ISBN" -> r.isbn
        "ISSN" -> r.issn
        "genre" -> if (r.type == RefType.REPORT && isNumeric(r.genre)) "" else r.genre
        "number" -> if (r.type == RefType.REPORT && isNumeric(r.genre)) r.genre else ""
        "abstract" -> r.abstract
        "language" -> r.language
        "citation-number" -> ctx.number.toString()
        "year-suffix" -> ctx.suffix
        "citation-label" -> (r.creators.firstOrNull()?.family?.take(4).orEmpty() + r.year.takeLast(2))
        else -> ""
    }

    private fun cslType(r: Reference) = when (r.type) {
        RefType.ARTICLE -> "article-journal"
        RefType.BOOK, RefType.EDITED_BOOK -> "book"
        RefType.CHAPTER -> "chapter"
        RefType.CONFERENCE -> "paper-conference"
        RefType.THESIS -> "thesis"
        RefType.REPORT -> "report"
        RefType.WEB -> "webpage"
        RefType.OTHER -> "document"
    }

    // ── Formatting ───────────────────────────────────────────────────────────────────────────────────────────

    private fun format(node: CslNode, text: String, terms: Terms, applyAffixes: Boolean): String {
        if (text.isEmpty()) return ""
        val a = node.attrs
        var t = text
        if (a["strip-periods"] == "true") t = t.replace(".", "")
        t = when (a["text-case"]) {
            "lowercase" -> t.lowercase()
            "uppercase" -> t.uppercase()
            "capitalize-first" -> t.replaceFirstChar { it.uppercaseChar() }
            "capitalize-all" -> t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
            "title" -> if (terms.lang == "en" && terms.englishItem) titleCase(t) else t
            "sentence" -> t.replaceFirstChar { it.uppercaseChar() }
            else -> t
        }
        if (a["quotes"] == "true") t = terms.get("open-quote") + t + terms.get("close-quote")
        if (a["font-style"] == "italic" || a["font-style"] == "oblique") t = "*" + t.trim() + "*"
        if (a["font-weight"] == "bold") t = "**" + t.trim() + "**"
        if (applyAffixes) t = (a["prefix"] ?: "") + t + (a["suffix"] ?: "")
        return t
    }

    private fun titleCase(s: String): String = s.split(' ').mapIndexed { i, w ->
        if (i > 0 && w.lowercase() in SMALL_WORDS) w else if (w.any { it.isUpperCase() }) w else w.replaceFirstChar { it.uppercaseChar() }
    }.joinToString(" ")

    private fun tidy(s: String): String = s
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\(\\s*\\)|\\[\\s*]"), "")
        .replace(Regex("\\s+([.,;:])"), "$1")
        .replace(Regex("([.,;:])\\1+"), "$1")
        .replace(Regex("\\.(\\*{1,2})\\."), ".$1")
        .replace(Regex(",\\s*\\."), ".")
        .replace(Regex("([?!])\\."), "$1")
        .replace(Regex("([?!])(\\*{1,2})\\."), "$1$2")
        .replace("**", "\u0000").replace("*.*", "").replace("\u0000", "**")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun plain(s: String) = s.replace("*", "").lowercase().trim()

    // ── Terms ────────────────────────────────────────────────────────────────────────────────────────────────

    /** The terms of the language: the locale parts of the style first, then the built-in German or English terms. */
    private fun terms(lang: String?, item: Reference? = null): Terms {
        val tag = (defaultLocale ?: lang ?: "en").lowercase()
        val primary = tag.substringBefore('-')
        val base = if (primary == "de") BUILTIN_DE else BUILTIN_EN
        val fromStyle = mutableMapOf<String, Pair<String, String>>()
        // Order of precedence: exact tag, primary language, locale without language.
        val ordered = locales.filter { it.attrs["xml:lang"].isNullOrBlank() } +
            locales.filter { it.attrs["xml:lang"]?.lowercase()?.substringBefore('-') == primary && it.attrs["xml:lang"]?.lowercase() != tag } +
            locales.filter { it.attrs["xml:lang"]?.lowercase() == tag }
        ordered.forEach { loc ->
            loc.child("terms")?.children?.filter { it.name == "term" }?.forEach { t ->
                val name = t.attrs["name"] ?: return@forEach
                val form = t.attrs["form"] ?: "long"
                val single = t.child("single")?.text ?: t.text
                val multiple = t.child("multiple")?.text ?: single
                fromStyle["$name|$form"] = single to multiple
            }
        }
        val dateForms = mutableMapOf<String, List<CslNode>>()
        ordered.forEach { loc -> loc.children.filter { it.name == "date" }.forEach { d -> d.attrs["form"]?.let { dateForms[it] = d.children.filter { c -> c.name == "date-part" } } } }
        return Terms(primary.ifEmpty { "en" }, item?.let(::english) ?: true, fromStyle, base, dateForms)
    }

    class Terms(
        val lang: String,
        /** Title case only for English works (CSL rule); works without language count as English unless the title looks German. */
        val englishItem: Boolean = true,
        private val style: Map<String, Pair<String, String>>,
        private val builtIn: Map<String, Pair<String, String>>,
        private val dateForms: Map<String, List<CslNode>>,
    ) {
        fun get(name: String, form: String = "long", plural: Boolean = false): String {
            val forms = when (form) {
                "verb-short" -> listOf("verb-short", "verb", "long")
                "symbol" -> listOf("symbol", "short", "long")
                "short" -> listOf("short", "long")
                else -> listOf(form, "long")
            }
            for (f in forms) {
                (style["$name|$f"] ?: builtIn["$name|$f"])?.let { return if (plural) it.second else it.first }
            }
            return ""
        }

        fun ordinal(n: Int): String {
            if (lang == "de") return "$n."
            val suffix = when {
                n % 100 in 11..13 -> get("ordinal").ifEmpty { "th" }
                n % 10 == 1 -> get("ordinal-01").ifEmpty { "st" }
                n % 10 == 2 -> get("ordinal-02").ifEmpty { "nd" }
                n % 10 == 3 -> get("ordinal-03").ifEmpty { "rd" }
                else -> get("ordinal").ifEmpty { "th" }
            }
            return "$n$suffix"
        }

        fun longOrdinal(n: Int): String = get("long-ordinal-%02d".format(n)).ifEmpty { ordinal(n) }

        fun dateForm(form: String): List<CslNode> = dateForms[form] ?: when {
            lang == "de" && form == "numeric" -> listOf(dp("day", "numeric-leading-zeros", suffix = "."), dp("month", "numeric-leading-zeros", suffix = "."), dp("year"))
            lang == "de" -> listOf(dp("day", suffix = ". "), dp("month", suffix = " "), dp("year"))
            form == "numeric" -> listOf(dp("month", "numeric", suffix = "/"), dp("day", suffix = "/"), dp("year"))
            else -> listOf(dp("month", suffix = " "), dp("day", suffix = ", "), dp("year"))
        }

        private fun dp(name: String, form: String? = null, suffix: String? = null) =
            CslNode("date-part", buildMap { put("name", name); form?.let { put("form", it) }; suffix?.let { put("suffix", it) } }, emptyList(), "")
    }

    companion object {
        const val KEY_PREFIX = "csl:"

        private val COLLATOR: Collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.PRIMARY }
        private val NAME_VARS = setOf("author", "editor", "translator", "container-author", "collection-editor", "composer", "director", "interviewer", "recipient", "reviewed-author", "editorial-director", "illustrator", "original-author")
        private val DATE_VARS = setOf("issued", "accessed", "original-date", "event-date", "submitted")
        private val NAME_OPTIONS = setOf(
            "and", "delimiter-precedes-et-al", "delimiter-precedes-last", "et-al-min", "et-al-use-first", "et-al-use-last",
            "initialize", "initialize-with", "name-as-sort-order", "sort-separator", "name-form", "name-delimiter", "form",
        )
        private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into", "nor", "of", "on", "or", "the", "to", "with")

        private val GERMAN_WORDS = Regex("\\b(und|der|die|das|des|den|im|zur|zum|für|mit|von|bei|über|eine?n?)\\b", RegexOption.IGNORE_CASE)

        private fun english(r: Reference): Boolean {
            val l = r.language.lowercase()
            if (l.isNotBlank()) return l.startsWith("en")
            val t = r.title + " " + r.container
            return !(t.any { it in "äöüßÄÖÜ" } || GERMAN_WORDS.containsMatchIn(t))
        }

        private fun isNumeric(v: String) = v.isNotBlank() &&
            Regex("^[A-Za-z]{0,2}\\d+[A-Za-z]{0,3}(\\s*[-–&,]\\s*[A-Za-z]{0,2}\\d+[A-Za-z]{0,3})*$").matches(v.trim())

        private fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val values = listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
            var rest = n
            return buildString { values.forEach { (v, s) -> while (rest >= v) { append(s); rest -= v } } }
        }

        /** Reads a CSL file; throws [IllegalArgumentException] with a readable message for files that are no CSL style. */
        fun parse(xml: String, fallbackId: String = ""): CslStyle {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // No external entities (files come from the internet or from others).
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val doc = runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().removePrefix("﻿").toByteArray(Charsets.UTF_8))) }
                .getOrElse { throw IllegalArgumentException(io.github.thepytales.llmhub.ui.tr("Die Datei ist kein lesbares XML: {0}", it.message ?: "")) }
            val root = doc.documentElement
            if (root.tagName.substringAfter(':') != "style") throw IllegalArgumentException(io.github.thepytales.llmhub.ui.tr("Die Datei ist kein CSL-Zitierstil."))
            val tree = CslNode.of(root)
            val info = tree.child("info")
            val title = info?.child("title")?.text?.trim().orEmpty()
            val idUrl = info?.child("id")?.text?.trim().orEmpty()
            val parent = info?.children?.firstOrNull { it.name == "link" && it.attrs["rel"] == "independent-parent" }?.attrs?.get("href")
            val format = info?.children?.firstOrNull { it.name == "category" && it.attrs["citation-format"] != null }?.attrs?.get("citation-format").orEmpty()
            val id = slug(idUrl.substringAfterLast('/').ifBlank { fallbackId.ifBlank { title } })
            val style = CslStyle(
                id = id.ifBlank { "stil" },
                title = title.ifBlank { id },
                format = format,
                defaultLocale = tree.attrs["default-locale"],
                parentId = parent?.substringAfterLast('/')?.let(::slug),
                styleAttrs = tree.attrs,
                macros = tree.children.filter { it.name == "macro" }.associateBy { it.attrs["name"].orEmpty() },
                locales = tree.children.filter { it.name == "locale" },
                citation = tree.child("citation"),
                bibliography = tree.child("bibliography"),
                xml = xml,
            )
            if (style.citation == null && style.bibliography == null && style.parentId == null) {
                throw IllegalArgumentException(io.github.thepytales.llmhub.ui.tr("Der Zitierstil enthält weder Zitate noch ein Literaturverzeichnis."))
            }
            return style
        }

        fun slug(s: String) = Reference.normalize(s).replace(Regex("[^a-z0-9]+"), "-").trim('-')

        // Built-in terms (the most used part of the official CSL locales de-DE and en-US).
        private fun terms(vararg t: Pair<String, Any>): Map<String, Pair<String, String>> = t.associate { (k, v) ->
            (if ('|' in k) k else "$k|long") to when (v) {
                is Pair<*, *> -> v.first as String to v.second as String
                else -> v as String to v
            }
        }

        private val MONTHS_DE = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
        private val MONTHS_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

        private val BUILTIN_DE: Map<String, Pair<String, String>> = terms(
            "and" to "und", "et-al" to "u. a.", "and others" to "und andere", "anonymous" to "ohne Autor", "anonymous|short" to "o. A.",
            "accessed" to "zugegriffen", "retrieved" to "abgerufen", "available at" to "verfügbar unter", "from" to "von", "in" to "in",
            "in press" to "im Druck", "no date" to "ohne Datum", "no date|short" to "o. J.", "online" to "online", "presented at" to "gehalten auf der",
            "cited" to "zitiert", "by" to "von", "ibid" to "ebd.", "at" to "auf", "version" to "Version", "forthcoming" to "i. E.",
            "edition" to ("Auflage" to "Auflagen"), "edition|short" to "Aufl.",
            "editor" to ("Herausgeber" to "Herausgeber"), "editor|short" to "Hrsg.", "editor|verb" to "herausgegeben von", "editor|verb-short" to "hg. v.",
            "translator" to ("Übersetzer" to "Übersetzer"), "translator|short" to "Übers.", "translator|verb" to "übersetzt von", "translator|verb-short" to "übers. v.",
            "container-author|verb" to "von", "author|verb" to "von",
            "page" to ("Seite" to "Seiten"), "page|short" to "S.", "volume" to ("Band" to "Bände"), "volume|short" to ("Bd." to "Bd."),
            "issue" to ("Heft" to "Hefte"), "issue|short" to "H.", "number" to ("Nummer" to "Nummern"), "number|short" to "Nr.",
            "chapter" to ("Kapitel" to "Kapitel"), "chapter|short" to "Kap.", "paragraph|short" to "Abs.", "section|short" to "Abschn.",
            "figure|short" to "Abb.", "line|short" to "Z.", "note|short" to "Anm.",
            "page-range-delimiter" to "–", "open-quote" to "„", "close-quote" to "“", "open-inner-quote" to "‚", "close-inner-quote" to "‘",
            "ordinal" to ".",
            *MONTHS_DE.mapIndexed { i, m -> "month-%02d".format(i + 1) to m }.toTypedArray(),
            *MONTHS_DE.mapIndexed { i, m -> "month-%02d|short".format(i + 1) to (if (m.length <= 4) m else m.take(3) + ".") }.toTypedArray(),
            *listOf("erste", "zweite", "dritte", "vierte", "fünfte", "sechste", "siebte", "achte", "neunte", "zehnte").mapIndexed { i, w -> "long-ordinal-%02d".format(i + 1) to w }.toTypedArray(),
        )

        private val BUILTIN_EN: Map<String, Pair<String, String>> = terms(
            "and" to "and", "et-al" to "et al.", "and others" to "and others", "anonymous" to "anonymous", "anonymous|short" to "anon.",
            "accessed" to "accessed", "retrieved" to "retrieved", "available at" to "available at", "from" to "from", "in" to "in",
            "in press" to "in press", "no date" to "no date", "no date|short" to "n.d.", "online" to "online", "presented at" to "presented at the",
            "cited" to "cited", "by" to "by", "ibid" to "ibid.", "at" to "at", "version" to "version", "forthcoming" to "forthcoming",
            "edition" to ("edition" to "editions"), "edition|short" to ("ed." to "eds."),
            "editor" to ("editor" to "editors"), "editor|short" to ("ed." to "eds."), "editor|verb" to "edited by", "editor|verb-short" to "ed. by",
            "translator" to ("translator" to "translators"), "translator|short" to ("trans." to "trans."), "translator|verb" to "translated by", "translator|verb-short" to "trans. by",
            "container-author|verb" to "by", "author|verb" to "by",
            "page" to ("page" to "pages"), "page|short" to ("p." to "pp."), "volume" to ("volume" to "volumes"), "volume|short" to ("vol." to "vols."),
            "issue" to ("issue" to "issues"), "issue|short" to ("no." to "nos."), "number" to ("number" to "numbers"), "number|short" to ("no." to "nos."),
            "chapter" to ("chapter" to "chapters"), "chapter|short" to ("chap." to "chaps."), "paragraph|short" to "para.", "section|short" to "sec.",
            "figure|short" to "fig.", "line|short" to "l.", "note|short" to "n.",
            "page-range-delimiter" to "–", "open-quote" to "“", "close-quote" to "”", "open-inner-quote" to "‘", "close-inner-quote" to "’",
            "ordinal" to "th", "ordinal-01" to "st", "ordinal-02" to "nd", "ordinal-03" to "rd", "ordinal-11" to "th", "ordinal-12" to "th", "ordinal-13" to "th",
            *MONTHS_EN.mapIndexed { i, m -> "month-%02d".format(i + 1) to m }.toTypedArray(),
            *MONTHS_EN.mapIndexed { i, m -> "month-%02d|short".format(i + 1) to (if (m.length <= 4) m else m.take(3) + ".") }.toTypedArray(),
            *listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth").mapIndexed { i, w -> "long-ordinal-%02d".format(i + 1) to w }.toTypedArray(),
        )
    }
}

/** An element of a CSL file (only elements and their text; the rest of the DOM is not needed). */
class CslNode(val name: String, val attrs: Map<String, String>, val children: List<CslNode>, val text: String) {
    fun child(n: String): CslNode? = children.firstOrNull { it.name == n }

    companion object {
        fun of(e: Element): CslNode {
            val attrs = (0 until e.attributes.length).associate { i -> e.attributes.item(i).let { it.nodeName to it.nodeValue } }
            val kids = (0 until e.childNodes.length).mapNotNull { i -> (e.childNodes.item(i) as? Element)?.let(::of) }
            val text = if (kids.isEmpty()) e.textContent.orEmpty() else (0 until e.childNodes.length)
                .map { e.childNodes.item(it) }.filter { it.nodeType == Node.TEXT_NODE }.joinToString("") { it.textContent }
            return CslNode(e.tagName.substringAfter(':'), attrs, kids, text)
        }
    }
}
