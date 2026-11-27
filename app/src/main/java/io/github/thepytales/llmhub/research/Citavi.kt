package io.github.thepytales.llmhub.research

import io.github.thepytales.llmhub.files.SqliteReader
import io.github.thepytales.llmhub.ui.tr

/**
 * Citavi übernehmen (AP-138 d): reads a Citavi project (`.ctv6`, `.ctv5`, `.ctv4` – SQLite databases) with titles,
 * persons, journals, publishers, notes, keywords, categories and knowledge items (quotations, own thoughts). Keywords
 * become [Reference.keywords], categories become lists of the collection („Citavi: Kategorie“), the abstract stays,
 * notes and knowledge items go into the note. Contributions in edited books take book, editors and publisher of their
 * parent. The schema differs a little between Citavi versions, so columns are looked up by several names.
 */
object Citavi {
    data class Result(val refs: List<Reference>, val categories: Int, val keywords: Int, val knowledge: Int)

    fun isProject(bytes: ByteArray) = SqliteReader.isSqlite(bytes)

    fun read(bytes: ByteArray, projectName: String = "Citavi"): Result {
        val db = SqliteReader(bytes)
        if (db.table("Reference") == null) throw IllegalArgumentException(tr("Die Datei ist kein Citavi-Projekt (keine Tabelle „Reference“)."))
        fun rows(vararg names: String) = names.firstNotNullOfOrNull { n -> db.table(n)?.let { db.read(n) } }.orEmpty()

        val persons = rows("Person").associateBy { id(it, "ID") }
        val periodicals = rows("Periodical").associateBy { id(it, "ID") }
        val publishers = rows("Publisher").associateBy { id(it, "ID") }
        val keywords = rows("Keyword").associateBy { id(it, "ID") }
        val categories = rows("Category").associateBy { id(it, "ID") }

        fun links(table: String, item: String): Map<String, List<String>> = rows(table)
            .sortedBy { (col(it, "Index", "Position", "SortOrder") as? Long) ?: 0L }
            .groupBy({ id(it, "ReferenceID") }, { id(it, item) })

        val authors = links("ReferenceAuthor", "PersonID")
        val editors = links("ReferenceEditor", "PersonID")
        val orgs = links("ReferenceOrganization", "PersonID")
        val refPublishers = links("ReferencePublisher", "PublisherID")
        val refKeywords = links("ReferenceKeyword", "KeywordID")
        val refCategories = links("ReferenceCategory", "CategoryID")
        val knowledge = rows("KnowledgeItem").groupBy { id(it, "ReferenceID") }

        fun person(pid: String): Person? {
            val p = persons[pid] ?: return null
            val last = text(p, "LastName").trim()
            val first = listOf(text(p, "FirstName"), text(p, "MiddleName")).filter { it.isNotBlank() }.joinToString(" ")
            val prefix = text(p, "Prefix").trim()
            if (last.isBlank()) return null
            val family = if (prefix.isNotBlank()) "$prefix $last" else last
            return if (first.isBlank()) Person(family, literal = true) else Person(family, first)
        }

        val raw = db.read("Reference")
        val byId = raw.associateBy { id(it, "ID") }

        fun categoryPath(cid: String): String {
            val names = mutableListOf<String>()
            var cur: String? = cid
            var guard = 0
            while (cur != null && guard++ < 10) {
                val c = categories[cur] ?: break
                names += text(c, "Name").ifBlank { text(c, "Title") }
                cur = id(c, "ParentID", "ParentCategoryID").ifBlank { null }
            }
            return names.asReversed().filter { it.isNotBlank() }.joinToString(" › ")
        }

        var kwCount = 0
        var kiCount = 0
        val usedCategories = mutableSetOf<String>()
        val refs = raw.mapNotNull { row ->
            val rid = id(row, "ID")
            val parent = id(row, "ParentReferenceID").ifBlank { null }?.let { byId[it] }
            val type = type(text(row, "ReferenceType"))
            val title = listOf(text(row, "Title"), text(row, "Subtitle")).filter { it.isNotBlank() }.joinToString(": ")
            if (title.isBlank() && authors[rid].isNullOrEmpty()) return@mapNotNull null
            val pid = parent?.let { id(it, "ID") }
            val kws = refKeywords[rid].orEmpty().mapNotNull { keywords[it]?.let { k -> text(k, "Name") } }.filter { it.isNotBlank() }
            kwCount += kws.size
            val cats = refCategories[rid].orEmpty().map(::categoryPath).filter { it.isNotBlank() }
            usedCategories += cats
            val items = knowledge[rid].orEmpty()
            kiCount += items.size
            val notes = buildList {
                text(row, "Notes").let(::plain).takeIf { it.isNotBlank() }?.let { add(it) }
                text(row, "Evaluation").let(::plain).takeIf { it.isNotBlank() }?.let { add(tr("Bewertung: {0}", it)) }
                items.forEach { k ->
                    val core = plain(text(k, "CoreStatement"))
                    val body = plain(text(k, "Text"))
                    val page = pageText(text(k, "PageRange"))
                    val line = listOf(core, body).filter { it.isNotBlank() }.joinToString(" – ")
                    if (line.isNotBlank()) add("• " + line + (if (page.isNotBlank()) " (S. $page)" else ""))
                }
            }
            val periodical = id(row, "PeriodicalID").ifBlank { null }?.let { periodicals[it] }
            val pubs = (refPublishers[rid] ?: pid?.let { refPublishers[it] }).orEmpty().mapNotNull { publishers[it]?.let { p -> text(p, "Name") } }
            val year = text(row, "Year").ifBlank { text(row, "YearResolved") }.ifBlank { parent?.let { text(it, "Year") }.orEmpty() }
            val doi = Reference.cleanDoi(text(row, "Doi").ifBlank { text(row, "DOI") })
            Reference(
                type = type,
                authors = (authors[rid].orEmpty() + if (type == RefType.REPORT || authors[rid].isNullOrEmpty() && type != RefType.EDITED_BOOK) orgs[rid].orEmpty() else emptyList()).mapNotNull(::person),
                editors = (editors[rid] ?: pid?.let { editors[it] }).orEmpty().mapNotNull(::person),
                year = Regex("\\d{4}").find(year)?.value ?: year.trim(),
                title = title,
                container = when {
                    periodical != null -> text(periodical, "Name")
                    parent != null -> listOf(text(parent, "Title"), text(parent, "Subtitle")).filter { it.isNotBlank() }.joinToString(": ")
                    else -> ""
                },
                volume = text(row, "Volume"),
                issue = text(row, "Number"),
                pages = pageText(text(row, "PageRange")),
                edition = text(row, "Edition").ifBlank { parent?.let { text(it, "Edition") }.orEmpty() },
                publisher = if (type == RefType.ARTICLE) "" else pubs.joinToString(", "),
                place = if (type == RefType.ARTICLE) "" else text(row, "PlaceOfPublication").ifBlank { parent?.let { text(it, "PlaceOfPublication") }.orEmpty() },
                doi = doi,
                url = text(row, "OnlineAddress").takeIf { doi.isBlank() }.orEmpty(),
                isbn = text(row, "Isbn").ifBlank { text(row, "ISBN") }.ifBlank { parent?.let { text(it, "Isbn") }.orEmpty() },
                issn = periodical?.let { text(it, "Issn").ifBlank { text(it, "ISSN") } }.orEmpty(),
                accessed = text(row, "AccessDate").let { a -> Regex("\\d{4}-\\d{2}-\\d{2}").find(a)?.value ?: germanDate(a) },
                institution = if (type == RefType.THESIS || type == RefType.REPORT) pubs.firstOrNull().orEmpty() else "",
                genre = text(row, "SpecificField1").takeIf { type == RefType.THESIS }.orEmpty(),
                abstract = plain(text(row, "Abstract")),
                language = text(row, "Language"),
                source = "Citavi",
                note = notes.joinToString("\n"),
                keywords = kws.distinct(),
                lists = (cats.map { "Citavi: $it" }.ifEmpty { listOf("Citavi: $projectName") }).distinct(),
            )
        }
        return Result(refs, usedCategories.size, kwCount, kiCount)
    }

