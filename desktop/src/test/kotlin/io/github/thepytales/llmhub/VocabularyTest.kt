package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.data.FileDownload
import io.github.thepytales.llmhub.data.HouseRule
import io.github.thepytales.llmhub.data.KnowledgePacks
import io.github.thepytales.llmhub.data.LoraHistory
import io.github.thepytales.llmhub.data.LoraPackages
import io.github.thepytales.llmhub.data.Template
import io.github.thepytales.llmhub.data.TemplateKind
import io.github.thepytales.llmhub.data.Term
import io.github.thepytales.llmhub.data.Vocabulary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Eigene Wörter und Wissenspakete (AP-78), Kana-Pakete teilen (AP-83). */
class VocabularyTest {
    private val mhb = Term("Modulhandbuch", "Beschreibung aller Module eines Studiengangs.", listOf("MHB"))
    private val zlsb = Term("ZLSB", "Zentrum für Lehrkräftebildung, Schul- und Berufsbildungsforschung", listOf("Zentrum für Lehrkräftebildung"), "Einrichtung", "https://tu-dresden.de/zlsb", "2026-11-10")
    private val terms = listOf(mhb to null, zlsb to "Lehramt an der TU Dresden")

    @Test
    fun ownWordsAreFoundAsWholeWordsAndAbbreviationsOnlyInCapitals() {
        assertEquals(listOf("Modulhandbuch"), Vocabulary.find("Öffne bitte das MHB von Grundschule", terms).map { it.term.term })
        assertTrue(Vocabulary.find("mhbx und zlsbeta", terms).isEmpty(), "nur ganze Wörter")
        assertTrue(Vocabulary.find("die zlsb-Seite", terms).isEmpty(), "kurze Abkürzungen nur in Großbuchstaben")
        assertEquals("ZLSB", Vocabulary.find("Termin beim zentrum für lehrkräftebildung", terms).single().term.term)
    }

    @Test
    fun theCommandModelGetsTheWordItselfAndTheSearchAllSpellings() {
        assertEquals("öffne das Modulhandbuch", Vocabulary.canonical("öffne das MHB", listOf(mhb)))
        assertEquals("öffne das Modulhandbuch", Vocabulary.canonical("öffne das Modulhandbuch", listOf(mhb)))
        val forms = Vocabulary.forms(terms)
        assertEquals(listOf("modulhandbuch"), forms("mhb"))
        assertEquals(listOf("mhb"), forms("Modulhandbuch"))
        assertTrue(forms("anderes").isEmpty())
        assertTrue("Modulhandbuch" in Vocabulary.expand("MHB Lehramt", terms))
    }

    @Test
    fun modelsGetAShortContextWithSourcesForPackEntries() {
        val ctx = Vocabulary.context("Wann hat das ZLSB Sprechzeit? Und wo finde ich das MHB?", terms)
        assertNotNull(ctx)
        assertTrue("- Modulhandbuch (auch: MHB): Beschreibung aller Module" in ctx, ctx)
        assertTrue("[Quelle: https://tu-dresden.de/zlsb, Stand 2026-11-10]" in ctx, ctx)
        assertNull(Vocabulary.context("Nichts davon hier.", terms))
    }

    @Test
    fun theLehramtPackHasASourceAndADateForEveryEntryAndMatchesTheCatalog() {
        val f = File("../tools/wissen/lehramt-tud.json")
        val raw = f.readText()
        val pack = KnowledgePacks.parse(raw)
        val count = Regex("\"begriff\"").findAll(raw).count()
        assertEquals(count, pack.entries.size, "Einträge ohne Quelle oder Stand fallen beim Lesen heraus – im Paket darf es keine geben")
        assertTrue(pack.entries.size >= 30)
        assertTrue(pack.entries.all { it.source.startsWith("https://") && it.explanation.isNotBlank() })
        // No names of persons (Frau …, Herr …) in a pack.
        assertFalse(Regex("\\b(Frau|Herr|Prof\\.|Dr\\.) [A-ZÄÖÜ]").containsMatchIn(raw))
        val offer = KnowledgePacks.offer("lehramt-tud")!!
        assertEquals(f.length(), offer.size, "Größe im Katalog")
        assertEquals(FileDownload.sha256(f), offer.sha256, "SHA-256 im Katalog (tools/wissen/README.md)")
        assertEquals(pack.version, offer.version)
        assertTrue(Regex("/[0-9a-f]{40}/tools/wissen/lehramt-tud.json$").containsMatchIn(offer.url), "fester Commit im Katalog eingetragen")
    }

