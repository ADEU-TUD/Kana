package io.github.thepytales.llmhub.docs

import io.github.thepytales.llmhub.ui.Block
import io.github.thepytales.llmhub.ui.Segment
import io.github.thepytales.llmhub.ui.inlineSegments
import io.github.thepytales.llmhub.ui.parseBlocks
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes Markdown as a minimal Word document (Office Open XML), without external libraries: headings, lists,
 * tables, real footnotes, pictures, page breaks, landscape sections and header/footer with "Seite X von Y"
 * (see [DocMarkup]).
 */
object MarkdownDocx {
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val TEXT_WIDTH = 9638 // A4 minus 2 cm margins, in twentieths of a point
    private const val TEXT_WIDTH_LANDSCAPE = 14570 // A4 landscape minus 2 cm margins
    private const val TEXT_HEIGHT = 14570
    private const val TEXT_HEIGHT_LANDSCAPE = 9638

    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CT = "application/vnd.openxmlformats-officedocument.wordprocessingml"

    /** A picture of the document in word/media. */
    private class Media(val name: String, val rel: String, val bytes: ByteArray)

    /** A real Word chart (AP-49): part number, relationship id and the chart with its embedded workbook. */
    private class ChartPart(val n: Int, val rel: String, val embedded: OfficeCharts.Embedded)

    /** What one export collects while writing the body: footnote numbers, pictures, the current page orientation. */
    private class Ctx(val notes: List<DocMarkup.Note>, val header: Boolean, val footer: Boolean) {
        val numbers = notes.associate { it.label to it.number }
        val media = mutableListOf<Media>()
        val charts = mutableListOf<ChartPart>()
        var landscape = false
        var drawingId = 10
        /** A picture with Textumbruch (AP-125 e) was written: the next block of its own starts below it. */
        var floating = false
        /** Headings for an Inhaltsverzeichnis (AP-16). */
        var headings: List<Pair<Int, String>> = emptyList()
        /** Page margins in twips (AP-16). */
        var margin = 1134
        /** Formatvorlagen the document chose (AP-46); null: the look of the export style. */
        var sheet: io.github.thepytales.llmhub.docs.text.StyleSheet? = null
        /** Erste Seite anders / gerade Seiten anders (AP-45): their own header and footer parts. */
        var first = false
        var even = false
        /** Sections written so far (only the first one has the Deckblatt). */
        var sections = 0
        /** Abschnitte (AP-43/45): header and footer of each section break of the document, and the current one (0: none yet). */
        var breaks: List<Pair<String, String>> = emptyList()
        var section = 0
        /** Sprache des Dokuments (AP-126 b): Word's spelling and hyphenation follow it. */
        var language = "de-DE"
        /** Wissenschaft (AP-126 c): the targets of Querverweise, the ids of the headings in order, bookmark ids. */
        var targets: List<DocFields.Entry> = emptyList()
        var headingIds: ArrayDeque<String> = ArrayDeque()
        var bookmarks = 0
        /** Fußnoten als Endnoten (AP-126 c). */
        var endnotes = false
        /** Seitenlayout (AP-126 d): paper, page colour, watermark; the lists counted as in the editor; columns now. */
        var layout = DocMarkup.Layout()
        val lists = io.github.thepytales.llmhub.docs.text.ListStyles.Counter()
        var columns = 1
        /** Nummerierte Überschriften as a Word list (AP-126 c): on, and the numbers of the headings in order (null: title). */
        var numbered = false
        var headingNos: ArrayDeque<String?> = ArrayDeque()
        /** The section now started at a change of columns (on the same page). */
        var continuous = false
        val settings: Boolean get() = notes.isNotEmpty() || even || layout.color != null
        val width: Int get() = if (layout.paper != "a4") ((if (landscape) layout.paperTwips.second else layout.paperTwips.first) - 2 * margin).coerceAtLeast(2000) / columns
            else (if (landscape) TEXT_WIDTH_LANDSCAPE else TEXT_WIDTH) / columns
        val height: Int get() = if (landscape) TEXT_HEIGHT_LANDSCAPE else TEXT_HEIGHT
    }

    private val current = ThreadLocal<Ctx?>()
    private val ctx: Ctx get() = current.get() ?: Ctx(emptyList(), header = false, footer = true)

    private fun contentTypes(c: Ctx) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        c.media.map { it.name.substringAfterLast('.') }.filter { it != "png" }.distinct().forEach { ext ->
            append("<Default Extension=\"$ext\" ContentType=\"image/$ext\"/>")
        }
        if (c.charts.isNotEmpty()) append("<Default Extension=\"xlsx\" ContentType=\"${OfficeCharts.XLSX_TYPE}\"/>")
        c.charts.forEach { append("<Override PartName=\"/word/charts/chart${it.n}.xml\" ContentType=\"${OfficeCharts.CONTENT_TYPE}\"/>") }
        append("<Override PartName=\"/word/document.xml\" ContentType=\"$CT.document.main+xml\"/>")
        for (n in parts(c)) {
            if (c.footer) append("<Override PartName=\"/word/footer$n.xml\" ContentType=\"$CT.footer+xml\"/>")
            if (c.header) append("<Override PartName=\"/word/header$n.xml\" ContentType=\"$CT.header+xml\"/>")
        }
        if (c.notes.isNotEmpty()) {
            if (c.endnotes) append("<Override PartName=\"/word/endnotes.xml\" ContentType=\"$CT.endnotes+xml\"/>")
            else append("<Override PartName=\"/word/footnotes.xml\" ContentType=\"$CT.footnotes+xml\"/>")
        }
        if (c.settings) append("<Override PartName=\"/word/settings.xml\" ContentType=\"$CT.settings+xml\"/>")
        if (c.numbered) append("<Override PartName=\"/word/numbering.xml\" ContentType=\"$CT.numbering+xml\"/>")
        append("<Override PartName=\"/word/styles.xml\" ContentType=\"$CT.styles+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("</Types>")
    }

