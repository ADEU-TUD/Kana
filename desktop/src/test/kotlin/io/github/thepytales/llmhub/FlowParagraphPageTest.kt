package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.DocBlocks
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.DocumentWriter
import io.github.thepytales.llmhub.docs.MarkdownDocx
import io.github.thepytales.llmhub.docs.text.ListStyles
import io.github.thepytales.llmhub.docs.text.PageLayout
import io.github.thepytales.llmhub.docs.text.ParaFormat
import io.github.thepytales.llmhub.docs.text.ParaKind
import io.github.thepytales.llmhub.docs.text.RichText
import io.github.thepytales.llmhub.docs.text.StyleSheet
import io.github.thepytales.llmhub.ui.PageBreakAt
import io.github.thepytales.llmhub.ui.editorListLabels
import io.github.thepytales.llmhub.ui.paraGapsPt
import io.github.thepytales.llmhub.ui.withGaps
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Absatz und Seite (AP-126 d): Abstand vor/nach, zusammenhalten, Seitenumbruch oberhalb, Rahmen und Schattierung,
 * Listen-Optionen und mehrstufige Listen, Spalten, Papierformat, Seitenfarbe, Wasserzeichen, Silbentrennung und
 * verbundene Zellen – in Markdown, im Editor (Zählung, Abstände), im PDF/HTML und in Word.
 */
class FlowParagraphPageTest {
    private val base = Files.createTempDirectory("adeu-ap126d").toFile()

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private fun docx(md: String): Map<String, String> {
        val f = java.io.File(base, "t${System.nanoTime()}.docx")
        MarkdownDocx.write(f, "", md)
        return ZipFile(f).use { z -> z.entries().toList().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }.associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
    }

    private fun wellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test
    fun markerKeepsTheNewParagraphOptions() {
        val p = ParaFormat(before = 12, after = 3, keep = true, pageBefore = true, border = true, shade = "e5f1ff", listFormat = "a", listStart = 5)
        val m = p.marker()
        assertEquals("<!--a:;v=12;n=3;k=1;p=1;r=1;h=e5f1ff;lf=a;ls=5-->", m)
        val back = ParaFormat.fromMarker(ParaFormat(), io.github.thepytales.llmhub.ui.ALIGN_MARK.find(m)!!)
        assertEquals(p, back)
        assertTrue(ParaFormat.fromMarker(ParaFormat(), io.github.thepytales.llmhub.ui.ALIGN_MARK.find("<!--a:;ls=w-->")!!).listContinue)
        // Enter at the end: the new paragraph keeps the look, not the page break nor the new start of the numbers.
        val next = p.forNext()
        assertTrue(next.keep && next.border && next.shade == "e5f1ff" && next.before == 12 && next.listFormat == "a")
        assertFalse(next.pageBefore)
        assertNull(next.listStart)
        // Through the running text and back.
        val md = "Vorher\n\n<!--a:;v=18;n=0;r=1;h=fff8e1-->Gerahmt und hinterlegt\n\n1. <!--a:;lf=i;ls=3-->Drei\n2. <!--a:;lf=i-->Vier\n"
        val r = RichText.fromBlocks(DocBlocks.split(md).filter(RichText::isFlowBlock))
        assertEquals(18, r.paras[1].before)
        assertEquals(0, r.paras[1].after)
        assertTrue(r.paras[1].border && r.paras[1].shade == "fff8e1")
        assertEquals(3, r.paras[2].listStart)
        val again = RichText.toBlocks(r).joinToString("\n\n")
        assertTrue("3. <!--a:;lf=i;ls=3-->Drei" in again && "4. <!--a:;lf=i-->Vier" in again, again)
    }