    /**
     * „Das Paket ist beschädigt angekommen (Prüfsumme stimmt nicht)“ (1.0.76, Windows): the app now carries the pack
     * itself – the same file as tools/wissen, checked with the catalog's SHA-256 – and installs it without internet.
     */
    @Test
    fun theLehramtPackComesWithTheAppAndNeedsNoInternet() {
        val offer = KnowledgePacks.offer("lehramt-tud")!!
        assertEquals(File("../tools/wissen/lehramt-tud.json").readText(), KnowledgePacks.builtIn(offer), "Kopie in app/src/main/resources/wissen = tools/wissen")
        val base = kotlin.io.path.createTempDirectory("packs").toFile()
        try {
            val c = AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }))
            File(c.appContext.filesDir, "wissen/lehramt-tud.part").apply { parentFile.mkdirs(); writeText("kaputt") }
            KnowledgePacks.download(c, offer.copy(url = "http://127.0.0.1:9/nicht-erreichbar.json"))
            assertEquals(offer.version, KnowledgePacks.installed(c).single { it.id == offer.id }.version)
            assertTrue(File(c.appContext.filesDir, "wissen").listFiles()!!.none { it.extension == "part" }, "alte Teildatei entfernt")
        } finally {
            base.deleteRecursively()
        }
    }

    /**
     * Packs without a copy in the app are downloaded: the setup wizard loads recommended packs in the background, and a
     * click on „Laden“ meanwhile wrote into the same part file. Two downloads at once now give one request and an intact
     * pack, a part file left over from before is not taken for a finished download, and a broken transfer is tried again.
     */
    @Test
    fun aPackLoadedTwiceAtOnceOrBrokenOnceStillArrivesIntact() {
        val bytes = File("../tools/wissen/lehramt-tud.json").readText().replace("\"id\": \"lehramt-tud\"", "\"id\": \"probe-paket\"").toByteArray()
        assertTrue("probe-paket" in String(bytes))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        var breakFirst = false
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/pack.json") { ex ->
            val n = requests.incrementAndGet()
            val body = if (breakFirst && n == 1) bytes.copyOf().also { it[100] = 'X'.code.toByte() } else bytes
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { out -> body.toList().chunked(4096).forEach { out.write(it.toByteArray()); out.flush(); Thread.sleep(60) } }
        }
        server.start()
        val base = kotlin.io.path.createTempDirectory("packs").toFile()
        try {
            val c = AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }))
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val offer = KnowledgePacks.Offer("probe-paket", "Probe", "", 1, "http://127.0.0.1:${server.address.port}/pack.json", bytes.size.toLong(), sha)
            assertNull(KnowledgePacks.builtIn(offer))
            // Left over from an earlier, broken try: as long as the pack, but not the pack.
            File(c.appContext.filesDir, "wissen/probe-paket.part").apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes.size) { 'x'.code.toByte() }) }
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val threads = List(2) { Thread { runCatching { KnowledgePacks.download(c, offer) }.onFailure { errors += it } }.apply { start() } }
            threads.forEach { it.join(20_000) }
            assertTrue(errors.isEmpty(), "kein Fehler: $errors")
            assertEquals(1, requests.get(), "der zweite Aufruf wartet und findet das Paket fertig")
            assertNotNull(KnowledgePacks.installed(c).firstOrNull { it.id == offer.id })
            assertTrue(KnowledgePacks.loading.value.isEmpty())
            assertTrue(File(c.appContext.filesDir, "wissen").listFiles()!!.none { it.extension == "part" }, "keine Teildateien übrig")

            KnowledgePacks.delete(c, offer.id)
            requests.set(0)
            breakFirst = true
            KnowledgePacks.download(c, offer)
            assertEquals(2, requests.get(), "kaputte Übertragung einmal neu geladen")
            assertNotNull(KnowledgePacks.installed(c).firstOrNull { it.id == offer.id })
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun entriesWithoutSourceAreDroppedFromDownloadedPacks() {
        val text = """{"id":"probe","titel":"Probe","eintraege":[{"begriff":"A","erklaerung":"x","quelle":"https://a.de","stand":"2026-01-01"},{"begriff":"B","erklaerung":"ohne Quelle"},{"begriff":"C","quelle":"http://unsicher.de","stand":"2026-01-01"}]}"""
        assertEquals(listOf("A"), KnowledgePacks.parse(text).entries.map { it.term })
    }

    @Test
    fun aSharedPackCarriesNoPersonalDataAndPreviewsWhatChanges() {
        val history = listOf(LoraHistory.Entry("Ordne Hochgeladen nach Semester", at = 5, name = "Semester sortieren"), LoraHistory.Entry("geheimer Wunsch", at = 9))
        val tpl = Template(name = "Gutachten Abschlussarbeit", kind = TemplateKind.PROMPT, content = "Schreibe ein Gutachten zu {{Titel}}")
        val rule = HouseRule("Lehrer", "Lehrkräfte", "Gendern")
        val pack = LoraPackages.build("Professur Grundschule", "", LoraPackages.Part.entries.toSet(), listOf(mhb), history, listOf(tpl), listOf(rule))
        assertEquals("geteilt-professur-grundschule", pack.id)
        assertEquals(listOf("Semester sortieren"), pack.commands.map { it.name }, "nur angeheftete Befehle, kein Verlauf")
        assertEquals(0L, pack.commands.single().at)
        val text = LoraPackages.export(pack)
        assertFalse("geheimer Wunsch" in text)
        val back = LoraPackages.read(text)
        assertEquals(pack.copy(date = back.date), back)

        // On the second PC: the word exists with another explanation, the rule is new.
        val there = listOf(mhb.copy(explanation = "anders"))
        val pv = LoraPackages.preview(back, there, emptyList(), emptyList(), emptyList())
        assertTrue("Wort „Modulhandbuch“" in pv.replaced, pv.toString())
        assertTrue(pv.added.any { "Semester sortieren" in it } && pv.added.any { "Gutachten" in it } && pv.added.any { "Lehrer" in it }, pv.toString())
        val same = LoraPackages.preview(back, listOf(mhb), back.commands, listOf(tpl), listOf(rule))
        assertTrue(same.added.isEmpty() && same.replaced.isEmpty(), same.toString())
    }
}