    private fun docRels(withLogo: Boolean, c: Ctx) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (n in parts(c)) {
            val id = if (n == 1) "" else n.toString()
            if (c.footer) append("<Relationship Id=\"rIdFooter$id\" Type=\"$R_NS/footer\" Target=\"footer$n.xml\"/>")
            if (c.header) append("<Relationship Id=\"rIdHeader$id\" Type=\"$R_NS/header\" Target=\"header$n.xml\"/>")
        }
        if (c.notes.isNotEmpty()) {
            if (c.endnotes) append("<Relationship Id=\"rIdEndnotes\" Type=\"$R_NS/endnotes\" Target=\"endnotes.xml\"/>")
            else append("<Relationship Id=\"rIdFootnotes\" Type=\"$R_NS/footnotes\" Target=\"footnotes.xml\"/>")
        }
        if (c.settings) append("<Relationship Id=\"rIdSettings\" Type=\"$R_NS/settings\" Target=\"settings.xml\"/>")
        if (c.numbered) append("<Relationship Id=\"rIdNumbering\" Type=\"$R_NS/numbering\" Target=\"numbering.xml\"/>")
        append("<Relationship Id=\"rIdStyles\" Type=\"$R_NS/styles\" Target=\"styles.xml\"/>")
        if (withLogo) append("<Relationship Id=\"rIdLogo\" Type=\"$R_NS/image\" Target=\"media/logo.png\"/>")
        c.media.forEach { append("<Relationship Id=\"${it.rel}\" Type=\"$R_NS/image\" Target=\"${it.name}\"/>") }
        c.charts.forEach { append("<Relationship Id=\"${it.rel}\" Type=\"${OfficeCharts.REL_CHART}\" Target=\"charts/chart${it.n}.xml\"/>") }
        append("</Relationships>")
    }

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

    /**
     * Accessibility: German as document language (spell check, screen readers) and real heading styles, so the
     * structure shows in the navigation pane and screen readers can jump between headings.
     */
    private fun styles(themeFont: String) = buildString {
        val lang = ctx.language
        // Formatvorlagen of the document (AP-46) as Word styles, so „Überschrift 1 ändern“ in Word works on them too.
        val sheet = ctx.sheet
        val body = sheet?.of(io.github.thepytales.llmhub.docs.text.ParaKind.NORMAL)
        val font = body?.font ?: themeFont
        val bodySize = body?.let { (it.size * 2).toInt() } ?: 21
        val line = body?.let { (it.spacing * 240).toInt() } ?: 276
        val after = body?.let { it.after * 20 } ?: 120
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:styles xmlns:w=\"$W_NS\">")
        append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:cs=\"$font\"/>")
        append("<w:sz w:val=\"$bodySize\"/><w:lang w:val=\"${lang}\" w:eastAsia=\"${lang}\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>")
        append("<w:pPrDefault><w:pPr><w:spacing w:after=\"$after\" w:line=\"$line\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>")
        append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Standard\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>")
        fun rPr(kind: io.github.thepytales.llmhub.docs.text.ParaKind, fallback: String): String {
            val d = sheet?.of(kind) ?: return fallback
            return buildString {
                d.font?.let { append("<w:rFonts w:ascii=\"${esc(it)}\" w:hAnsi=\"${esc(it)}\" w:cs=\"${esc(it)}\"/>") }
                if (d.bold) append("<w:b/>")
                if (d.italic) append("<w:i/>")
                d.color?.let { append("<w:color w:val=\"$it\"/>") }
                append("<w:sz w:val=\"${(d.size * 2).toInt()}\"/>")
            }
        }
        fun spacing(kind: io.github.thepytales.llmhub.docs.text.ParaKind): String {
            val d = sheet?.of(kind) ?: return ""
            return "<w:spacing w:before=\"${d.before * 20}\" w:after=\"${d.after * 20}\" w:line=\"${(d.spacing * 240).toInt()}\" w:lineRule=\"auto\"/>"
        }
        for (level in 1..4) {
            val kind = io.github.thepytales.llmhub.docs.text.ParaKind.heading(level)
            append("<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\"><w:name w:val=\"heading $level\"/>")
            append("<w:basedOn w:val=\"Standard\"/><w:next w:val=\"Standard\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>")
            append("<w:pPr><w:keepNext/>${spacing(kind)}<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr><w:rPr>${rPr(kind, "<w:b/>")}</w:rPr></w:style>")
        }
        // Titel, Untertitel and Literatur (AP-46).
        append("<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Standard\"/><w:next w:val=\"Standard\"/><w:qFormat/>")
        append("<w:pPr>${spacing(io.github.thepytales.llmhub.docs.text.ParaKind.TITLE)}</w:pPr><w:rPr>${rPr(io.github.thepytales.llmhub.docs.text.ParaKind.TITLE, "<w:sz w:val=\"52\"/>")}</w:rPr></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"Subtitle\"><w:name w:val=\"Subtitle\"/><w:basedOn w:val=\"Standard\"/><w:next w:val=\"Standard\"/><w:qFormat/>")
        append("<w:pPr>${spacing(io.github.thepytales.llmhub.docs.text.ParaKind.SUBTITLE)}</w:pPr><w:rPr>${rPr(io.github.thepytales.llmhub.docs.text.ParaKind.SUBTITLE, "<w:color w:val=\"666666\"/><w:sz w:val=\"30\"/>")}</w:rPr></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"Bibliography\"><w:name w:val=\"Bibliography\"/><w:basedOn w:val=\"Standard\"/><w:qFormat/>")
        append("<w:pPr>${spacing(io.github.thepytales.llmhub.docs.text.ParaKind.LITERATURE)}<w:ind w:left=\"567\" w:hanging=\"567\"/></w:pPr><w:rPr>${rPr(io.github.thepytales.llmhub.docs.text.ParaKind.LITERATURE, "")}</w:rPr></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"FootnoteText\"><w:name w:val=\"footnote text\"/><w:basedOn w:val=\"Standard\"/>")
        append("<w:pPr><w:spacing w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:sz w:val=\"18\"/></w:rPr></w:style>")
        append("<w:style w:type=\"character\" w:styleId=\"FootnoteReference\"><w:name w:val=\"footnote reference\"/><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr></w:style>")
        if (ctx.endnotes) {
            append("<w:style w:type=\"paragraph\" w:styleId=\"EndnoteText\"><w:name w:val=\"endnote text\"/><w:basedOn w:val=\"Standard\"/>")
            append("<w:pPr><w:spacing w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:sz w:val=\"18\"/></w:rPr></w:style>")
            append("<w:style w:type=\"character\" w:styleId=\"EndnoteReference\"><w:name w:val=\"endnote reference\"/><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr></w:style>")
        }
        append("<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:rPr><w:color w:val=\"1D4ED8\"/><w:u w:val=\"single\"/></w:rPr></w:style>")
        append("<w:style w:type=\"paragraph\" w:styleId=\"Caption\"><w:name w:val=\"caption\"/><w:basedOn w:val=\"Standard\"/><w:qFormat/>")
        append("<w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"200\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"555555\"/><w:sz w:val=\"18\"/></w:rPr></w:style>")
        append("</w:styles>")
    }

    private fun core(title: String): String {
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${esc(title)}</dc:title>" +
            "<dc:language>${ctx.language}</dc:language><dc:creator>Lora</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified>" +
            "</cp:coreProperties>"
    }

    fun write(file: File, title: String, source: String, theme: DocTheme = DocTheme()) {
        // Wissenschaft (AP-126 c): cross references as the numbers are now; numbered headings as Word's own list
        // numbering (a heading's number is no text, Word counts on when headings move).
        val markdown = if (DocFields.numberedHeadings(source)) DocFields.refresh(source) else DocFields.forExport(source)
        val notes = DocMarkup.footnotes(DocMarkup.isolate(markdown))
        writeWith(file, title, markdown, notes.notes, theme, DocFields.entries(source)) { withLogo -> document(title, notes.body, theme, withLogo) }
    }

    /**
     * Word with tracked changes: the text of [newMarkdown], with what changed since [oldMarkdown] as revisions
     * (inserted / deleted, by [author]) that can be accepted or rejected in Word.
     */
    fun writeTracked(file: File, title: String, oldMarkdown: String, newMarkdown: String, theme: DocTheme = DocTheme(), author: String = "ADEU LLM") {
        val notes = DocMarkup.footnotes(DocMarkup.isolate(newMarkdown))
        val old = DocMarkup.footnotes(DocMarkup.isolate(oldMarkdown)).body
        writeWith(file, title, newMarkdown, notes.notes, theme) { withLogo -> trackedDocument(old, notes.body, theme, withLogo, author) }
    }

    /**
     * The body XML of [markdown] – paragraphs and tables, without section properties – to put into an existing Word file
     * (Word-Treue, AP-45). Null when the piece needs parts of the package (pictures, footnotes, landscape sections).
     */
    internal fun fragment(markdown: String, theme: DocTheme = DocTheme(), noteIds: List<Int> = emptyList()): String? {
        if ("![" in markdown || markdown.lineSequence().any { DocMarkup.orientation(it) != null || DocMarkup.section(it) != null }) return null
        // Footnote marks: only those of notes the file already has ([noteIds]: the ids of note 1, 2 … in the original).
        val labels = Regex("\\[\\^([^\\]]+)]").findAll(markdown).map { it.groupValues[1] }.toSet()
        val notes = labels.map { l -> DocMarkup.Note(l, l.toIntOrNull()?.let { noteIds.getOrNull(it - 1) } ?: return null, "") }
        val c = Ctx(notes, header = false, footer = false)
        c.headings = DocMarkup.tocEntries(markdown)
        current.set(c)
        try {
            val xml = buildString { for (b in parseBlocks(markdown)) block(b, theme) }
            return if (c.media.isNotEmpty() || c.charts.isNotEmpty()) null else xml
        } finally {
            current.remove()
        }
    }

    private fun writeWith(file: File, title: String, markdown: String, notes: List<DocMarkup.Note>, theme: DocTheme, targets: List<DocFields.Entry> = DocFields.entries(markdown), body: (Boolean) -> String) {
        val logo = theme.logo?.takeIf { theme.logoSize != null }
        val setup = DocMarkup.pageSetup(markdown)
        val breaks = DocMarkup.sectionMargins(setup, DocMarkup.sections(markdown))
        val layout = DocMarkup.layout(markdown)
        val c = Ctx(
            notes,
            header = setup.hasHeader || setup.pageNumbers && setup.numbersMoved && setup.numbersTop || setup.headerEven.isNotBlank() || setup.titlePage && setup.headerFirst.isNotBlank() || breaks.any { it.first.isNotBlank() } ||
                layout.watermark.isNotBlank(),
            footer = setup.pageNumbers || theme.footer.isNotBlank() || setup.footer.isNotBlank() || setup.footerEven.isNotBlank() || setup.titlePage && setup.footerFirst.isNotBlank() ||
                breaks.any { it.second.isNotBlank() },
        )
        c.breaks = breaks
        c.layout = layout
        c.margin = setup.marginTwips
        c.first = setup.titlePage
        c.even = setup.hasEven
        c.headings = DocMarkup.tocEntries(markdown)
        if (DocFields.numberedHeadings(markdown) && !markdown.lineSequence().any { Regex("^#{1,4}\\s+(<!--.*?-->)?[\\d.]+${DocFields.NUMBER_GAP}").containsMatchIn(it) }) {
            c.numbered = true
            // The Inhaltsverzeichnis shows the numbers before the headings (Word renews it with F9).
            c.headings = DocMarkup.tocEntries(DocFields.numberHeadings(markdown))
        }
        c.language = DocMarkup.language(markdown)
        c.targets = targets
        c.headingIds = ArrayDeque(targets.filter { it.kind == DocFields.Kind.HEADING }.map { it.id })
        c.endnotes = DocFields.endnotes(markdown)
        c.sheet = io.github.thepytales.llmhub.docs.text.Styles.read(markdown).takeIf { it.explicit || it.changed.isNotEmpty() }
        current.set(c)
        try {
            // The body first: it collects the pictures that the package lists.
            val document = body(logo != null)
            val footnotes = if (notes.isEmpty()) null else footnotesXml(notes, c.endnotes)
            ZipOutputStream(file.outputStream()).use { zip ->
                fun bytes(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
                fun entry(name: String, content: String) = bytes(name, content.toByteArray(Charsets.UTF_8))
                entry("[Content_Types].xml", contentTypes(c))
                entry("_rels/.rels", RELS)
                entry("word/_rels/document.xml.rels", docRels(logo != null, c))
                if (c.footer) entry("word/footer1.xml", footer(setup.footer, theme.footer, setup.pageNumbers, setup))
                if (c.header) entry("word/header1.xml", watermarked(header(setup.header, setup.date, setup.pageNumbers, setup), layout.watermark))
                // Deckblatt: own header and footer of the first page (no page number); even pages: theirs.
                if (c.first && c.footer) entry("word/footer2.xml", footer(setup.footerFirst, "", false))
                if (c.first && c.header) entry("word/header2.xml", watermarked(header(setup.headerFirst, false), layout.watermark))
                if (c.even && c.footer) entry("word/footer3.xml", footer(setup.footerEven, theme.footer, setup.pageNumbers, setup))
                if (c.even && c.header) entry("word/header3.xml", watermarked(header(setup.headerEven, setup.date, setup.pageNumbers, setup), layout.watermark))
                // Abschnitte: header and footer of each further section.
                c.breaks.forEachIndexed { k, (h, f) ->
                    if (c.footer) entry("word/footer${4 + k}.xml", footer(f, theme.footer, setup.pageNumbers, setup))
                    if (c.header) entry("word/header${4 + k}.xml", watermarked(header(h, setup.date, setup.pageNumbers, setup), layout.watermark))
                }
                if (footnotes != null) entry(if (c.endnotes) "word/endnotes.xml" else "word/footnotes.xml", footnotes)
                if (c.settings) entry("word/settings.xml", settings(c))
                if (c.numbered) entry("word/numbering.xml", headingNumbering())
                entry("word/styles.xml", styles(if (theme.style == DocStyle.TUD) "Noto Sans" else if (theme.style == DocStyle.CLASSIC) "Georgia" else "Calibri"))
                entry("docProps/core.xml", core(title.ifBlank { markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: "Dokument" }))
                if (logo != null) bytes("word/media/logo.png", logo)
                c.media.forEach { bytes("word/${it.name}", it.bytes) }
                c.charts.forEach { ch ->
                    entry("word/charts/chart${ch.n}.xml", ch.embedded.chartXml)
                    entry("word/charts/_rels/chart${ch.n}.xml.rels", ch.embedded.relsXml)
                    bytes("word/embeddings/Microsoft_Excel_Worksheet${ch.n}.xlsx", ch.embedded.workbook)
                }
                entry("word/document.xml", document)
            }
        } finally {
            current.remove()
        }
    }

    /**
     * Nummerierte Überschriften (AP-126 c) as Word's mehrstufige Liste: 1, 1.1, 1.1.1 … with a space after the number –
     * the headings name it (numPr), so Word counts and renews them itself.
     */
    private fun headingNumbering(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:numbering xmlns:w=\"$W_NS\">")
        append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"multilevel\"/>")
        for (k in 0 until 6) {
            val text = (1..k + 1).joinToString(".") { "%$it" }
            append("<w:lvl w:ilvl=\"$k\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"$text\"/><w:lvlJc w:val=\"left\"/>")
            append("<w:suff w:val=\"space\"/><w:pPr><w:ind w:left=\"0\" w:firstLine=\"0\"/></w:pPr></w:lvl>")
        }
        append("</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>")
    }

    /**
     * Wasserzeichen (AP-126 d) as Word writes it: a grey text shape in the header part, turned 45°, in the middle of the
     * page behind the text – Word lists it under *Entwurf › Wasserzeichen*.
     */
    private fun watermarked(headerXml: String, text: String): String {
        if (text.isBlank()) return headerXml
        val shape = "<w:p><w:pPr><w:pStyle w:val=\"Header\"/></w:pPr><w:r><w:pict>" +
            "<v:shapetype id=\"_x0000_t136\" coordsize=\"21600,21600\" o:spt=\"136\" adj=\"10800\" path=\"m@7,l@8,m@5,21600l@6,21600e\">" +
            "<v:formulas><v:f eqn=\"sum #0 0 10800\"/><v:f eqn=\"prod #0 2 1\"/><v:f eqn=\"sum 21600 0 @1\"/><v:f eqn=\"sum 0 0 @2\"/>" +
            "<v:f eqn=\"sum 21600 0 @3\"/><v:f eqn=\"if @0 @3 0\"/><v:f eqn=\"if @0 21600 @1\"/><v:f eqn=\"if @0 0 @2\"/><v:f eqn=\"if @0 @4 21600\"/>" +
            "<v:f eqn=\"mid @5 @6\"/><v:f eqn=\"mid @8 @5\"/><v:f eqn=\"mid @7 @8\"/><v:f eqn=\"mid @6 @7\"/><v:f eqn=\"sum @6 0 @5\"/></v:formulas>" +
            "<v:path textpathok=\"t\" o:connecttype=\"custom\" o:connectlocs=\"@9,0;@10,10800;@11,21600;@12,10800\" o:connectangles=\"270,180,90,0\"/>" +
            "<v:textpath on=\"t\" fitshape=\"t\"/><v:handles><v:h position=\"#0,bottomRight\" xrange=\"6629,14971\"/></v:handles>" +
            "<o:lock v:ext=\"edit\" text=\"t\" shapetype=\"t\"/></v:shapetype>" +
            "<v:shape id=\"PowerPlusWaterMarkObject\" o:spid=\"_x0000_s2049\" type=\"#_x0000_t136\" " +
            "style=\"position:absolute;margin-left:0;margin-top:0;width:440pt;height:110pt;rotation:315;z-index:-251657216;" +
            "mso-position-horizontal:center;mso-position-horizontal-relative:margin;mso-position-vertical:center;mso-position-vertical-relative:margin\" " +
            "o:allowincell=\"f\" fillcolor=\"silver\" stroked=\"f\"><v:fill opacity=\".5\"/>" +
            "<v:textpath style=\"font-family:&quot;Calibri&quot;;font-size:1pt\" string=\"${attr(text)}\"/></v:shape></w:pict></w:r></w:p>"
        val ns = " xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\""
        val open = Regex("<w:hdr ([^>]*)>").find(headerXml) ?: return headerXml
        return headerXml.substring(0, open.range.first) + "<w:hdr " + open.groupValues[1] + ns + ">" + shape + headerXml.substring(open.range.last + 1)
    }

    /** Numbers of the header/footer parts: 1 for all pages, 2 for the first, 3 for even pages. */
    private fun parts(c: Ctx) = listOfNotNull(1, 2.takeIf { c.first }, 3.takeIf { c.even }) + c.breaks.indices.map { 4 + it }

    /** Word links the separator lines of the footnote area from the settings; even pages get their own headers there. */
    private fun settings(c: Ctx) = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<w:settings xmlns:w=\"$W_NS\">" + (if (c.layout.color != null) "<w:displayBackgroundShape/>" else "") + (if (c.even) "<w:evenAndOddHeaders/>" else "") +
        (if (c.notes.isNotEmpty()) (if (c.endnotes) "<w:endnotePr><w:pos w:val=\"docEnd\"/><w:endnote w:id=\"-1\"/><w:endnote w:id=\"0\"/></w:endnotePr>" else "<w:footnotePr><w:footnote w:id=\"-1\"/><w:footnote w:id=\"0\"/></w:footnotePr>") else "") + "</w:settings>"

    /** The notes as Word footnotes – or, with [end] (AP-126 c), as endnotes at the end of the document. */
    private fun footnotesXml(notes: List<DocMarkup.Note>, end: Boolean = false) = buildString {
        val t = if (end) "endnote" else "footnote"
        val style = if (end) "Endnote" else "Footnote"
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:${t}s xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\">")
        append("<w:$t w:type=\"separator\" w:id=\"-1\"><w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:separator/></w:r></w:p></w:$t>")
        append("<w:$t w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:continuationSeparator/></w:r></w:p></w:$t>")
        for (n in notes) {
            append("<w:$t w:id=\"${n.number}\"><w:p><w:pPr><w:pStyle w:val=\"${style}Text\"/></w:pPr>")
            append("<w:r><w:rPr><w:rStyle w:val=\"${style}Reference\"/></w:rPr><w:${t}Ref/></w:r>")
            append("<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>")
            append(inlineSegments(n.text).joinToString("") { segRun(it, RunStyle(size = 18)) })
            append("</w:p></w:$t>")
        }
        append("</w:${t}s>")
    }

    private fun field(instr: String, shown: String, rPr: String) =
        "<w:r>$rPr<w:fldChar w:fldCharType=\"begin\"/></w:r>" +
            "<w:r>$rPr<w:instrText xml:space=\"preserve\"> $instr </w:instrText></w:r>" +
            "<w:r>$rPr<w:fldChar w:fldCharType=\"separate\"/></w:r>" +
            "<w:r>$rPr<w:t>$shown</w:t></w:r>" +
            "<w:r>$rPr<w:fldChar w:fldCharType=\"end\"/></w:r>"

    /** Text of a header or footer with its fields `{seite}`/`{seiten}` as PAGE / NUMPAGES fields. */
    private fun fieldRuns(text: String, rPr: String): String = DocMarkup.fieldPieces(text).joinToString("") { (isField, t) ->
        if (isField) field(if (t == "seite") "PAGE" else "NUMPAGES", "1", rPr) else "<w:r>$rPr<w:t xml:space=\"preserve\">${esc(t)}</w:t></w:r>"
    }

    /**
     * Header or footer with the page number at its own place (AP-126 b): [left], [center] and [right] (texts with fields)
     * at a centre and a right tab, like Word's own header and footer.
     */
    private fun threeParts(tag: String, left: String, center: String, right: String): String {
        val rPr = "<w:rPr><w:color w:val=\"888888\"/><w:sz w:val=\"16\"/></w:rPr>"
        val tab = "<w:r>$rPr<w:tab/></w:r>"
        val content = buildString {
            if (left.isNotBlank()) append(fieldRuns(left, rPr))
            if (center.isNotBlank() || right.isNotBlank()) append(tab)
            if (center.isNotBlank()) append(fieldRuns(center, rPr))
            if (right.isNotBlank()) append(tab).append(fieldRuns(right, rPr))
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:$tag xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\"><w:p><w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"${TEXT_WIDTH / 2}\"/>" +
            "<w:tab w:val=\"right\" w:pos=\"$TEXT_WIDTH\"/></w:tabs></w:pPr>$content</w:p></w:$tag>"
    }

    /** The three parts of a header ([top]) or footer, with the page number at its place. */
    private fun parts(texts: List<String>, numbers: Boolean, setup: DocMarkup.PageSetup, top: Boolean): List<String> {
        val slots = texts.map { mutableListOf(it).filter { t -> t.isNotBlank() }.toMutableList() }
        if (numbers && setup.numbersTop == top) slots[setup.numbersSlot] += setup.numbersText
        return slots.map { it.joinToString("   ·   ") }
    }

    private fun footer(own: String, org: String, numbers: Boolean, setup: DocMarkup.PageSetup): String {
        if (!setup.numbersMoved) return footer(own, org, numbers)
        val p = parts(listOf(listOf(own, org).filter { it.isNotBlank() }.joinToString("   ·   "), "", ""), numbers, setup, top = false)
        return threeParts("ftr", p[0], p[1], p[2])
    }

    private fun header(text: String, date: Boolean, numbers: Boolean, setup: DocMarkup.PageSetup): String {
        if (!setup.numbersMoved) return header(text, date)
        val p = parts(listOf(text, "", if (date) DocMarkup.today() else ""), numbers, setup, top = true)
        return threeParts("hdr", p[0], p[1], p[2])
    }

    /** Footer line ([own] and the theme's [org] line) and "Seite X von Y" (PAGE / NUMPAGES fields, updated by Word). */
    private fun footer(own: String, org: String, numbers: Boolean): String {
        val rPr = "<w:rPr><w:color w:val=\"888888\"/><w:sz w:val=\"16\"/></w:rPr>"
        fun t(s: String) = "<w:r>$rPr<w:t xml:space=\"preserve\">${esc(s)}</w:t></w:r>"
        val content = buildString {
            val line = listOf(own, org).filter { it.isNotBlank() }.joinToString("   ·   ")
            if (line.isNotBlank()) append(fieldRuns(line + if (numbers) "   ·   " else "", rPr))
            if (numbers) {
                append(t("Seite "))
                append(field("PAGE", "1", rPr))
                append(t(" von "))
                append(field("NUMPAGES", "1", rPr))
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:ftr xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\"><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>$content</w:p></w:ftr>"
    }

    /** Header line on the left, today's date on the right (at a right tab). */
    private fun header(text: String, date: Boolean): String {
        val rPr = "<w:rPr><w:color w:val=\"888888\"/><w:sz w:val=\"16\"/></w:rPr>"
        val left = if (text.isBlank()) "" else fieldRuns(text, rPr)
        val right = if (!date) "" else "<w:r>$rPr<w:tab/></w:r><w:r>$rPr<w:t>${esc(DocMarkup.today())}</w:t></w:r>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:hdr xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\"><w:p><w:pPr><w:tabs><w:tab w:val=\"right\" w:pos=\"$TEXT_WIDTH\"/></w:tabs></w:pPr>" +
            "$left$right</w:p></w:hdr>"
    }

    /** The logo as inline picture, about 1.4 cm high (TU Dresden: 17 mm as in the official templates). */
    private fun logoParagraph(theme: DocTheme): String {
        val (w, h) = theme.logoSize ?: return ""
        val tud = theme.style == DocStyle.TUD
        var cy = if (tud) 612_000L else 504_000L
        var cx = cy * w / h
        val maxWidth = if (tud) 1_900_000L else 1_800_000L
        if (cx > maxWidth) {
            cx = maxWidth
            cy = cx * h / w
        }
        val align = when (theme.style) {
            DocStyle.CLASSIC -> "center"
            DocStyle.TUD -> "left"
            else -> "right"
        }
        return "<w:p><w:pPr><w:spacing w:after=\"120\"/><w:jc w:val=\"$align\"/></w:pPr><w:r>" +
            drawing("rIdLogo", 1, "logo.png", if (tud) "Logo der Technischen Universität Dresden" else "Logo", cx, cy) + "</w:r></w:p>"
    }

    private fun drawing(rel: String, id: Int, name: String, descr: String, cx: Long, cy: Long) =
        "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$id\" name=\"${attr(name)}\" descr=\"${attr(descr)}\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"${attr(name)}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rel\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>" +
            "</wp:inline></w:drawing>"

    /**
     * A ```chart block as real Word chart (AP-49): with its data in an embedded workbook, so „Daten bearbeiten“ works
     * in Word. Null for charts Word has no classic form for (box plots) – they keep
     * their data table.
     */
    private fun chartParagraph(code: String): String? {
        val spec = ChartSpec.parse(code)?.takeIf { it.type != ChartSpec.Type.BOX } ?: return null
        val c = ctx
        val n = c.charts.size + 1
        val embedded = runCatching { OfficeCharts.embedded(spec, "../embeddings/Microsoft_Excel_Worksheet$n.xlsx") }.getOrNull() ?: return null
        val part = ChartPart(n, "rIdChart$n", embedded)
        c.charts += part
        val cx = c.width * 635L
        val cy = cx * 380 / 640
        val id = c.drawingId++
        val chart = "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$id\" name=\"Diagramm $n\" descr=\"${attr(OfficeCharts.altText(spec))}\"/><wp:cNvGraphicFramePr/>" + OfficeCharts.graphic(part.rel) + "</wp:inline></w:drawing></w:r>"
        // The title is part of the chart (no caption below, so a file edited in Word comes back without doubling it).
        return para(chart, "<w:spacing w:before=\"120\" w:after=\"160\"/><w:jc w:val=\"center\"/>")
    }

    /**
     * A diagram block (AP-61) as a picture with its connections as alternative text; null without a renderer (Android),
     * then the block stays text.
     */
    private fun diagramParagraph(lang: String, code: String): String? {
        val d = Diagrams.ofBlock(lang, code) ?: return null
        val bytes = runCatching { Diagrams.raster?.invoke(d) }.getOrNull() ?: return null
        val c = ctx
        val n = c.media.size + 1
        c.media += Media("media/diagramm$n.png", "rIdImg$n", bytes)
        val (pw, ph) = DocMarkup.imageSize(bytes) ?: (800 to 600)
        val maxW = c.width * 635L
        // The picture is drawn at twice the size: at most the text width, else its own size.
        var cx = minOf(pw * 9525L / 2, maxW)
        var cy = cx * ph / pw
        val maxH = c.height * 635L * 85 / 100
        if (cy > maxH) { cy = maxH; cx = cy * pw / ph }
        val id = c.drawingId++
        return para("<w:r>" + drawing("rIdImg$n", id, "Diagramm $n", Diagrams.altText(d), cx, cy) + "</w:r>", "<w:spacing w:before=\"120\" w:after=\"160\"/><w:jc w:val=\"center\"/>")
    }

    /** A picture of the document as inline drawing (width in percent of the text width), caption below it. */
    private fun imageParagraph(img: DocMarkup.Image): String {
        val c = ctx
        val bytes = DocMarkup.imageBytes(img.src)
            ?: return para(run("[Bild: ${img.alt.ifBlank { img.src.substringAfterLast('/') }}]", RunStyle(italic = true, color = "888888")), SPACING)
        val type = DocMarkup.imageType(bytes) ?: "png"
        val n = c.media.size + 1
        val media = Media("media/bild$n.$type", "rIdImg$n", bytes)
        c.media += media
        val (pw, ph) = DocMarkup.imageSize(bytes) ?: (800 to 600)
        val maxW = c.width * 635L
        val maxH = c.height * 635L * 85 / 100
        var cx = if (img.width != null) maxW * img.width / 100 else minOf(pw * 9525L, maxW)
        var cy = cx * ph / pw
        if (cy > maxH) {
            cy = maxH
            cx = cy * pw / ph
        }
        val id = c.drawingId++
        val caption = img.alt.isNotBlank()
        // Ausrichtung of the picture and its caption (AP-125 d).
        val jc = when (img.align) { "left" -> "left"; "right" -> "right"; else -> "center" }
        val pictureRun = "<w:r>" + drawing(media.rel, id, "Bild $n", img.altText.ifBlank { "Bild $n" }, cx, cy) + "</w:r>"
        img.wrap?.let { side ->
            val label = img.nr?.let { captionLabel(DocFields.Kind.FIGURE, it, caption) }.orEmpty()
            return floatingPicture(side, pictureRun, if (caption || label.isNotEmpty()) label + runs(img.alt) else null, cx, cy)
        }
        val picture = para(
            pictureRun,
            (if (caption) "<w:keepNext/>" else "") + "<w:spacing w:before=\"120\" w:after=\"${if (caption) 60 else 160}\"/><w:jc w:val=\"$jc\"/>",
        )
        if (img.nr != null) return picture.replace("<w:spacing w:before=\"120\" w:after=\"160\"/>", "<w:keepNext/><w:spacing w:before=\"120\" w:after=\"60\"/>") +
            bookmarked(para(captionLabel(DocFields.Kind.FIGURE, img.nr, caption) + runs(img.alt), "<w:pStyle w:val=\"Caption\"/><w:jc w:val=\"$jc\"/>"), img.nr)
        return picture + if (caption) para(runs(img.alt), "<w:pStyle w:val=\"Caption\"/><w:jc w:val=\"$jc\"/>") else ""
    }

    /**
     * Textumbruch (AP-125 e), as Word does it for a picture with a caption: picture and caption in a text box without
     * border, anchored at the left or right margin with „Quadrat“; the text after it runs on beside it. The anchor sits in
     * a paragraph of almost no height, so the text starts level with the picture.
     */
    private fun floatingPicture(side: String, pictureRun: String, captionRuns: String?, cx: Long, cy: Long): String {
        val c = ctx
        c.floating = true
        val id = c.drawingId++
        val gap = 114300L
        val content = para(pictureRun, "<w:spacing w:before=\"0\" w:after=\"${if (captionRuns != null) 60 else 0}\"/><w:jc w:val=\"center\"/>") +
            (captionRuns?.let { para(it, "<w:pStyle w:val=\"Caption\"/><w:spacing w:before=\"0\" w:after=\"0\"/><w:jc w:val=\"center\"/>") }.orEmpty())
        val boxH = cy + if (captionRuns != null) 320000L else 0L
        val shape = "<wp:anchor distT=\"0\" distB=\"$gap\" distL=\"${if (side == "right") gap else 0}\" distR=\"${if (side == "left") gap else 0}\" simplePos=\"0\" relativeHeight=\"$id\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"0\">" +
            "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"margin\"><wp:align>$side</wp:align></wp:positionH>" +
            "<wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0</wp:posOffset></wp:positionV>" +
            "<wp:extent cx=\"$cx\" cy=\"$boxH\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapSquare wrapText=\"bothSides\"/>" +
            "<wp:docPr id=\"$id\" name=\"Bild mit Beschriftung $id\"/><wp:cNvGraphicFramePr/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">" +
            "<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$boxH\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>" +
            "<wps:txbx><w:txbxContent>$content</w:txbxContent></wps:txbx>" +
            "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:spAutoFit/></wps:bodyPr>" +
            "</wps:wsp></a:graphicData></a:graphic></wp:anchor>"
        val run = "<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing>$shape</w:drawing></mc:Choice></mc:AlternateContent></w:r>"
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr>$run</w:p>"
    }

    private fun esc(s: String) = s
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(s: String) = esc(s).replace("\"", "&quot;").replace("\n", " ")

    /** Run formatting; rendered in the element order the OOXML schema requires. */
    private data class RunStyle(
        val mono: Boolean = false,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val color: String? = null,
        val size: Int? = null,
        val underline: Boolean = false,
        val shade: Boolean = false,
        val font: String? = null,
        /** Highlight colour (RRGGBB) of the Fließtext-Editor, written as shading so every colour works. */
        val highlight: String? = null,
        val sup: Boolean = false,
        val sub: Boolean = false,
    ) {
        fun xml() = buildString {
            if (mono) append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>")
            else if (font != null) append("<w:rFonts w:ascii=\"${attr(font)}\" w:hAnsi=\"${attr(font)}\" w:cs=\"${attr(font)}\"/>")
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (strike) append("<w:strike/>")
            if (color != null) append("<w:color w:val=\"$color\"/>")
            if (size != null) append("<w:sz w:val=\"$size\"/>")
            if (underline) append("<w:u w:val=\"single\"/>")
            if (highlight != null) append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$highlight\"/>")
            else if (shade) append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F1F3F5\"/>")
            if (sup) append("<w:vertAlign w:val=\"superscript\"/>") else if (sub) append("<w:vertAlign w:val=\"subscript\"/>")
        }
    }

    private fun run(text: String, style: RunStyle = RunStyle()): String {
        val props = style.xml().let { if (it.isEmpty()) "" else "<w:rPr>$it</w:rPr>" }
        // Tabs (AP-44) as Word tabs: they go to the paragraph's Tabstopps.
        return text.split('\n').joinToString("<w:r><w:br/></w:r>") { line ->
            line.split('\t').joinToString("<w:r>$props<w:tab/></w:r>") { piece ->
                if (piece.isEmpty()) "" else "<w:r>$props<w:t xml:space=\"preserve\">${esc(piece)}</w:t></w:r>"
            }
        }
    }

    private fun segRun(seg: Segment, base: RunStyle): String {
        val r = segRunPlain(seg, base)
        // Querverweis (AP-126 c): a link to a place in the document jumps to its bookmark.
        val target = seg.link?.takeIf { it.startsWith("#") && it.length > 1 }?.substring(1) ?: return r
        return "<w:hyperlink w:anchor=\"${bm(target)}\" w:history=\"1\">$r</w:hyperlink>"
    }

    private fun segRunPlain(seg: Segment, base: RunStyle) = run(
        seg.text,
        base.copy(
            mono = base.mono || seg.code,
            bold = base.bold || seg.bold,
            italic = base.italic || seg.italic,
            strike = base.strike || seg.strike,
            color = if (seg.link != null) "1D4ED8" else seg.color ?: base.color,
            underline = base.underline || seg.link != null || seg.underline,
            shade = base.shade || seg.code,
            size = seg.size?.let { it * 2 } ?: base.size,
            font = seg.font ?: base.font,
            highlight = seg.highlight ?: base.highlight,
            sup = seg.sup || base.sup,
            sub = seg.sub || base.sub,
        ),
    )

    /** A footnote mark in the text; Word numbers it and puts the note at the bottom of the page. */
    private fun noteRef(n: Int) =
        if (ctx.endnotes) "<w:r><w:rPr><w:rStyle w:val=\"EndnoteReference\"/></w:rPr><w:endnoteReference w:id=\"$n\"/></w:r>"
        else "<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteReference w:id=\"$n\"/></w:r>"

    /** Word's name of the bookmark of a target (AP-126 c): letters, digits and „_“, at most 40. */
    private fun bm(id: String) = id.replace('-', '_').take(40)

    /** [paragraph] (one `<w:p>`) inside a bookmark named after [id] – the place a cross reference jumps to. */
    private fun bookmarked(paragraph: String, id: String?): String {
        if (id == null || !paragraph.startsWith("<w:p>")) return paragraph
        val n = 100 + ctx.bookmarks++
        val at = paragraph.indexOf("</w:pPr>").let { if (it >= 0) it + 8 else 5 }
        val end = paragraph.lastIndexOf("</w:p>")
        return paragraph.substring(0, at) + "<w:bookmarkStart w:id=\"$n\" w:name=\"${bm(id)}\"/>" + paragraph.substring(at, end) +
            "<w:bookmarkEnd w:id=\"$n\"/>" + paragraph.substring(end)
    }

    /** „Abbildung “ SEQ Abbildung „: “ – the label of a numbered caption as Word writes it (its Verzeichnis lists it). */
    private fun captionLabel(kind: DocFields.Kind, id: String, hasText: Boolean): String {
        val word = if (kind == DocFields.Kind.FIGURE) DocFields.figureWord(ctx.language) else DocFields.tableWord(ctx.language)
        val no = ctx.targets.firstOrNull { it.id == id }?.number ?: "1"
        return run("$word ") + field("SEQ $word \\* ARABIC", no, "") + (if (hasText) run(": ") else "")
    }

    private fun runs(text: String, base: RunStyle = RunStyle()): String =
        // Formulas (AP-16) as Word formulas, the text around them as runs.
        LatexOmml.split(text).joinToString("") { (t, math) -> if (math) LatexOmml.inline(t) else textRuns(t, base) }

    private fun textRuns(text: String, base: RunStyle): String {
        val numbers = ctx.numbers
        return inlineSegments(text).joinToString("") { seg ->
            if (seg.code || numbers.isEmpty()) segRun(seg, base)
            else DocMarkup.splitNotes(seg.text, numbers).joinToString("") { (t, n) -> if (n != null) noteRef(n) else segRun(seg.copy(text = t), base) }
        }
    }

    /**
     * A paragraph of the Fließtext-Editor with its marker (AP-44/46): Formatvorlage (Titel, Untertitel,
     * Bildunterschrift, Code, Literatur), alignment, line spacing and indents.
     */
    private fun markedParagraph(text: String): String {
        val m = io.github.thepytales.llmhub.ui.ALIGN_MARK.find(text.trimStart()) ?: return para(runs(text), SPACING)
        val p = io.github.thepytales.llmhub.docs.text.ParaFormat.fromMarker(io.github.thepytales.llmhub.docs.text.ParaFormat(), m)
        val sheet = ctx.sheet
        val style = when (p.kind) {
            io.github.thepytales.llmhub.docs.text.ParaKind.TITLE -> "Title"
            io.github.thepytales.llmhub.docs.text.ParaKind.SUBTITLE -> "Subtitle"
            io.github.thepytales.llmhub.docs.text.ParaKind.CAPTION -> "Caption"
            io.github.thepytales.llmhub.docs.text.ParaKind.LITERATURE -> "Bibliography"
            else -> null
        }
        val pPr = buildString {
            style?.let { append("<w:pStyle w:val=\"$it\"/>") }
            // Absatz-Optionen (AP-126 d), in the order Word wants: keepNext, pageBreakBefore, pBdr, shd, tabs, spacing, ind.
            append(boxPPr(p))
            if (p.tabs.isNotEmpty()) {
                append("<w:tabs>")
                p.tabs.forEach { t ->
                    val v = when (t.kind) { io.github.thepytales.llmhub.docs.text.TabKind.LEFT -> "left"; io.github.thepytales.llmhub.docs.text.TabKind.CENTER -> "center"; io.github.thepytales.llmhub.docs.text.TabKind.RIGHT -> "right" }
                    append("<w:tab w:val=\"$v\" w:pos=\"${(t.mm * 56.693f).toInt()}\"/>")
                }
                append("</w:tabs>")
            }
            val code = p.kind == io.github.thepytales.llmhub.docs.text.ParaKind.CODE
            when {
                p.spacing != null -> append(spacingXml(p, null, 120, (p.spacing * 240).toInt()))
                code -> append(spacingXml(p, null, 0, 240))
                style == null && sheet == null -> append(spacingXml(p, null, 120, 276))
                else -> append(spacingXml(p, null, null, null))
            }
            if (p.left != 0 || p.first != 0) {
                val left = (p.left * 56.693f).toInt()
                val first = (p.first * 56.693f).toInt()
                append("<w:ind w:left=\"$left\"" + (if (first >= 0) " w:firstLine=\"$first\"" else " w:hanging=\"${-first}\"") + "/>")
            }
            append(jc(text))
        }
        val base = if (p.kind == io.github.thepytales.llmhub.docs.text.ParaKind.CODE) RunStyle(mono = true, size = 20) else null
        // Lesezeichen (AP-126 c): the marker b=… names the paragraph as a target.
        val mark = Regex(";b=([0-9a-z-]+)").find(m.groupValues[2])?.groupValues?.get(1)?.let { "lz-$it" }
        return bookmarked(para(if (base != null) runs(text, base) else runs(text), pPr), mark)
    }

    /** Alignment of a paragraph of the Fließtext-Editor (marker `<!--a:c-->` at its start, AP-44). */
    private fun jc(text: String): String = when (io.github.thepytales.llmhub.ui.ALIGN_MARK.find(text.trimStart())?.groupValues?.get(1)) {
        "c" -> "<w:jc w:val=\"center\"/>"
        "r" -> "<w:jc w:val=\"right\"/>"
        "j" -> "<w:jc w:val=\"both\"/>"
        else -> ""
    }

    /** The paragraph format in the marker of [text] (AP-44/126), or null. */
    private fun formatOf(text: String): io.github.thepytales.llmhub.docs.text.ParaFormat? =
        io.github.thepytales.llmhub.ui.ALIGN_MARK.find(text.trimStart())?.let { io.github.thepytales.llmhub.docs.text.ParaFormat.fromMarker(io.github.thepytales.llmhub.docs.text.ParaFormat(), it) }

    /**
     * Absatz-Optionen (AP-126 d): mit nächstem zusammenhalten, Seitenumbruch oberhalb, Rahmen and Schattierung – right
     * after pStyle. [keepNext]: the block keeps with the next anyway (headings); [border]: its own lines (unless framed).
     */
    private fun boxPPr(p: io.github.thepytales.llmhub.docs.text.ParaFormat?, keepNext: Boolean = false, border: String = "", numPr: String = ""): String = buildString {
        if (keepNext || p?.keep == true) append("<w:keepNext/>")
        if (p?.pageBefore == true) append("<w:pageBreakBefore/>")
        append(numPr)
        if (p?.border == true) append("<w:pBdr>" + listOf("top", "left", "bottom", "right").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"444444\"/>" } + "</w:pBdr>")
        else append(border)
        p?.shade?.let { append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${it.uppercase()}\"/>") }
    }

    /** `<w:spacing>`: the paragraph's own space before/after (pt) over the given ones (twips); [line] in 240ths; "" for none. */
    private fun spacingXml(p: io.github.thepytales.llmhub.docs.text.ParaFormat?, before: Int?, after: Int?, line: Int?): String {
        val b = p?.before?.let { it * 20 } ?: before
        val a = p?.after?.let { it * 20 } ?: after
        if (b == null && a == null && line == null) return ""
        return "<w:spacing" + (b?.let { " w:before=\"$it\"" } ?: "") + (a?.let { " w:after=\"$it\"" } ?: "") +
            (line?.let { " w:line=\"$it\" w:lineRule=\"auto\"" } ?: "") + "/>"
    }

    private fun para(content: String, pPr: String = "") =
        "<w:p>${if (pPr.isEmpty()) "" else "<w:pPr>$pPr</w:pPr>"}$content</w:p>"

    private const val SPACING = "<w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/>"

    private fun accentOf(theme: DocTheme) = if (theme.style == DocStyle.CLASSIC || theme.style == DocStyle.MINIMAL) null else theme.accentHex
    private fun headFontOf(theme: DocTheme) = if (theme.style == DocStyle.CLASSIC || theme.style == DocStyle.ELEGANT) "Georgia" else null

    private fun document(title: String, markdown: String, theme: DocTheme, withLogo: Boolean): String = buildString {
        head(theme, withLogo)
        val full = DocumentWriter.withTitle(title, markdown)
        // A title heading added here is no target of its own (AP-126 c): the ids stay with their headings.
        if (full.trimStart().startsWith("# ") && !markdown.trimStart().startsWith("# ")) ctx.headingIds.addFirst("kap-dokumenttitel")
        val blocks = parseBlocks(full)
        if (ctx.numbered) ctx.headingNos = ArrayDeque(DocFields.headingNumbers(blocks.filterIsInstance<Block.Heading>().filter { it.level <= 4 }.map { it.level }))
        for (b in blocks) block(b, theme)
        tail()
    }

    private fun StringBuilder.head(theme: DocTheme, withLogo: Boolean) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<w:document xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\" ")
        append("xmlns:m=\"${LatexOmml.NS}\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" ")
        // Text boxes (Textumbruch ums Bild, AP-125 e).
        append("xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\">")
        // Seitenfarbe (AP-126 d).
        ctx.layout.color?.let { append("<w:background w:color=\"${it.uppercase()}\"/>") }
        append("<w:body>")
        if (withLogo) append(logoParagraph(theme))
        if (theme.style == DocStyle.TUD && theme.unit.isNotBlank()) {
            val lines = theme.unit.lines().map { it.trim() }.filter { it.isNotEmpty() }
            lines.forEachIndexed { i, line ->
                val last = i == lines.lastIndex
                // As in the official templates: the unit in small black type below the logo.
                val style = RunStyle(bold = last, size = 17)
                append(para(run(line, style), "<w:spacing w:after=\"${if (last) 240 else 0}\"/>"))
            }
        }
    }

    /** Section properties: header/footer, A4 portrait or landscape, 2 cm margins. */
    private fun sectPr(landscape: Boolean): String = buildString {
        val c = ctx
        append("<w:sectPr>")
        if (c.section > 0) {
            // A section of its own: its header and footer on odd and even pages (parts 4, 5 …).
            val n = 3 + c.section
            if (c.header) append("<w:headerReference w:type=\"default\" r:id=\"rIdHeader$n\"/>")
            if (c.header && c.even) append("<w:headerReference w:type=\"even\" r:id=\"rIdHeader$n\"/>")
            if (c.footer) append("<w:footerReference w:type=\"default\" r:id=\"rIdFooter$n\"/>")
            if (c.footer && c.even) append("<w:footerReference w:type=\"even\" r:id=\"rIdFooter$n\"/>")
        } else {
            if (c.header) append("<w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/>")
            if (c.header && c.first) append("<w:headerReference w:type=\"first\" r:id=\"rIdHeader2\"/>")
            if (c.header && c.even) append("<w:headerReference w:type=\"even\" r:id=\"rIdHeader3\"/>")
            if (c.footer) append("<w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>")
            if (c.footer && c.first) append("<w:footerReference w:type=\"first\" r:id=\"rIdFooter2\"/>")
            if (c.footer && c.even) append("<w:footerReference w:type=\"even\" r:id=\"rIdFooter3\"/>")
        }
        // Spalten (AP-126 d): a section of columns starts on the same page.
        if (c.continuous) append("<w:type w:val=\"continuous\"/>")
        c.continuous = false
        val (pw, ph) = c.layout.paperTwips
        append(if (landscape) "<w:pgSz w:w=\"$ph\" w:h=\"$pw\" w:orient=\"landscape\"/>" else "<w:pgSz w:w=\"$pw\" w:h=\"$ph\"/>")
        val m = c.margin
        append("<w:pgMar w:top=\"$m\" w:right=\"$m\" w:bottom=\"$m\" w:left=\"$m\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>")
        if (c.columns > 1) append("<w:cols w:num=\"${c.columns}\" w:space=\"454\"/>")
        if (c.first && c.sections == 0) append("<w:titlePg/>")
        c.sections++
        append("</w:sectPr>")
    }

    private fun StringBuilder.tail() {
        append(sectPr(ctx.landscape))
        append("</w:body></w:document>")
    }

    /** Paragraph properties of a block (style, indent, borders) for the tracked version. */
    private fun pPrOf(b: Block, theme: DocTheme): String = when (b) {
        is Block.Heading -> "<w:pStyle w:val=\"Heading${b.level.coerceIn(1, 4)}\"/><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/>"
        is Block.Item -> "<w:spacing w:after=\"60\"/><w:ind w:left=\"${720 + 360 * b.indent}\" w:hanging=\"360\"/>"
        is Block.Quote -> "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"BBBBBB\"/></w:pBdr>$SPACING<w:ind w:left=\"240\"/>"
        else -> SPACING
    }

    private fun textOf(b: Block): String = when (b) {
        is Block.Heading -> b.text
        is Block.Paragraph -> b.text
        is Block.Item -> b.marker + " " + b.text
        is Block.Quote -> b.text
        is Block.Code -> b.code
        is Block.Table -> b.rows.joinToString("\n") { it.joinToString(" | ") }
        Block.Rule -> ""
    }

    /** Plain text of inline Markdown (the tracked version compares words without formatting marks). */
    private fun plainInline(text: String) = inlineSegments(text).joinToString("") { it.text }

    /** Blocks the tracked version keeps as they are (markers, pictures, tables). */
    private fun special(b: Block) = b is Block.Table || b is Block.Rule ||
        b is Block.Paragraph && (DocMarkup.isComment(b.text) || DocMarkup.isPageBreak(b.text) || DocMarkup.image(b.text) != null)

    private fun trackedDocument(oldMd: String, newMd: String, theme: DocTheme, withLogo: Boolean, author: String): String = buildString {
        head(theme, withLogo)
        val date = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        var revision = 1
        fun attrs() = "w:id=\"${revision++}\" w:author=\"${esc(author)}\" w:date=\"$date\""
        fun insRun(t: String, style: RunStyle) = if (t.isEmpty()) "" else "<w:ins ${attrs()}>${run(t, style)}</w:ins>"
        fun delRun(t: String, style: RunStyle): String {
            if (t.isEmpty()) return ""
            val props = style.xml().let { if (it.isEmpty()) "" else "<w:rPr>$it</w:rPr>" }
            return "<w:del ${attrs()}>" + t.split('\n').joinToString("<w:r><w:br/></w:r>") { line -> "<w:r>$props<w:delText xml:space=\"preserve\">${esc(line)}</w:delText></w:r>" } + "</w:del>"
        }
        fun styleOf(b: Block) = when (b) {
            is Block.Heading -> RunStyle(bold = true, size = when (b.level) { 1 -> 40; 2 -> 32; 3 -> 27; else -> 24 }, color = if (b.level <= 2) accentOf(theme) else null, font = headFontOf(theme))
            is Block.Quote -> RunStyle(italic = true, color = "555555")
            is Block.Code -> RunStyle(mono = true, size = 18)
            else -> RunStyle()
        }
        val oldBlocks = parseBlocks(oldMd)
        val newBlocks = parseBlocks(newMd)
        // Longest common subsequence of the blocks; changed neighbours are compared word by word.
        val dp = Array(oldBlocks.size + 1) { IntArray(newBlocks.size + 1) }
        for (i in oldBlocks.indices.reversed()) for (j in newBlocks.indices.reversed()) {
            dp[i][j] = if (oldBlocks[i] == newBlocks[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < oldBlocks.size || j < newBlocks.size) {
            when {
                i < oldBlocks.size && j < newBlocks.size && oldBlocks[i] == newBlocks[j] -> { block(newBlocks[j], theme); i++; j++ }
                // A changed paragraph: same kind of block on both sides → word by word.
                i < oldBlocks.size && j < newBlocks.size && oldBlocks[i]::class == newBlocks[j]::class && !special(oldBlocks[i]) && !special(newBlocks[j]) &&
                    dp[i][j] == dp[i + 1][j + 1] -> {
                    val style = styleOf(newBlocks[j])
                    val pieces = TextDiff.words(plainInline(textOf(oldBlocks[i])), plainInline(textOf(newBlocks[j])))
                    val content = pieces.joinToString("") { p ->
                        when (p.op) {
                            TextDiff.Op.SAME -> run(p.text, style)
                            TextDiff.Op.ADDED -> insRun(p.text, style)
                            TextDiff.Op.REMOVED -> delRun(p.text, style)
                        }
                    }
                    append(para(content, pPrOf(newBlocks[j], theme)))
                    i++; j++
                }
                j < newBlocks.size && (i >= oldBlocks.size || dp[i][j + 1] >= dp[i + 1][j]) -> {
                    val b = newBlocks[j]
                    if (special(b)) block(b, theme)
                    else append(para(insRun(plainInline(textOf(b)), styleOf(b)), pPrOf(b, theme) + "<w:rPr><w:ins ${attrs()}/></w:rPr>"))
                    j++
                }
                else -> {
                    val b = oldBlocks[i]
                    val text = if (b is Block.Table) "[Tabelle entfernt] " + textOf(b).take(200) else plainInline(textOf(b))
                    if (!special(b) || b is Block.Table) append(para(delRun(text, styleOf(b)), pPrOf(b, theme) + "<w:rPr><w:del ${attrs()}/></w:rPr>"))
                    i++
                }
            }
        }
        tail()
    }

    /** Paragraph-level extras: editor notes, page breaks, landscape sections and pictures. */
    private fun StringBuilder.paragraph(text: String) {
        val c = ctx
        DocMarkup.orientation(text)?.let { landscape ->
            if (landscape != c.landscape) {
                // The section so far ends here (its properties sit in the last paragraph); the next one turns.
                append(para("", sectPr(c.landscape)))
                c.landscape = landscape
            }
            return
        }
        // Spalten (AP-126 d): the text so far ends its section (with its columns); the next one has the new number.
        DocMarkup.columns(text)?.let { n ->
            if (n != c.columns) {
                append(para("", sectPr(c.landscape)))
                c.columns = n
                c.continuous = true
            }
            return
        }
        // Abschnittsumbruch: the section so far ends here; the next one starts on a new page with its own header/footer.
        if (DocMarkup.section(text) != null) {
            if (c.section < c.breaks.size) {
                append(para("", sectPr(c.landscape)))
                c.section++
            }
            return
        }
        when {
            DocMarkup.isPageBreak(text) -> append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            DocMarkup.isToc(text) -> toc()
            DocFields.isFigureList(text) -> captionList(DocFields.Kind.FIGURE)
            DocFields.isTableList(text) -> captionList(DocFields.Kind.TABLE)
            // Beschriftung einer Tabelle (AP-126 c): above the table, kept with it.
            DocFields.tableCaption(text) != null -> DocFields.tableCaption(text)!!.let { cap ->
                append(bookmarked(para(captionLabel(DocFields.Kind.TABLE, cap.id, cap.text.isNotBlank()) + runs(cap.text), "<w:pStyle w:val=\"Caption\"/><w:keepNext/><w:jc w:val=\"left\"/><w:spacing w:before=\"120\" w:after=\"60\"/>"), cap.id))
            }
            LatexOmml.display(text) != null -> append(LatexOmml.paragraph(LatexOmml.display(text)!!))
            DocMarkup.isComment(text) -> {}
            else -> {
                val img = DocMarkup.image(text)
                if (img != null) append(imageParagraph(img)) else append(markedParagraph(text))
            }
        }
    }

    /**
     * Inhaltsverzeichnis (AP-16) as Word field (TOC, levels 1–3, linked): it lists the headings at once; Word fills in
     * the page numbers with „Felder aktualisieren“ (F9).
     */
    /** Abbildungs- oder Tabellenverzeichnis (AP-126 c) as Word field (TOC \\c over the captions), filled in at once. */
    private fun StringBuilder.captionList(kind: DocFields.Kind) {
        val word = if (kind == DocFields.Kind.FIGURE) DocFields.figureWord(ctx.language) else DocFields.tableWord(ctx.language)
        append(para(run(DocFields.listTitle(kind, ctx.language), RunStyle(bold = true, size = 28)), "<w:spacing w:before=\"120\" w:after=\"120\"/>"))
        val begin = "<w:r><w:fldChar w:fldCharType=\"begin\" w:dirty=\"true\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> TOC \\h \\z \\c \"$word\" </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
        val end = "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
        val entries = ctx.targets.filter { it.kind == kind }
        if (entries.isEmpty()) {
            append(para(begin + run("–") + end))
            return
        }
        entries.forEachIndexed { i, e ->
            val text = e.label + if (e.text.isNotBlank()) ": " + e.text else ""
            append(para((if (i == 0) begin else "") + run(text) + (if (i == entries.lastIndex) end else ""), "<w:pStyle w:val=\"TableofFigures\"/><w:spacing w:after=\"40\"/>"))
        }
    }

    private fun StringBuilder.toc() {
        val entries = ctx.headings
        val rPr = ""
        append(para(run("Inhaltsverzeichnis", RunStyle(bold = true, size = 28)), "<w:spacing w:before=\"120\" w:after=\"120\"/>"))
        val begin = "<w:r>$rPr<w:fldChar w:fldCharType=\"begin\" w:dirty=\"true\"/></w:r><w:r>$rPr<w:instrText xml:space=\"preserve\"> TOC \\o \"1-3\" \\h \\z \\u </w:instrText></w:r><w:r>$rPr<w:fldChar w:fldCharType=\"separate\"/></w:r>"
        val end = "<w:r>$rPr<w:fldChar w:fldCharType=\"end\"/></w:r>"
        if (entries.isEmpty()) {
            append(para(begin + run("(Überschriften erscheinen hier)") + end))
            return
        }
        entries.forEachIndexed { i, (level, text) ->
            val pPr = "<w:pStyle w:val=\"TOC$level\"/><w:ind w:left=\"${(level - 1) * 360}\"/><w:spacing w:after=\"40\"/>"
            append(para((if (i == 0) begin else "") + run(text) + (if (i == entries.lastIndex) end else ""), pPr))
        }
    }

    private fun StringBuilder.block(b: Block, theme: DocTheme) {
        // Textumbruch (AP-125 e): only running text goes on beside a picture; a table, another picture … starts below it,
        // as in the editor: the last paragraph before it ends with Word's „Textumbruch“ break that goes below the picture.
        val flows = b is Block.Heading || b is Block.Item || b is Block.Quote ||
            b is Block.Paragraph && !DocMarkup.isPageBreak(b.text) && DocMarkup.image(b.text) == null && !DocMarkup.isComment(b.text)
        if (ctx.floating && !flows) {
            val end = lastIndexOf("</w:p>")
            if (end >= 0) insert(end, "<w:r><w:br w:type=\"textWrapping\" w:clear=\"all\"/></w:r>")
            ctx.floating = false
        }
        if (b !is Block.Item) ctx.lists.end()
        val accent = accentOf(theme)
        val headFont = headFontOf(theme)
        run {
            when (b) {
                is Block.Heading -> {
                    val size = when (b.level) { 1 -> 40; 2 -> 32; 3 -> 27; else -> 24 }
                    val lineColor = accent ?: "BBBBBB"
                    val border = if (b.level == 1) "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"4\" w:color=\"$lineColor\"/></w:pBdr>" else ""
                    val def = ctx.sheet?.of(io.github.thepytales.llmhub.docs.text.ParaKind.heading(b.level.coerceIn(1, 4)))
                    // With Formatvorlagen (AP-46) the heading looks as its Word style says.
                    val style = if (def != null) RunStyle() else RunStyle(bold = true, size = size, color = if (b.level <= 2) accent else null, font = headFont)
                    val align = jc(b.text).ifEmpty { if (b.level == 1 && theme.style == DocStyle.CLASSIC) "<w:jc w:val=\"center\"/>" else "" }
                    val level = b.level.coerceIn(1, 4)
                    val hp = formatOf(b.text)
                    // Nummerierte Überschriften (AP-126 c): the level of the number (1 → 0, 1.2 → 1 …) of Word's list.
                    val no = if (ctx.numbered && b.level <= 4) ctx.headingNos.removeFirstOrNull() else null
                    val numPr = no?.let { "<w:numPr><w:ilvl w:val=\"${it.count { ch -> ch == '.' }}\"/><w:numId w:val=\"1\"/></w:numPr>" }.orEmpty()
                    append(bookmarked(para(runs(b.text, style), "<w:pStyle w:val=\"Heading$level\"/>${boxPPr(hp, keepNext = true, border = border, numPr = numPr)}${spacingXml(hp, 240, 120, null)}$align"), if (b.level <= 4) ctx.headingIds.removeFirstOrNull() else null))
                }
                is Block.Paragraph -> paragraph(b.text)
                is Block.Item -> {
                    val left = 360 + 360 * b.indent
                    val box = Regex("^\\[([ xX])]\\s+").find(b.text)
                    // Listen-Optionen (AP-126 d): own numbers or bullets, counted as in the editor.
                    val ip = formatOf(b.text)
                    val label = if (box == null) ctx.lists.item(b.marker.first().isDigit(), b.indent, ip ?: io.github.thepytales.llmhub.docs.text.ParaFormat(), editor = false) else null
                    val marker = if (box == null) label ?: b.marker else if (box.groupValues[1].isBlank()) "☐" else "☒"
                    val text = if (box == null) b.text else b.text.substring(box.range.last + 1)
                    val hang = if (marker.length > 3) 360 + 90 * (marker.length - 3) else 360
                    append(para(run(marker) + "<w:r><w:tab/></w:r>" + runs(text), "${boxPPr(ip)}${spacingXml(ip, null, 60, null)}<w:ind w:left=\"${left + hang}\" w:hanging=\"$hang\"/>${jc(b.text)}"))
                }
                is Block.Quote -> append(
                    para(
                        runs(b.text, RunStyle(italic = true, color = "555555")),
                        "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"BBBBBB\"/></w:pBdr>$SPACING<w:ind w:left=\"240\"/>",
                    )
                )
                is Block.Code -> {
                    val chart = if (b.lang.equals("chart", ignoreCase = true)) chartParagraph(b.code) else null
                    val diagram = if (chart == null && Diagrams.isDiagramLang(b.lang)) diagramParagraph(b.lang, b.code) else null
                    when {
                        chart != null -> append(chart)
                        diagram != null -> append(diagram)
                        b.lang.equals("chart", ignoreCase = true) -> parseBlocks(ChartBlocks.asTables("```chart\n${b.code}\n```")).forEach { block(it, theme) }
                        else -> append(
                            para(
                                run(b.code, RunStyle(mono = true, size = 18)),
                                "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F1F3F5\"/><w:spacing w:after=\"160\"/>",
                            )
                        )
                    }
                }
                is Block.Table -> table(b.rows, accent, b.widths)
                Block.Rule -> append(para("", "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BBBBBB\"/></w:pBdr>"))
            }
        }
    }

    private fun StringBuilder.table(rows: List<List<String>>, accent: String?, widths: List<Int>? = null) {
        if (rows.isEmpty()) return
        val width = ctx.width
        val cols = rows.maxOf { it.size }
        // Spaltenbreiten from the editor (AP-125 d), otherwise all equal.
        val w = widths?.takeIf { it.size == cols }
        fun colW(c: Int) = if (w != null) width * w[c] / 100 else width / cols
        val border = listOf("top", "left", "bottom", "right", "insideH", "insideV")
            .joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BBBBBB\"/>" }
        append("<w:tbl><w:tblPr><w:tblW w:w=\"$width\" w:type=\"dxa\"/><w:tblBorders>$border</w:tblBorders></w:tblPr>")
        append("<w:tblGrid>")
        repeat(cols) { append("<w:gridCol w:w=\"${colW(it)}\"/>") }
        append("</w:tblGrid>")
        rows.forEachIndexed { r, row ->
            // The head row repeats on every page and is announced as column header by screen readers.
            append(if (r == 0) "<w:tr><w:trPr><w:tblHeader/></w:trPr>" else "<w:tr>")
            // Verbundene Zellen (AP-126 d): one cell over its columns (gridSpan).
            for ((c, text, n) in DocBlocks.spans(row, cols)) {
                val fill = if (accent != null) accent else "F1F3F5"
                val shade = if (r == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/>" else ""
                val w = (c until c + n).sumOf { colW(it) }
                append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>${if (n > 1) "<w:gridSpan w:val=\"$n\"/>" else ""}$shade</w:tcPr>")
                val headColor = if (r == 0 && accent != null) "FFFFFF" else null
                append(para(runs(text, RunStyle(bold = r == 0, color = headColor))))
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
        append(para(""))
    }
}
