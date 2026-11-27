package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.DocCitations
import io.github.thepytales.llmhub.research.BibEntry
import io.github.thepytales.llmhub.research.Bibliography
import io.github.thepytales.llmhub.research.CitationStyle
import io.github.thepytales.llmhub.research.CitationStyles
import io.github.thepytales.llmhub.research.CslLibrary
import io.github.thepytales.llmhub.research.CslRepository
import io.github.thepytales.llmhub.research.CslStyle
import io.github.thepytales.llmhub.research.Person
import io.github.thepytales.llmhub.research.RefType
import io.github.thepytales.llmhub.research.Reference
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AP-138 a: citation styles in CSL – real styles of the official collection (test resources, CC BY-SA 3.0). */
class CslTest {
    private fun xml(name: String) = javaClass.getResource("/csl/$name.csl")!!.readText()
    private fun style(name: String) = CslStyle.parse(xml(name), name)

    private val article = Reference(
        id = "a", type = RefType.ARTICLE,
        authors = listOf(Person("Praetorius", "Anna-Katharina"), Person("Klieme", "Eckhard"), Person("Herbert", "Benjamin"), Person("Pinger", "Petra")),
        year = "2018", title = "Generic dimensions of teaching quality: The German framework of Three Basic Dimensions",
        container = "ZDM", volume = "50", issue = "3", pages = "407-426", doi = "10.1007/s11858-018-0918-4",
    )
    private val book = Reference(
        id = "b", type = RefType.BOOK, authors = listOf(Person("Helmke", "Andreas")), year = "2009",
        title = "Unterrichtsqualität und Lehrerprofessionalität", edition = "2", publisher = "Klett-Kallmeyer", place = "Seelze",
    )
    private val chapter = Reference(
        id = "c", type = RefType.CHAPTER, authors = listOf(Person("Terhart", "Ewald")), year = "2011", title = "Lehrerberuf und Professionalität",
        editors = listOf(Person("Helsper", "Werner"), Person("Tippelt", "Rudolf")), container = "Pädagogische Professionalität",
        pages = "202–224", publisher = "Beltz", place = "Weinheim",
    )
    private val web = Reference(
        id = "w", type = RefType.WEB, authors = listOf(Person("Kultusministerkonferenz", literal = true)), year = "2022",
        title = "Standards für die Lehrerbildung", url = "https://www.kmk.org/x.pdf", accessed = "2026-03-03",
    )
    private val edited = Reference(
        id = "e", type = RefType.EDITED_BOOK, editors = listOf(Person("Helsper", "Werner"), Person("Tippelt", "Rudolf")), year = "2011",
        title = "Pädagogische Professionalität", publisher = "Beltz", place = "Weinheim",
    )
    private val all = listOf(article, book, chapter, web, edited)

    @AfterTest
    fun reset() {
        CitationStyles.library = null
    }

    /** Lines of [name] (non-breaking spaces of the styles as plain spaces, for comparing). */
    private fun lines(name: String) = style(name).bibliography(all).associate { it.first.id to it.second.replace('\u00a0', ' ') }

    @Test
    fun ieeeNumbersAlphabeticallyAndQuotesTitles() {
        val s = style("ieee")
        assertEquals("numeric", s.format)
        assertTrue(s.numeric)
        val out = s.bibliography(all)
        assertEquals(listOf("b", "e", "w", "a", "c"), out.map { it.first.id })
        assertEquals("[1] A. Helmke, *Unterrichtsqualität und Lehrerprofessionalität*, 2nd ed. Seelze: Klett-Kallmeyer, 2009.", out[0].second)
        assertEquals(
            "[4] A.-K. Praetorius, E. Klieme, B. Herbert, and P. Pinger, “Generic dimensions of teaching quality: The German framework of Three Basic Dimensions”, *ZDM*, vol. 50, no. 3, pp. 407–426, 2018, doi: 10.1007/s11858-018-0918-4.",
            out[3].second,
        )
        assertEquals("[2]", s.cite(listOf(edited to 2)))
    }

