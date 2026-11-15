package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.DocCompare
import io.github.thepytales.llmhub.docs.DocFields
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.MarkdownDocx
import io.github.thepytales.llmhub.docs.OfficeImport
import io.github.thepytales.llmhub.docs.Thesaurus
import io.github.thepytales.llmhub.ui.Snippet
import io.github.thepytales.llmhub.ui.Snippets
import io.github.thepytales.llmhub.ui.atFilter
import io.github.thepytales.llmhub.ui.AtEntry
import io.github.thepytales.llmhub.ui.chartFromTable
import io.github.thepytales.llmhub.ui.dimmed
import io.github.thepytales.llmhub.ui.shapeBlock
import io.github.thepytales.llmhub.ui.wordAround
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Überprüfen (AP-126 e: Thesaurus, Fassungen vergleichen), Docs-Komfort (AP-126 f: Bausteine, @-Menü, Formen, Diagramm
 * aus Daten, Fokusmodus) and the rest of AP-126 c: numbered headings as Word's own list, SEQ captions back from Word.
 */
class FlowReviewComfortTest {
    private val base = Files.createTempDirectory("adeu-ap126ef").toFile()

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private val sample = """
        # OpenThesaurus - German Thesaurus in text format
        Atomspaltung;Fission;Kernfission;Kernspaltung (fachspr., technisch)
        im Nu;in kurzer Frist;innert Kürze (schweiz., österr.);kurzfristig;schnell;zügig
        flink;geschwind;rasch;schnell;zügig
        Haus;Gebäude;Bau
        (etwas) Verbotenes tun (Hauptform);(sich) hinwegsetzen (über);missachten
    """.trimIndent()

    @Test
    fun thesaurusLooksUpOnTheDevice() {
        assertEquals("hinwegsetzen", Thesaurus.clean("(sich) hinwegsetzen (über)"))
        val idx = Thesaurus.parse(sample)
        val schnell = Thesaurus.parse(sample).lookup("schnell")
        assertEquals(2, schnell.size, schnell.toString())
        assertTrue(schnell.flatten().any { it.word == "zügig" } && schnell.flatten().none { it.word == "schnell" })
        assertTrue(idx.lookup("Häuser").flatten().any { it.word == "Gebäude" } || idx.lookup("Hauses").flatten().any { it.word == "Gebäude" })
        assertTrue(idx.lookup("Kernspaltung").flatten().any { it.shown == "Fission" })
        // Installed from the ZIP of OpenThesaurus (as the download gets it).
        val zip = java.io.ByteArrayOutputStream().also { bo ->
            java.util.zip.ZipOutputStream(bo).use { z -> z.putNextEntry(java.util.zip.ZipEntry("openthesaurus.txt")); z.write(sample.toByteArray()); z.closeEntry() }
        }.toByteArray()
        Thesaurus.install(base, zip)
        assertTrue(Thesaurus.installed(base))
        assertNotNull(Thesaurus.index(base)?.lookup("rasch")?.firstOrNull())
        assertEquals("schnelles", wordAround("Ein schnelles Auto", androidx.compose.ui.text.TextRange(7))!!.let { "Ein schnelles Auto".substring(it.first, it.last + 1) })
    }

