package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.DocCounts
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.DocPrint
import io.github.thepytales.llmhub.docs.DocumentWriter
import io.github.thepytales.llmhub.docs.HtmlPdf
import io.github.thepytales.llmhub.docs.MarkdownDocx
import io.github.thepytales.llmhub.docs.text.AutoCorrect
import io.github.thepytales.llmhub.ui.pageMargins
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Grundausstattung der Dokumente (AP-126 b) in den Formaten: Seitenzahlen an eigener Stelle (Seitenansicht, PDF, Word),
 * Sprache des Dokuments, Details, Drucken – and the page number as always where nothing was chosen.
 */
class FlowOfficeBasicsTest {
    private val base = Files.createTempDirectory("adeu-ap126b").toFile()

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private fun docx(md: String): Map<String, String> {
        val f = File(base, "t.docx")
        MarkdownDocx.write(f, "T", md)
        return ZipFile(f).use { z -> z.entries().toList().filter { it.name.endsWith(".xml") }.associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
    }

    @Test
    fun pageNumbersKeepTheirPlaceAndText() {
        val setup = DocMarkup.PageSetup(numbersAt = "oben mitte", numbersFormat = "zahl")
        val md = DocMarkup.withPageSetup("# Antrag\n\nText.", setup)
        assertTrue("<!-- seitenzahlen: oben mitte, zahl -->" in md, md)
        assertEquals(setup, DocMarkup.pageSetup(md))
        // As always: nothing written, nothing changed.
        assertFalse("seitenzahlen" in DocMarkup.withPageSetup("Text.", DocMarkup.PageSetup()))
        assertFalse(DocMarkup.pageSetup("<!-- seitenzahlen: aus -->").pageNumbers)
        // Seitenansicht: in the middle of the header.
        val pm = pageMargins(DocMarkup.pageSetup(md), 3, 9)
        assertEquals("3", pm.headerCenter)
        assertEquals("", pm.footerRight)
        val pm2 = pageMargins(DocMarkup.PageSetup(footer = "Entwurf", numbersAt = "unten links", numbersFormat = "bruch"), 2, 5)
        assertEquals("Entwurf · 2 / 5", pm2.footerLeft)
    }

    @Test
    fun pdfAndWordPutThePageNumberWhereChosen() {
        val plain = DocumentWriter.toHtml("T", "Text.\n", print = true)
        assertTrue("@bottom-right{content:\"Seite \" counter(page) \" von \" counter(pages)" in plain, "wie immer unten rechts")
        assertFalse("@top-center" in plain || "@bottom-center" in plain)
        val md = DocMarkup.withPageSetup("Text.", DocMarkup.PageSetup(header = "Kapitel", numbersAt = "oben rechts", numbersFormat = "bruch"))
        val html = DocumentWriter.toHtml("T", md, print = true)
        assertTrue(Regex("@top-right\\{content:counter\\(page\\) \" / \" counter\\(pages\\)").containsMatchIn(html), html.substringAfter("@page").take(600))
        assertTrue("@bottom-right{content:none" in html)
        // Word: the number in the header at the right tab, as PAGE / NUMPAGES fields; the footer without it.
        val parts = docx(md)
        val header = parts.getValue("word/header1.xml")
        assertTrue("w:val=\"right\"" in header && "PAGE" in header && "NUMPAGES" in header && "Kapitel" in header, header)
        assertFalse("PAGE" in parts.getValue("word/footer1.xml"))
        // As always in Word: one centred footer line with „Seite X von Y“.
        val always = docx("Text.").getValue("word/footer1.xml")
        assertTrue("<w:jc w:val=\"center\"/>" in always && "Seite " in always, always)
    }

    @Test
    fun languageOfTheDocument() {
        assertEquals("de-DE", DocMarkup.language("Text."))
        val md = DocMarkup.withLanguage("# Paper\n\nText.", "en-GB")
        assertEquals("en-GB", DocMarkup.language(md))
        assertEquals("de-DE", DocMarkup.language(DocMarkup.withLanguage(md, "de-DE")))
        assertFalse("sprache" in DocMarkup.withLanguage(md, "de-DE"))
        assertTrue("<html lang=\"en-GB\">" in DocumentWriter.toHtml("Paper", md, print = true))
        assertTrue("<html lang=\"de\">" in DocumentWriter.toHtml("T", "Text.", print = true), "Deutsch wie bisher")
        val parts = docx(md)
        assertTrue("w:lang w:val=\"en-GB\"" in parts.getValue("word/styles.xml"), Regex("<w:lang[^>]*>").find(parts.getValue("word/styles.xml"))?.value)
        assertTrue("<dc:language>en-GB</dc:language>" in parts.getValue("docProps/core.xml"))
        assertFalse("sprache" in parts.getValue("word/document.xml"), "die Marke bleibt unsichtbar")
        // Quotation marks while typing follow the language.
        fun quote(before: String, lang: String): String {
            val text = "$before\""
            val fix = AutoCorrect.after(text, text.length, '"', lang = lang)!!
            return text.replaceRange(fix.range, fix.text)
        }
        assertEquals("He said “", quote("He said ", "en-GB"))
        assertEquals("“Yes”", quote("“Yes", "en-US"))
        assertEquals("Er sagte „", quote("Er sagte ", "de-DE"))
        assertEquals("Il dit « ", quote("Il dit ", "fr-FR"))
    }

    @Test
    fun detailsCountTheTextNotTheMarkup() {
        val md = "# Titel\n\nEin **kurzer** Satz mit [Link](https://tu-dresden.de).[^1]\n\n- Punkt eins\n\n![Bild](a.png)\n\n| A | B |\n| --- | --- |\n| x | y |\n\n<!-- sprache: en-GB -->\n\n[^1]: Die Fußnote."
        val s = DocCounts.of(md)
        assertEquals(1, s.images)
        assertEquals(1, s.tables)
        assertEquals(1, s.notes)
        // Titel · Ein kurzer Satz mit Link · Punkt eins · A B x y · Die Fußnote
        assertEquals(1 + 5 + 2 + 4 + 2, s.words, s.toString())
        assertTrue(s.chars > s.charsNoSpaces)
    }

    @Test
    fun printingHandsThePagesToThePrinter() {
        val html = DocumentWriter.toHtml("Druck", "# Druck\n\n" + (1..80).joinToString("\n\n") { "Absatz $it mit etwas Text." }, print = true)
        val pdf = File(base, "druck.pdf")
        HtmlPdf.renderBuiltIn(HtmlPdf.withPrintColors(html), pdf)
        var pages = -1
        var name = ""
        DocPrint.dialog = { job, pageable -> pages = pageable.numberOfPages; name = job.jobName; false }
        try {
            val printed = runBlocking { DocPrint.print(android.content.Context(File(base, "f").apply { mkdirs() }, File(base, "c").apply { mkdirs() }), pdf, "Druck") }
            assertFalse(printed, "abgebrochen")
        } finally {
            DocPrint.dialog = null
        }
        assertEquals(HtmlPdf.pageCount(pdf), pages)
        assertTrue(pages >= 2)
        assertEquals("Druck", name)
    }
}