    @Test
    fun harvardWithEtAlAndEditors() {
        val l = lines("harvard-cite-them-right")
        assertEquals("Helmke, A. (2009) *Unterrichtsqualität und Lehrerprofessionalität*. 2nd edn. Seelze: Klett-Kallmeyer.", l["b"])
        assertTrue(l["a"]!!.startsWith("Praetorius, A.-K. *et al.* (2018) “Generic dimensions"), l["a"])
        assertEquals("Terhart, E. (2011) “Lehrerberuf und Professionalität”, in W. Helsper and R. Tippelt (eds) *Pädagogische Professionalität*. Weinheim: Beltz, pp. 202–224.", l["c"])
        assertEquals("(Praetorius *et al.*, 2018)", style("harvard-cite-them-right").cite(listOf(article to 1)))
    }

    @Test
    fun germanStylesUseGermanTerms() {
        val dgps = lines("deutsche-gesellschaft-fur-psychologie")
        assertEquals("Helmke, A. (2009). *Unterrichtsqualität und Lehrerprofessionalität* (2. Auflage). Seelze: Klett-Kallmeyer.", dgps["b"])
        assertEquals("Helsper, W. & Tippelt, R. (Hrsg.). (2011). *Pädagogische Professionalität*. Weinheim: Beltz.", dgps["e"])
        assertTrue(dgps["c"]!!.contains("In W. Helsper & R. Tippelt (Hrsg.), *Pädagogische Professionalität* (S. 202–224)"), dgps["c"])
        val din = lines("din-1505-2")
        assertEquals("Helmke, Andreas: *Unterrichtsqualität und Lehrerprofessionalität*. 2. Aufl. Seelze : Klett-Kallmeyer, 2009", din["b"])
        assertTrue(din["a"]!!.contains("In: *ZDM* Bd. 50 (2018), Nr. 3, S. 407–426"), din["a"])
        assertEquals("(Praetorius u. a., 2018)", style("din-1505-2").cite(listOf(article to 1)))
        assertTrue(style("din-1505-2").german)
    }

    @Test
    fun apaAndOthersRenderWithoutGluedWords() {
        val apa = lines("apa")
        assertEquals("Helmke, A. (2009). *Unterrichtsqualität und Lehrerprofessionalität* (2nd ed.). Klett-Kallmeyer.", apa["b"])
        assertTrue(apa["a"]!!.endsWith("*ZDM*, *50*(3), 407–426. https://doi.org/10.1007/s11858-018-0918-4"), apa["a"])
        assertEquals("Helsper, W., & Tippelt, R. (Eds.). (2011). *Pädagogische Professionalität*. Beltz.", apa["e"])
        // Springer and Nature: their own forms; German titles keep their capitals (title case only for English works).
        assertEquals("Helmke A (2009) Unterrichtsqualität und Lehrerprofessionalität, 2nd edn. Klett-Kallmeyer, Seelze", lines("springer-basic-author-date")["b"])
        assertTrue(lines("nature")["b"]!!.startsWith("1. Helmke, A. *Unterrichtsqualität und Lehrerprofessionalität*."))
        for (name in listOf("ieee", "harvard-cite-them-right", "din-1505-2", "apa", "springer-basic-author-date", "nature", "deutsche-gesellschaft-fur-psychologie", "elife")) {
            style(name).bibliography(all).forEach { (_, line) ->
                assertTrue(line.isNotBlank() && "  " !in line && ".." !in line && " ," !in line, "$name: $line")
            }
        }
    }

    @Test
    fun yearSuffixesForSameAuthorAndYear() {
        val a2 = book.copy(id = "b2", title = "Andere Arbeit", edition = "")
        val s = style("harvard-cite-them-right")
        val out = s.bibliography(listOf(book, a2)).map { it.second }
        assertTrue(out[0].startsWith("Helmke, A. (2009a) *Andere Arbeit*"), out[0])
        assertTrue(out[1].startsWith("Helmke, A. (2009b) *Unterrichtsqualität"), out[1])
        assertEquals("(Helmke, 2009b)", s.cite(listOf(book to 1), s.yearSuffixes(listOf(book, a2))))
    }

    @Test
    fun badFilesGiveReadableErrors() {
        assertFailsWith<IllegalArgumentException> { CslStyle.parse("<html><body>nope</body></html>") }
        assertFailsWith<IllegalArgumentException> { CslStyle.parse("kein xml") }
        // External entities are refused (files come from the internet).
        assertFailsWith<IllegalArgumentException> {
            CslStyle.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><style>&e;</style>")
        }
    }

