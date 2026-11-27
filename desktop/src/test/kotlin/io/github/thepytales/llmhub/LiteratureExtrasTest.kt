package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.docs.ScanRaster
import io.github.thepytales.llmhub.files.SqliteReader
import io.github.thepytales.llmhub.research.Barcodes
import io.github.thepytales.llmhub.research.CitationCheck
import io.github.thepytales.llmhub.research.Citavi
import io.github.thepytales.llmhub.research.FullTexts
import io.github.thepytales.llmhub.research.LitSource
import io.github.thepytales.llmhub.research.LitWatchSpec
import io.github.thepytales.llmhub.research.LiteratureSources
import io.github.thepytales.llmhub.research.LiteratureWatch
import io.github.thepytales.llmhub.research.Person
import io.github.thepytales.llmhub.research.RefType
import io.github.thepytales.llmhub.research.Reference
import io.github.thepytales.llmhub.research.Ris
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AP-138 b–f: Zitierprüfung, Volltexte, Citavi, Literatur-Wächter, ISBN/DOI per Foto. */
class LiteratureExtrasTest {

    // ── (b) Zitierprüfung ────────────────────────────────────────────────────────────────────────────────────

    private val doc = """
        # Einleitung

        Unterrichtsqualität lässt sich in drei Dimensionen fassen (Praetorius et al., 2018; Kunter & Voss, 2011, S. 4).
        Helmke (2009) spricht von „einem Angebot, das die Lernenden nutzen müssen, damit es wirkt“ (S. 71).
        Klieme betont: „Die Tiefenstrukturen sind entscheidend für den Lernerfolg der Schülerinnen“ (Klieme, 2006).
        Es heißt auch „Qualität ist kein Zufall, sondern das Ergebnis harter Arbeit“ – ohne Beleg.
        Kurze Begriffe wie „Angebot“ zählen nicht. Siehe auch (vgl. von Aster, 2020a) und (Müller 2019, 2021).

        ## Literatur

        Helmke, A. (2009). *Unterrichtsqualität und Lehrerprofessionalität* (2. Aufl.). Klett-Kallmeyer.

        Klieme, E. (2006). Empirische Unterrichtsforschung. *Zeitschrift für Pädagogik*, *52*(6), 765–773.

        Praetorius, A.-K., Klieme, E., Herbert, B., & Pinger, P. (2018). Generic dimensions of teaching quality. *ZDM*, *50*(3), 407–426.

        von Aster, M. (2020). Rechenschwäche. Hogrefe.

        Terhart, E. (2011). Lehrerberuf und Professionalität. In W. Helsper & R. Tippelt (Hrsg.), *Pädagogische Professionalität* (S. 202–224). Beltz.
    """.trimIndent()

    @Test
    fun citationCheckFindsGapsBothWays() {
        val r = CitationCheck.check(doc)
        assertEquals(5, r.entries)
        val notInList = r.findings.filter { it.kind == CitationCheck.Kind.NOT_IN_LIST }.map { it.text }
        assertEquals(listOf("(Praetorius et al., 2018; Kunter & Voss, 2011, S. 4)", "(Müller 2019, 2021)", "(Müller 2019, 2021)"), notInList)
        val notCited = r.findings.filter { it.kind == CitationCheck.Kind.NOT_CITED }.map { it.text }
        assertEquals(1, notCited.size)
        assertTrue(notCited.single().startsWith("Terhart, E. (2011)"))
        assertEquals(listOf("„Die Tiefenstrukturen sind entscheidend für den Lernerfolg der Schülerinnen“"), r.findings.filter { it.kind == CitationCheck.Kind.QUOTE_WITHOUT_PAGE }.map { it.text })
        assertEquals(listOf("„Qualität ist kein Zufall, sondern das Ergebnis harter Arbeit“"), r.findings.filter { it.kind == CitationCheck.Kind.QUOTE_WITHOUT_SOURCE }.map { it.text })
        assertEquals(3, r.quotes)
        assertTrue("Wörtliches Zitat ohne Seitenangabe" in r.text())
    }

