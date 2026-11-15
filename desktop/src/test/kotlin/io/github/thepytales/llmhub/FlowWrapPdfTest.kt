package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.Browser
import io.github.thepytales.llmhub.docs.DocumentWriter
import io.github.thepytales.llmhub.docs.HtmlPdf
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seitenplan für umflossene Bilder im PDF (AP-125 f, D-02): a picture the text runs beside stays on the page of the
 * text that starts beside it – before, the browser pushed a picture that no longer fit alone onto the next page and its
 * text stayed behind. Checked with the browser (where one is installed) and the built-in renderer.
 */
class FlowWrapPdfTest {
    private val base = Files.createTempDirectory("adeu-ap125f-pdf").toFile()

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private fun markdown(): String {
        val tall = File(base, "hoch.png")
        val bi = java.awt.image.BufferedImage(200, 500, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics(); g.color = java.awt.Color(0, 48, 93); g.fillRect(0, 0, 200, 500); g.dispose()
        javax.imageio.ImageIO.write(bi, "png", tall)
        val sentence = "Ein Satz, der die Seite füllt und umbricht. "
        val md = (1..9).joinToString("\n\n") { k ->
            val before = (1..(k % 4 + 1)).joinToString("\n\n") { "Absatz $k.$it: " + sentence.repeat(3 + it) }
            // Every other picture has only one short line beside it (the picture is higher than its text).
            val beside = if (k % 2 == 0) "Kurz daneben $k.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |" else "Daneben $k: " + sentence.repeat(8)
            "$before\n\n![Bild $k](${tall.absolutePath}){width=30% wrap=${if (k % 3 == 0) "left" else "right"}}\n\n$beside"
        }
        return "# Bilder im Text\n\n$md\n"
    }

    @Test
    fun pictureAndTheParagraphBesideItStayTogether() {
        val html = DocumentWriter.toHtml("Bilder", markdown(), print = true)
        // The picture and the first paragraph after it in one piece that a page end does not split.
        assertEquals(9, Regex("<div class=\"umfluss\">\\n<figure").findAll(html).count(), "neun Bilder zusammen mit ihrem Text")
        assertTrue(Regex("</figure>\\n<p>Kurz daneben 2\\.</p>\\n</div>\\n<table").containsMatchIn(html), "die Tabelle steht nach dem Stück")
        assertTrue(".umfluss{break-inside:avoid" in html)
        val printed = HtmlPdf.withPrintColors(html)
        if (Browser.find() != null) {
            val viaBrowser = File(File("build/ap125").apply { mkdirs() }, "umfluss-browser.pdf")
            runBlocking { HtmlPdf.write(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }), viaBrowser, html) }
            assertTogether(viaBrowser, "Browser")
        }
        val builtIn = File(base, "eingebaut.pdf").also { HtmlPdf.renderBuiltIn(printed, it) }
        assertTogether(builtIn, "eingebaut")
    }

    /** The caption under each picture and the text beside it are on the same page; at least one picture went over. */
    private fun assertTogether(pdf: File, engine: String) {
        val pages = Loader.loadPDF(pdf).use { d ->
            (1..d.numberOfPages).map { p -> PDFTextStripper().apply { startPage = p; endPage = p }.getText(d).filterNot { it.isWhitespace() || it.isISOControl() || it == '\u00A0' } }
        }
        assertTrue(pages.size >= 4, "$engine: Seiten ${pages.size}")
        for (k in 1..9) {
            val caption = pages.indexOfFirst { Regex("Bild$k(?!\\d)").containsMatchIn(it) }
            val text = pages.indexOfFirst { (if (k % 2 == 0) "Kurzdaneben$k" else "Daneben$k") in it }
            assertTrue(caption >= 0 && text >= 0, "$engine: Bild $k auf Seite $caption, Text auf $text: ${pages.getOrNull(0)?.take(400)}")
            assertEquals(caption, text, "$engine: Bild $k und sein Text auf derselben Seite")
        }
    }
}