    @Test
    fun twoVersionsCompared() {
        val old = "# Bericht\n\nDer Hörsaal ist groß und hell.\n\nDieser Absatz fällt weg.\n\nGleich bleibt dies.\n"
        val new = "# Bericht\n\nDer Hörsaal ist sehr groß und hell.\n\nGleich bleibt dies.\n\nGanz neu ist dieser Satz.\n\n<!-- seitenzahlen: aus -->\n"
        val d = DocCompare.diff(old, new)
        val kinds = d.map { it.kind }
        assertEquals(listOf(DocCompare.Kind.SAME, DocCompare.Kind.CHANGED, DocCompare.Kind.REMOVED, DocCompare.Kind.SAME, DocCompare.Kind.ADDED), kinds, d.toString())
        assertEquals("Der Hörsaal ist groß und hell.", d[1].old)
        // As Word file with tracked changes.
        val f = File(base, "vergleich.docx")
        MarkdownDocx.writeTracked(f, "Bericht", old, new, author = "Vergleich")
        val xml = ZipFile(f).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().decodeToString() }
        assertTrue("<w:ins " in xml && "<w:del " in xml, xml.take(2000))
    }

    @Test
    fun snippetsAndTheAtMenu() {
        val own = listOf(Snippet("Gruß", "Viele Grüße\n\nIhr Team"), Snippet("Tabelle", "| A | B |\n| --- | --- |\n| 1 | 2 |"))
        val stored = Snippets.store(own + Snippets.builtIn())
        assertEquals(own, Snippets.own(stored))
        assertTrue(Snippets.builtIn().any { it.name == "Besprechungsnotiz" && "- [ ]" in it.markdown })
        val entries = listOf(AtEntry("Heute (15.11.2026)", "Datum") {}, AtEntry("Besprechungsnotiz", "Baustein") {}, AtEntry("Tabelle 3 × 3", "Einfügen") {})
        assertEquals(listOf("Besprechungsnotiz"), atFilter(entries, "bespr").map { it.label })
        assertEquals(listOf("Heute (15.11.2026)"), atFilter(entries, "dat").map { it.label })
        assertEquals(3, atFilter(entries, "").size)
    }

    @Test
    fun shapesChartsAndFocus() {
        val box = shapeBlock("diamond", "Entscheidung")
        assertTrue(box.startsWith("```"), box)
        val d = io.github.thepytales.llmhub.docs.Diagrams.parseBlock(box)?.let { (l, c) -> io.github.thepytales.llmhub.docs.Diagrams.ofBlock(l, c) }
        assertEquals("diamond", d?.nodes?.single()?.shape, box)
        val arrow = io.github.thepytales.llmhub.docs.Diagrams.parseBlock(shapeBlock("arrow", "Antrag", "Prüfung"))?.let { (l, c) -> io.github.thepytales.llmhub.docs.Diagrams.ofBlock(l, c) }
        assertEquals(1, arrow?.edges?.size)
        val chart = chartFromTable(listOf(listOf("Jahr", "Studierende", "Absolvent:innen"), listOf("2024", "1.200", "300"), listOf("2025", "1.350", "310,5")), "bar", "Zahlen")
        val spec = io.github.thepytales.llmhub.docs.ChartSpec.parse(chart!!.removePrefix("```chart\n").removeSuffix("\n```"))!!
        assertEquals(listOf("2024", "2025"), spec.labels)
        assertEquals(listOf(1200.0, 1350.0), spec.series[0].values)
        assertEquals(310.5, spec.series[1].values[1])
        assertEquals(null, chartFromTable(listOf(listOf("A", "B"), listOf("x", "y")), "bar", ""))
        val dim = dimmed(androidx.compose.ui.text.AnnotatedString("eins\nzwei\ndrei"), 5..9, androidx.compose.ui.graphics.Color.Gray)
        assertEquals(listOf(0 to 5, 10 to 14), dim.spanStyles.map { it.start to it.end })
    }

    @Test
    fun numberedHeadingsAreWordsOwnList() {
        val md = "# Arbeit\n\n## Einleitung\n\n### Hintergrund\n\n## Methode\n\nSiehe [Abschnitt 9](#kap-methode).\n\n<!-- ueberschriften: nummeriert -->\n"
        val f = File(base, "n.docx")
        MarkdownDocx.write(f, "", md)
        val parts = ZipFile(f).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
        val doc = parts.getValue("word/document.xml")
        assertTrue(">Einleitung<" in doc && ">1${DocFields.NUMBER_GAP}Einleitung<" !in doc, "die Nummer ist kein Text")
        assertTrue("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>" in doc && "<w:numPr><w:ilvl w:val=\"1\"/><w:numId w:val=\"1\"/></w:numPr>" in doc, doc)
        // The title has no number.
        val title = Regex("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/>.*?</w:p>").find(doc)!!.value
        assertFalse("numPr" in title, title)
        assertTrue("%1.%2" in parts.getValue("word/numbering.xml"))
        assertTrue("numbering.xml" in parts.getValue("word/_rels/document.xml.rels") && "numbering+xml" in parts.getValue("[Content_Types].xml"))
        assertTrue(">Abschnitt 2<" in doc, "Querverweis mit der Nummer")
        // Without numbering: no list part.
        val g = File(base, "o.docx")
        MarkdownDocx.write(g, "", "# A\n\n## B\n")
        assertFalse(ZipFile(g).use { it.getEntry("word/numbering.xml") != null })
    }

    @Test
    fun seqCaptionsComeBackFromWord() {
        val png = File(base, "a.png").also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(30, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }
        val md = """
            # Arbeit

            Wie [Abbildung 1](#abb-hoersaal) und [Tabelle 1](#tab-kosten) zeigen.

            ![Der Hörsaal](${png.absolutePath}){nr=abb-hoersaal}

            <!-- tabelle: Kosten nach Phasen; nr=tab-kosten -->

            | Phase | Kosten |
            | --- | --- |
            | A | 1 |
        """.trimIndent()
        val f = File(base, "seq.docx")
        MarkdownDocx.write(f, "", md)
        val back = OfficeImport.docx(f.readBytes())
        val img = back.lines().mapNotNull { DocMarkup.image(it) }.single()
        assertEquals("abb-hoersaal", img.nr, back)
        assertEquals("Der Hörsaal", img.alt, back)
        assertTrue("<!-- tabelle: Kosten nach Phasen; nr=tab-kosten -->\n\n| Phase" in back, back)
        assertTrue("[Abbildung 1](#abb-hoersaal)" in back && "[Tabelle 1](#tab-kosten)" in back, back)
        assertFalse("Abbildung 1: Der Hörsaal" in back, "keine doppelte Beschriftung")
        val e = DocFields.entries(back)
        assertEquals(listOf("Abbildung 1", "Tabelle 1"), e.filter { it.kind != DocFields.Kind.HEADING }.map { it.label })
    }
}
