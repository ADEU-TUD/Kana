package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.DocFields
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.DocumentWriter
import io.github.thepytales.llmhub.docs.MarkdownDocx
import io.github.thepytales.llmhub.ui.moveBlock
import io.github.thepytales.llmhub.ui.removeBlock
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wissenschaft im Dokument (AP-126 c): Beschriftungen mit Nummer, Querverweise, Verzeichnisse, nummerierte Überschriften,
 * Lesezeichen und Endnoten – in Markdown, PDF/HTML und Word.
 */
class FlowScienceTest {
    private val base = Files.createTempDirectory("adeu-ap126c").toFile()

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private fun picture(): String = File(base, "a.png").also { f ->
        val bi = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(bi, "png", f)
    }.absolutePath

    private fun doc(): String = """
        # Hausarbeit

        [Inhaltsverzeichnis]

        [Abbildungsverzeichnis]

        [Tabellenverzeichnis]

        ## Einleitung

        Wie [Abbildung 9](#abb-hoersaal) zeigt, und siehe [Tabelle 7](#tab-kosten) sowie [„alt“](#kap-methode) und [hier](#abb-hoersaal).[^1]

        ![Der Hörsaal](${picture()}){width=50% nr=abb-hoersaal}

        ### Hintergrund

        <!--a:;b=merke-->Dieser Absatz ist ein Lesezeichen.

        ## Methode

        ![Ohne Nummer](${picture()})

        <!-- tabelle: Kosten nach Phasen; nr=tab-kosten -->

        | Phase | Kosten |
        | --- | --- |
        | A | 1 |

        <!-- ueberschriften: nummeriert -->

        [^1]: Eine Anmerkung.
    """.trimIndent() + "\n"

    @Test
    fun targetsNumbersAndRefresh() {
        assertEquals(listOf(null, "1", "1.1", "2", "2.1", "2.1.1", "3"), DocFields.headingNumbers(listOf(1, 2, 3, 2, 3, 4, 2)))
        assertEquals(listOf("1", "1.1", "2"), DocFields.headingNumbers(listOf(2, 3, 2)), "ohne Titel")
        val md = doc()
        val e = DocFields.entries(md)
        assertEquals("Abbildung 1", e.first { it.id == "abb-hoersaal" }.label)
        assertEquals("Tabelle 1", e.first { it.id == "tab-kosten" }.label)
        assertEquals("Abschnitt 2", e.first { it.id == "kap-methode" }.label)
        assertEquals("Abschnitt 1.1", e.first { it.id == "kap-hintergrund" }.label)
        assertTrue(e.any { it.id == "lz-merke" && it.kind == DocFields.Kind.BOOKMARK }, e.toString())
        val fresh = DocFields.refresh(md)
        assertTrue("[Abbildung 1](#abb-hoersaal)" in fresh && "[Tabelle 1](#tab-kosten)" in fresh && "[Abschnitt 2](#kap-methode)" in fresh, fresh)
        assertTrue("[hier](#abb-hoersaal)" in fresh, fresh)
        val numbered = DocFields.numberHeadings(md)
        assertTrue("## 1 Einleitung" in numbered && "### 1.1 Hintergrund" in numbered && "# Hausarbeit\n" in numbered, numbered)
        // English: Figure, Table.
        assertEquals("Figure 1", DocFields.entries(DocMarkup.withLanguage(md, "en-GB")).first { it.id == "abb-hoersaal" }.label)
        // Picture attribute round trip.
        val img = DocMarkup.image("![X](a.png){width=40% nr=abb-x}")!!
        assertEquals("abb-x", img.nr)
        assertEquals("![X](a.png){width=40% nr=abb-x}", img.markdown())
        assertEquals("tab-kosten", DocFields.tableCaption("<!-- tabelle: Kosten; nr=tab-kosten -->")!!.id)
    }

    @Test
    fun pdfHasNumbersAnchorsAndLists() {
        val html = DocumentWriter.toHtml("Hausarbeit", doc(), print = true)
        assertTrue("<figure class=\"img\" id=\"abb-hoersaal\"" in html, "Anker der Abbildung")
        assertTrue("<figcaption>Abbildung 1: Der Hörsaal</figcaption>" in html, "Nummer der Abbildung")
        assertTrue("<figcaption>Ohne Nummer</figcaption>" in html)
        assertTrue("<p class=\"tabcap\" id=\"tab-kosten\">Tabelle 1: Kosten nach Phasen</p>" in html, "Tabellenbeschriftung")
        assertTrue(Regex("<h2 id=\"kap-einleitung\"[^>]*>1 Einleitung</h2>").containsMatchIn(html), "nummerierte Überschrift mit Anker")
        assertTrue("<p id=\"lz-merke\"" in html, "Lesezeichen")
        assertTrue("<a href=\"#abb-hoersaal\">Abbildung 1</a>" in html, "Querverweis aktualisiert")
        // Verzeichnisse: no „[Inhaltsverzeichnis]“ as text any more (it was printed so before AP-126 c).
        assertFalse("[Inhaltsverzeichnis]" in html)
        assertTrue("<p class=\"vz-titel\">Abbildungsverzeichnis</p>" in html && "<a href=\"#abb-hoersaal\">Abbildung 1: Der Hörsaal</a>" in html)
        assertTrue("<a href=\"#tab-kosten\">Tabelle 1: Kosten nach Phasen</a>" in html)
        assertTrue("<a href=\"#kap-methode\">2 Methode</a>" in html, "Inhaltsverzeichnis mit Nummern")
    }