    @Test
    fun listsCountLikeWord() {
        assertEquals("iv", ListStyles.roman(4))
        assertEquals("xiv", ListStyles.roman(14))
        assertEquals("aa", ListStyles.alpha(27))
        fun para(kind: ParaKind, indent: Int = 0, fmt: String? = null, start: Int? = null, goOn: Boolean = false) =
            ParaFormat(kind = kind, indent = indent, listFormat = fmt, listStart = start, listContinue = goOn)
        val paras = listOf(
            para(ParaKind.NUMBERED, fmt = "m"), para(ParaKind.NUMBERED, 1, "m"), para(ParaKind.NUMBERED, 1, "m"), para(ParaKind.NUMBERED, 2, "m"),
            para(ParaKind.NUMBERED, fmt = "m"),
            ParaFormat(),
            para(ParaKind.NUMBERED, fmt = "au", goOn = true),
            ParaFormat(),
            para(ParaKind.NUMBERED, fmt = "1p", start = 7),
            para(ParaKind.BULLET, fmt = "q"),
        )
        val text = paras.indices.joinToString("\n") { "x$it" }
        val r = RichText(text, List(text.length) { io.github.thepytales.llmhub.docs.text.CharFormat.PLAIN }, paras)
        val labels = editorListLabels(r)
        assertEquals(listOf("1.", "1.1.", "1.2.", "1.2.1.", "2.", "C.", "7)", "■"), listOf(0, 1, 2, 3, 4, 6, 8, 9).map { labels[it] }, labels.toString())
        // Without Listen-Optionen the editor counts as before (1., a. on the second level).
        val plain = RichText("a\nb\nc", List(5) { io.github.thepytales.llmhub.docs.text.CharFormat.PLAIN }, listOf(para(ParaKind.NUMBERED), para(ParaKind.NUMBERED, 1), para(ParaKind.BULLET)))
        assertEquals(mapOf(0 to "1.", 1 to "a.", 2 to "•"), editorListLabels(plain))
    }

    @Test
    fun spaceBeforeAndAfterOnTheSheet() {
        val md = "Eins\n\n<!--a:;n=24-->Zwei\n\n<!--a:;v=12-->Drei\n"
        val r = RichText.fromBlocks(DocBlocks.split(md).filter(RichText::isFlowBlock))
        val gaps = paraGapsPt(r, StyleSheet())
        // Normal text has 6 pt after it in its Formatvorlage: 24 pt after → 18 more; 12 pt before → 12 more.
        assertEquals(mapOf(r.starts[2] to 30f), gaps)
        val breaks = withGaps(listOf(PageBreakAt.at(r.text, r.starts[1], 50f)), gaps.mapValues { it.value * 2 }, r.text)
        assertEquals(2, breaks.size)
        assertEquals(60f, breaks[1].gap)
        // A gap where the page plan breaks: its room adds up.
        val same = withGaps(listOf(PageBreakAt.at(r.text, r.starts[2], 50f)), mapOf(r.starts[2] to 10f), r.text)
        assertEquals(listOf(60f), same.map { it.height })
        assertEquals(10f, same[0].gap)
        // Seitenumbruch oberhalb: a forced break before the paragraph.
        val plan = PageLayout.plan(listOf(PageLayout.Line(10f, 0, 0, 1), PageLayout.Break(0f), PageLayout.Line(10f, 1, 0, 1)), 100f)
        assertEquals(1, plan.page[2])
    }