    @Test
    fun libraryStoresStylesAndResolvesDependentOnes() = runBlocking {
        val dir = Files.createTempDirectory("csl").toFile()
        val lib = CslLibrary(dir)
        val dependent = """<?xml version="1.0" encoding="utf-8"?>
            <style xmlns="http://purl.org/net/xbiblio/csl" version="1.0" default-locale="de-DE">
              <info><title>Zeitschrift für Testpädagogik</title><id>http://www.zotero.org/styles/zeitschrift-fur-testpadagogik</id>
                <link href="http://www.zotero.org/styles/harvard-cite-them-right" rel="independent-parent"/>
                <category citation-format="author-date"/></info>
            </style>"""
        var asked = ""
        val st = lib.add(dependent) { id -> asked = id; xml(id) }
        assertEquals("harvard-cite-them-right", asked)
        assertEquals("zeitschrift-fur-testpadagogik", st.id)
        assertTrue(st.german)
        // The parent's rules with German terms of the dependent style.
        assertEquals("Helmke, A. (2009) *Unterrichtsqualität und Lehrerprofessionalität*. 2. Aufl. Seelze: Klett-Kallmeyer.", st.bibliography(listOf(book)).single().second)
        lib.add(xml("ieee"))
        assertEquals(listOf("ieee", "zeitschrift-fur-testpadagogik"), CslLibrary(dir).list().map { it.id })
        // A dependent style whose parent cannot be loaded is refused and nothing is stored.
        assertFailsWith<IllegalArgumentException> { lib.add(dependent.replace("testpadagogik", "x").replace("harvard-cite-them-right", "gibt-es-nicht")) { null } }
        assertEquals(2, lib.list().size)

        CitationStyles.library = lib
        val choice = CitationStyles.resolve("csl:ieee")
        assertTrue(choice.label.startsWith("IEEE"), choice.label)
        assertEquals(CitationStyle.IEEE, choice.style)
        assertEquals("csl:ieee", CitationStyles.find("ieee")?.key)
        assertEquals("APA7_DE", CitationStyles.find("apa")?.key)
        assertEquals(CitationStyle.APA7_DE, CitationStyles.resolve("csl:gelöscht").style)
        assertNull(CitationStyle.parse("csl:ieee"))
        assertEquals(10, CitationStyles.choices().size)

        // A document: the marker remembers the CSL style, in-text citations and list follow it.
        val result = Bibliography.build(listOf(BibEntry(article), BibEntry(book)), choice)
        assertEquals("csl:ieee", result.key)
        val md = DocCitations.withBibliography("Text [1].", listOf(article, book), choice)
        assertTrue("<!-- literatur: csl:ieee; b, a -->" in md, md)
        assertTrue("[2] A.-K. Praetorius" in md, md)
        val cited = DocCitations.cited(md)
        assertEquals(listOf("b", "a"), cited.ids)
        assertEquals("csl:ieee", cited.choice?.key)
        assertEquals("[2]", DocCitations.inText(article, cited.choice!!, DocCitations.number(listOf(article, book), article, cited.choice!!)))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun repositoryIndexSearch() {
        val index = CslRepository.parseIndex(
            """[{"title":"Zeitschrift für Pädagogik","name":"zeitschrift-fur-padagogik","dependent":0,"categories":{"format":"author-date","fields":["social_science"]}},
                {"title":"Technische Universität Dresden - Medizin","name":"technische-universitat-dresden-medizin","dependent":0,"categories":{"format":"numeric","fields":["medicine"]}},
                {"title":"Nature","name":"nature","dependent":0,"categories":{"format":"numeric"}},
                {"title":"Nature Communications","name":"nature-communications","dependent":1,"categories":{"format":"numeric"}}]""",
        )
        assertEquals(4, index.size)
        assertEquals(listOf("technische-universitat-dresden-medizin"), CslRepository.rank(index, "TU Dresden".replace("TU", "Universität"), 10).map { it.name })
        assertEquals(listOf("nature", "nature-communications"), CslRepository.rank(index, "nature", 10).map { it.name })
        assertEquals("zeitschrift-fur-padagogik", CslRepository.rank(index, "pädagogik", 10).single().name)
        assertTrue(CslRepository.rank(index, "", 10).isEmpty())
    }
}