    private fun parts(md: String): Map<String, String> {
        val f = File(base, "t.docx")
        MarkdownDocx.write(f, "Hausarbeit", md)
        return ZipFile(f).use { z -> z.entries().toList().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }.associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
    }

    @Test
    fun wordHasFieldsBookmarksAndEndnotes() {
        val p = parts(doc())
        // Every part is well-formed XML.
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        p.forEach { (name, xml) -> runCatching { dbf.newDocumentBuilder().parse(xml.byteInputStream()) }.onFailure { throw AssertionError("$name: ${it.message}") } }
        val d = p.getValue("word/document.xml")
        assertTrue("SEQ Abbildung" in d && "SEQ Tabelle" in d, "Beschriftungen als Felder")
        assertTrue("w:name=\"abb_hoersaal\"" in d && "w:name=\"tab_kosten\"" in d && "w:name=\"kap_methode\"" in d && "w:name=\"lz_merke\"" in d, "Textmarken")
        assertTrue("<w:hyperlink w:anchor=\"abb_hoersaal\"" in d, "Querverweis springt")
        assertTrue("TOC \\h \\z \\c \"Abbildung\"" in d && "TOC \\h \\z \\c \"Tabelle\"" in d, "Verzeichnisse")
        assertTrue("1 Einleitung" in d)
        assertTrue("word/footnotes.xml" in p && "word/endnotes.xml" !in p)
        // Endnoten: the notes at the end of the document.
        val e = parts(DocFields.withEndnotes(doc(), true))
        assertTrue("word/endnotes.xml" in e && "word/footnotes.xml" !in e)
        assertTrue("<w:endnoteReference w:id=\"1\"/>" in e.getValue("word/document.xml"))
        assertTrue("endnotes.xml" in e.getValue("word/_rels/document.xml.rels") && "docEnd" in e.getValue("word/settings.xml"))
        assertTrue("endnotes+xml" in File(base, "t.docx").let { f -> ZipFile(f).use { z -> z.getInputStream(z.getEntry("[Content_Types].xml")).readBytes().decodeToString() } })
    }

    /** In the editor a table keeps its caption right above it – loaded, moved, removed; a block inserted into a paragraph. */
    @Test
    fun editorKeepsTheCaptionWithItsTable() {
        val st = io.github.thepytales.llmhub.ui.FlowState("# T\n\nErster.\n\n<!-- tabelle: Kosten; nr=tab-kosten -->\n\n| A |\n| --- |\n| 1 |\n\nZweiter.\n\nDritter.\n")
        fun order() = st.segs.map { s -> when { s is io.github.thepytales.llmhub.ui.FlowSeg.Raw && DocFields.tableCaption(s.markdown) != null -> "cap"; s is io.github.thepytales.llmhub.ui.FlowSeg.Raw -> "tab"; else -> "txt" } }
        assertEquals(listOf("txt", "cap", "tab", "txt"), order(), "keine Lücke zwischen Beschriftung und Tabelle")
        val table = st.segs.filterIsInstance<io.github.thepytales.llmhub.ui.FlowSeg.Raw>().first { it.isTable }
        st.moveBlock(table, up = false)
        val o = order()
        assertEquals(o.indexOf("cap") + 1, o.indexOf("tab"), "Beschriftung zieht mit: $o")
        assertTrue(st.markdown().indexOf("Zweiter.") < st.markdown().indexOf("<!-- tabelle"), st.markdown())
        st.removeBlock(st.segs.filterIsInstance<io.github.thepytales.llmhub.ui.FlowSeg.Raw>().first { it.isTable })
        assertFalse("tabelle" in st.markdown(), st.markdown())
        // A block inserted into a paragraph after a heading: the rest stays normal text (it became a heading before).
        val st2 = io.github.thepytales.llmhub.ui.FlowState("# Titel\n\nVorher nachher.\n")
        val t = st2.segs.filterIsInstance<io.github.thepytales.llmhub.ui.FlowSeg.Text>().first()
        t.selection = androidx.compose.ui.text.TextRange(t.rich.text.indexOf(" nachher"))
        st2.active = t
        st2.insertBlock("---")
        assertTrue("\n\n---\n\nnachher." in st2.markdown() || "\n\n---\n\n nachher." in st2.markdown(), st2.markdown())
        assertFalse("# nachher" in st2.markdown() || "#  nachher" in st2.markdown(), st2.markdown())
    }
}