    /** Citavi stores types like „JournalArticle“, „BookEdited“, „Contribution“. */
    fun type(t: String): RefType {
        val n = t.lowercase()
        return when {
            "journal" in n || "newspaper" in n || "article" in n && "internet" !in n -> RefType.ARTICLE
            n == "bookedited" || "edited" in n || "collected" in n -> RefType.EDITED_BOOK
            "contribution" in n || "chapter" in n -> RefType.CHAPTER
            "conference" in n || "proceedings" in n || "lecture" in n -> RefType.CONFERENCE
            "thesis" in n || "dissertation" in n -> RefType.THESIS
            "report" in n || "unpublished" in n || "grey" in n || "standard" in n || "researchpaper" in n -> RefType.REPORT
            "internet" in n || "web" in n || "online" in n -> RefType.WEB
            "book" in n || "monograph" in n -> RefType.BOOK
            else -> RefType.OTHER
        }
    }

    /** Citavi writes page ranges as small XML: `<sp><n>407</n>…</sp><ep>…</ep><os>407-426</os>`. */
    fun pageText(s: String): String {
        if ('<' !in s) return s.trim()
        Regex("<os>(.*?)</os>", RegexOption.DOT_MATCHES_ALL).findAll(s).lastOrNull()?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val start = Regex("<sp>.*?<n>(\\d+)</n>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)
        val end = Regex("<ep>.*?<n>(\\d+)</n>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)
        return listOfNotNull(start, end).distinct().joinToString("–")
    }

    /** Rich text of Citavi (RTF or XAML/HTML) as plain text. */
    fun plain(s: String): String {
        if (s.isBlank()) return ""
        var t = s
        if (t.trimStart().startsWith("{\\rtf")) {
            t = t.replace(Regex("\\\\par[d]?\\b"), "\n").replace(Regex("\\{\\\\\\*[^{}]*}"), "").replace(Regex("\\\\'([0-9a-fA-F]{2})")) { m ->
                String(byteArrayOf(m.groupValues[1].toInt(16).toByte()), Charsets.ISO_8859_1)
            }.replace(Regex("\\\\u(-?\\d+)\\??")) { m -> m.groupValues[1].toInt().let { (if (it < 0) it + 65536 else it).toChar().toString() } }
                .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "").replace(Regex("[{}]"), "")
        }
        if ('<' in t) t = t.replace(Regex("<(br|/p|/Paragraph)[^>]*>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()
    }

    private fun germanDate(s: String): String = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").find(s)?.destructured?.let { (d, m, y) -> "%s-%02d-%02d".format(y, m.toInt(), d.toInt()) } ?: ""

    private fun col(row: Map<String, Any?>, vararg names: String): Any? = names.firstNotNullOfOrNull { n -> row.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value }

    private fun text(row: Map<String, Any?>, vararg names: String): String = when (val v = col(row, *names)) {
        null -> ""
        is ByteArray -> String(v, Charsets.UTF_8)
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** Ids are GUIDs (text or 16 bytes) or numbers. */
    private fun id(row: Map<String, Any?>, vararg names: String): String = when (val v = col(row, *names)) {
        null -> ""
        is ByteArray -> v.joinToString("") { "%02x".format(it) }
        else -> v.toString().lowercase().trim('{', '}')
    }
}