    @Test
    fun htmlAndPdfLookLikeTheEditor() {
        val md = """
            # Bericht

            <!--a:j;v=12;n=18;k=1-->Ein Absatz mit Abstand.

            <!--a:;p=1;r=1;h=e5f1ff-->Neue Seite, gerahmt.

            1. <!--a:;lf=a-->Erstens
            2. <!--a:;lf=a-->Zweitens

            <!-- spalten: 2 -->

            Text in zwei Spalten.

            <!-- spalten: 1 -->

            | A | B | C |
            | --- | --- | --- |
            | lang | << | x |

            <!-- papier: a5 -->

            <!-- seitenfarbe: fff8e1 -->

            <!-- wasserzeichen: ENTWURF -->

            <!-- silbentrennung: aus -->
        """.trimIndent()
        val layout = DocMarkup.layout(md)
        assertEquals(DocMarkup.Layout("a5", "fff8e1", "ENTWURF", hyphenation = false), layout)
        val html = DocumentWriter.toHtml("", md, print = true)
        assertTrue("margin-top:12pt;margin-bottom:18pt" in html && "break-after:avoid" in html, html)
        assertTrue("text-align:justify\"" in html || "text-align:justify;" in html, "Blocksatz ohne Silbentrennung")
        assertFalse("hyphens:auto" in html.substringAfter("<body"), "Silbentrennung aus")
        assertTrue("break-before:page" in html && "border:0.75pt solid" in html && "background:#e5f1ff" in html, html)
        assertTrue("<span class=\"lnum\">a.</span>" in html && "<span class=\"lnum\">b.</span>" in html, html)
        assertTrue("<div class=\"spalten\" style=\"column-count:2\">" in html, html)
        assertTrue("colspan=\"2\"" in html && "&lt;&lt;" !in html, html)
        assertTrue("@page{size:A5}" in html && "html{background:#fff8e1}" in html && "<div class=\"wz\" aria-hidden=\"true\">ENTWURF</div>" in html, html)
        // Without the new settings the page stays as it was.
        val plain = DocumentWriter.toHtml("", "# T\n\nText\n", print = true)
        assertFalse("class=\"wz\"" in plain || "size:A5" in plain || "html{background" in plain)
    }

    @Test
    fun wordHasTheSameOptions() {
        val md = """
            # Bericht

            <!--a:;v=12;n=18;k=1-->Ein Absatz.

            <!--a:;p=1;r=1;h=e5f1ff-->Gerahmt.

            1. <!--a:;lf=iu-->Erstens
            2. <!--a:;lf=iu-->Zweitens

            <!-- spalten: 2 -->

            Zweispaltig.

            <!-- spalten: 1 -->

            | A | B | C |
            | --- | --- | --- |
            | lang | << | x |

            <!-- papier: a5 -->

            <!-- seitenfarbe: fff8e1 -->

            <!-- wasserzeichen: VERTRAULICH -->
        """.trimIndent()
        val parts = docx(md)
        val doc = parts.getValue("word/document.xml")
        parts.values.forEach { wellFormed(it) }
        assertTrue("<w:keepNext/><w:spacing w:before=\"240\" w:after=\"360\" w:line=\"276\" w:lineRule=\"auto\"/>" in doc, doc)
        assertTrue("<w:pageBreakBefore/><w:pBdr>" in doc && "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E5F1FF\"/>" in doc, doc)
        assertTrue(">I.</w:t>" in doc && ">II.</w:t>" in doc, doc)
        assertTrue("<w:cols w:num=\"2\" w:space=\"454\"/>" in doc && "<w:type w:val=\"continuous\"/>" in doc, doc)
        assertTrue("<w:gridSpan w:val=\"2\"/>" in doc && "&lt;&lt;" !in doc, doc)
        assertTrue("<w:pgSz w:w=\"8391\" w:h=\"11906\"/>" in doc && "<w:background w:color=\"FFF8E1\"/>" in doc, doc)
        assertTrue("<w:displayBackgroundShape/>" in parts.getValue("word/settings.xml"))
        val header = parts.getValue("word/header1.xml")
        assertTrue("string=\"VERTRAULICH\"" in header && "rotation:315" in header, header)
        // The order Word wants in the section properties: type before pgSz, cols after pgMar.
        Regex("<w:sectPr>.*?</w:sectPr>").findAll(doc).forEach { m ->
            val s = m.value
            if ("<w:type" in s) assertTrue(s.indexOf("<w:type") < s.indexOf("<w:pgSz"), s)
            if ("<w:cols" in s) assertTrue(s.indexOf("<w:pgMar") < s.indexOf("<w:cols"), s)
        }
    }
}