    @Test
    fun citationCheckWithNumbersAndKnownEntries() {
        val a = Reference(id = "a", authors = listOf(Person("Helmke", "A.")), year = "2009", title = "Unterrichtsqualität")
        val b = Reference(id = "b", authors = listOf(Person("Klieme", "E.")), year = "2006", title = "Unterrichtsforschung")
        val c = Reference(id = "c", authors = listOf(Person("Lipowsky", "F.")), year = "2015", title = "Unterricht")
        val text = "Wie gezeigt [1], [3, 4] und [2–3].\n\n## Literatur\n\n<!-- literatur: IEEE; a, b, c -->\n\n[1] x\n\n[2] y\n\n[3] z\n"
        val r = CitationCheck.check(text, listOf(a, b, c), numbered = true)
        assertEquals(listOf("[3, 4]"), r.findings.filter { it.kind == CitationCheck.Kind.NOT_IN_LIST }.map { it.text })
        assertTrue(r.findings.none { it.kind == CitationCheck.Kind.NOT_CITED })
        // A clean text with author-year citations.
        val ok = CitationCheck.check("Siehe Helmke (2009, S. 3) und (Klieme 2006).", listOf(a, b))
        assertTrue(ok.ok, ok.text())
    }

    // ── (c) Volltexte ────────────────────────────────────────────────────────────────────────────────────────

    private fun fake(routes: (okhttp3.Request) -> Pair<Int, Pair<String, ByteArray>>) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val (code, body) = routes(req)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.second.toResponseBody(body.first.toMediaType())).build()
    }).build()

    @Test
    fun fullTextFromOpenAlexIsStoredAsPdf() = runBlocking {
        val pdf = "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF".toByteArray()
        val asked = mutableListOf<String>()
        val http = fake { req ->
            asked += req.url.host + req.url.encodedPath
            when {
                req.url.host == "api.openalex.org" -> 200 to ("application/json" to """{"id":"https://openalex.org/W1","best_oa_location":{"is_oa":true,"pdf_url":"https://repo.example.org/login","landing_page_url":"https://repo.example.org/a","version":"acceptedVersion","license":"cc-by","source":{"display_name":"Qucosa"}},"locations":[{"is_oa":true,"pdf_url":"https://repo.example.org/a.pdf","version":"publishedVersion","license":"cc-by","source":{"display_name":"ZDM"}}]}""".toByteArray())
                req.url.host == "api.unpaywall.org" -> 404 to ("application/json" to "{}".toByteArray())
                req.url.encodedPath == "/a.pdf" -> 200 to ("application/pdf" to pdf)
                else -> 200 to ("text/html" to "<html>Login</html>".toByteArray())
            }
        }
        val root = Files.createTempDirectory("kana").toFile()
        val ref = Reference(type = RefType.ARTICLE, authors = listOf(Person("Praetorius", "A."), Person("Klieme", "E."), Person("Herbert", "B.")), year = "2018",
            title = "Generic dimensions of teaching quality: The German framework", doi = "10.1007/s11858-018-0918-4")
        val out = FullTexts.fetch(LiteratureSources(http), http, ref, root)
        assertTrue(out is FullTexts.Outcome.Stored, out.toString())
        assertEquals("Literatur/Volltexte/Praetorius et al. 2018 - Generic dimensions of teaching quality.pdf", out.relative)
        assertTrue(FullTexts.isPdf(out.file.readBytes()))
        // The published version comes first; the login page (HTML) is never stored.
        assertEquals("ZDM · Verlagsfassung · CC-BY", FullTexts.describe(out.from))
        assertTrue(asked.any { it.startsWith("api.unpaywall.org") })
        root.deleteRecursively()
        // Nothing free: NotFound.
        val none = fake { 404 to ("application/json" to "{}".toByteArray()) }
        assertEquals(FullTexts.Outcome.NotFound, FullTexts.fetch(LiteratureSources(none), none, ref, root))
    }

    // ── (d) Citavi ───────────────────────────────────────────────────────────────────────────────────────────

    private val citavi = javaClass.getResource("/citavi/beispiel.ctv6")!!.readBytes()

    @Test
    fun sqliteReaderReadsTablesOverflowAndTypes() {
        val db = SqliteReader(citavi)
        assertTrue(db.tables.map { it.name }.containsAll(listOf("Reference", "Person", "KnowledgeItem")))
        val refs = db.read("Reference")
        assertEquals(405, refs.size)
        // A long abstract on overflow pages comes back whole.
        val long = refs.first { it["Title"] == "Füllband 000" }["Abstract"] as String
        assertEquals("Langer Abstract ".repeat(600), long)
        val counter = db.read("Counter").associateBy { it["Nr"] }
        assertEquals(51, counter.size)
        assertEquals(-7L, counter[-7L]!!["Nr"])
        assertEquals(3.25, counter[-7L]!!["Value"])
        assertEquals(listOf<Byte>(3, -1, 0), (counter[3000L]!!["Blob"] as ByteArray).toList())
        assertEquals(-4.5, counter[3000L]!!["Value"])
        assertNull(db.table("GibtEsNicht"))
        assertEquals(listOf("a", "b c", "d") to "a", SqliteReader.columns("CREATE TABLE t (a INTEGER PRIMARY KEY, \"b c\" TEXT, d, PRIMARY KEY (a))"))
    }

    @Test
    fun citaviProjectWithNotesKeywordsAndCategories() {
        val res = Citavi.read(citavi, "Masterarbeit")
        assertEquals(405, res.refs.size)
        val a = res.refs.first { it.title.startsWith("Generic") }
        assertEquals(RefType.ARTICLE, a.type)
        assertEquals("Generic dimensions of teaching quality: The German framework of Three Basic Dimensions", a.title)
        assertEquals(listOf("Praetorius", "Klieme"), a.authors.map { it.family })
        assertEquals("ZDM Mathematics Education", a.container)
        assertEquals("407-426", a.pages)
        assertEquals("1863-9690", a.issn)
        assertEquals(listOf("Unterrichtsqualität"), a.keywords)
        assertEquals(listOf("Citavi: 1 Theorie › 1.1 Basisdimensionen"), a.lists)
        assertEquals("Die drei Basisdimensionen über Fächer.", a.abstract)
        assertTrue(a.note.startsWith("Zentral für Kapitel 2\nBewertung: sehr gut\n• Drei Basisdimensionen – „Klassenführung, Unterstützung, Aktivierung“ (S. 410)"), a.note)
        val chapter = res.refs.first { it.type == RefType.CHAPTER }
        assertEquals("Pädagogische Professionalität", chapter.container)
        assertEquals(listOf("Helsper", "Tippelt"), chapter.editors.map { it.family })
        assertEquals("Beltz", chapter.publisher)
        assertEquals("2011", chapter.year)
        assertEquals("202–224", chapter.pages)
        assertEquals(listOf("Citavi: Masterarbeit"), chapter.lists)
        val web = res.refs.first { it.type == RefType.WEB }
        assertEquals("2026-03-03", web.accessed)
        assertTrue(web.authors.single().literal)
        assertEquals("von Aster", res.refs.first { it.title == "Lehrerprofessionalität" }.authors.single().family)
        assertEquals(RefType.EDITED_BOOK, res.refs.first { it.title == "Pädagogische Professionalität" }.type)
        assertEquals(2, res.keywords)
        assertEquals(1, res.knowledge)
        // Keywords also from RIS (Citavi export).
        val ris = Ris.parse("TY  - JOUR\nAU  - Müller, Hans\nTI  - Titel\nPY  - 2020\nKW  - Inklusion\nKW  - Schule; Lehrkräfte\nER  - \n").single()
        assertEquals(listOf("Inklusion", "Schule", "Lehrkräfte"), ris.keywords)
        assertTrue("KW  - Inklusion" in Ris.write(listOf(ris)))
    }

    // ── (e) Literatur-Wächter ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun watcherShowsOnlyNewWorksOfThePeriod() = runBlocking {
        val asked = mutableListOf<String>()
        val http = fake { req ->
            asked += req.url.toString()
            when (req.url.host) {
                "api.openalex.org" -> 200 to ("application/json" to """{"results":[
                    {"id":"https://openalex.org/W1","title":"Classroom management in the first year","publication_year":2026,"doi":"https://doi.org/10.1/neu","authorships":[{"author":{"display_name":"Anna Neu"}}]},
                    {"id":"https://openalex.org/W2","title":"Schon gesehen","publication_year":2026,"doi":"https://doi.org/10.1/alt","authorships":[]}]}""".toByteArray())
                else -> 500 to ("application/json" to "{}".toByteArray())
            }
        }
        val spec = LitWatchSpec("Klassenführung Berufseinstieg", listOf("OPENALEX", "CROSSREF"), "Wächter: Klassenführung", seen = listOf(Reference(title = "Schon gesehen", year = "2026", doi = "10.1/alt").dedupeKey), lastRun = "2026-11-16")
        val found = LiteratureWatch.search(LiteratureSources(http), spec, emptySet(), today = LocalDate.of(2026, 11, 23))
        assertEquals(listOf("Classroom management in the first year"), found.fresh.map { it.title })
        assertEquals("2026-11-23", found.spec.lastRun)
        assertEquals(2, found.spec.seen.size)
        assertTrue(asked.first().contains("from_publication_date%3A2026-11-15"), asked.first())
        val text = LiteratureWatch.report(spec, found.fresh)
        assertTrue(text.startsWith("1 neue Werke zu „Klassenführung Berufseinstieg“"), text)
        // A second run finds nothing new.
        assertTrue(LiteratureWatch.search(LiteratureSources(http), found.spec, emptySet(), today = LocalDate.of(2026, 11, 30)).fresh.isEmpty())
        val task = LiteratureWatch.task("Klassenführung", "m", listOf(LitSource.OPENALEX), weekday = 1, hour = 7)
        assertEquals(listOf(1), task.days)
        assertTrue(!task.notify && task.litWatch != null && task.litWatch!!.list == "Wächter: Klassenführung")
    }

    // ── (f) ISBN/DOI per Foto ────────────────────────────────────────────────────────────────────────────────

    /** Draws the EAN-13 [code] as bars (module width [m] px) on a light, slightly uneven background. */
    private fun barcode(code: String, m: Int = 3): ScanRaster {
        val L = listOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
        val g = L.map { c -> c.map { if (it == '0') '1' else '0' }.joinToString("").reversed() }
        val r = L.map { c -> c.map { if (it == '0') '1' else '0' }.joinToString("") }
        val parity = listOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")[code[0] - '0']
        val bits = StringBuilder("101")
        (1..6).forEach { i -> val d = code[i] - '0'; bits.append(if (parity[i - 1] == 'L') L[d] else g[d]) }
        bits.append("01010")
        (7..12).forEach { i -> bits.append(r[code[i] - '0']) }
        bits.append("101")
        val w = (bits.length + 30) * m
        val h = 120
        val img = ScanRaster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val bit = (x / m - 15).let { it in bits.indices && bits[it] == '1' }
            val light = 230 - x * 30 / w
            val v = if (bit) 30 else light
            img[x, y] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return img
    }

    @Test
    fun isbnBarcodeIsReadOnTheDevice() {
        val code = "9783407250001"
        assertTrue(Barcodes.checkDigitOk(code))
        assertEquals(code, Barcodes.isbn(barcode(code)))
        assertEquals(code, Barcodes.isbn(barcode(code, 2).rotated(1)))
        assertEquals("9780306406157", Barcodes.isbn(barcode("9780306406157", 4).rotated(2)))
        assertNull(Barcodes.isbn(ScanRaster(300, 100, IntArray(300 * 100) { -1 })))
        val (dois, isbns) = Barcodes.identifiers("Zeitschrift für Pädagogik, DOI: 10.3262/ZP1806765. ISBN 978-3-407-25000-1 oder ISBN 3-407-25000-X? ISBN 978-3-407-25000-3")
        assertEquals(listOf("10.3262/ZP1806765"), dois)
        assertEquals(listOf("9783407250001"), isbns)
    }
}
