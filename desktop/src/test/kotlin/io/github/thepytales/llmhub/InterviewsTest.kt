package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.data.VaultStore
import io.github.thepytales.llmhub.transcribe.Code
import io.github.thepytales.llmhub.transcribe.CodeSuggest
import io.github.thepytales.llmhub.transcribe.Codebook
import io.github.thepytales.llmhub.transcribe.InterviewAnalysis
import io.github.thepytales.llmhub.transcribe.RefiQda
import io.github.thepytales.llmhub.transcribe.Segment
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.transcribe.TranscriptAnon
import io.github.thepytales.llmhub.transcribe.TranscriptCoding
import io.github.thepytales.llmhub.transcribe.withCoding
import io.github.thepytales.llmhub.ui.KeyList
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Interviews auswerten (AP-136): „drei Interviews pseudonymisiert, codiert und mit Codes in MAXQDA geöffnet“ – codings
 * of marked words with memos, pseudonymized copies that keep the codings (the marked words move with the text), one
 * REFI-QDA project with all three, back from MAXQDA with the codings in place, the key list only in the Tresor.
 */
class InterviewsTest {
    private val base = Files.createTempDirectory("adeu-ap136").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private val book = Codebook.parse("Lehrkräfte", "Planung: wie Unterricht geplant wird\n  Lernziele\nKlassenführung: Umgang mit Störungen\nKooperation: Zusammenarbeit im Kollegium")
    private fun code(name: String) = book.codes.first { it.name == name }

    private fun interview(n: Int, teacher: String, colleague: String, school: String) = Transcript(
        id = "i$n", title = "Interview $n", created = "2026-11-0$n", duration = 90.0, language = "de", modelName = "x", device = null,
        speakers = mapOf(0 to "I", 1 to teacher),
        segments = listOf(
            Segment(0, 0.0, 5.0, "Wie planen Sie Ihren Unterricht?", 0),
            Segment(1, 5.0, 30.0, "Ich plane mit Lernzielen. Mit $colleague bespreche ich das jeden Montag an der $school.", 1),
            Segment(2, 30.0, 50.0, "Störungen gibt es selten, dann rede ich mit der Klasse.", 1),
        ),
        audio = null, folder = "projekt", marks = emptyList(), notice = null, raw = JsonObject(emptyMap()),
    )

    /** Codes interview [t]: Planung on the whole passage 1, Kooperation on the colleague's sentence with a memo, Klassenführung on 2. */
    private fun coded(t: Transcript): Transcript {
        val s = t.segments
        val sentence = s[1].text.indexOf("Mit ")
        var c = TranscriptCoding(book.id, emptyList())
        c = c.toggle(s[1], code("Planung").id, "AB")
            .mark(s[1], code("Kooperation").id, "AB", sentence, s[1].text.length, memo = "Montag fest – Ritual?")
            .toggle(s[2], code("Klassenführung").id, "AB")
        return t.withCoding(c)
    }

    @Test
    fun markedWordsAndMemosStay() {
        val t = coded(interview(1, "Anna Müller", "Herrn Schmidt", "Oberschule Pirna"))
        val c = TranscriptCoding.of(t)
        assertEquals(3, c.codings.size)
        val coop = c.codings.single { it.codeId == code("Kooperation").id }
        assertFalse(coop.whole)
        assertEquals("Mit Herrn Schmidt bespreche ich das jeden Montag an der Oberschule Pirna.", coop.quote(t.segments[1]))
        assertEquals("Montag fest – Ritual?", coop.memo)
        // The same marked words twice stay once; whole and part are apart; removing and memos.
        assertEquals(c, c.mark(t.segments[1], code("Kooperation").id, "AB", coop.from!!, coop.to!!))
        val withoutWhole = c.toggle(t.segments[1], code("Planung").id, "AB")
        assertEquals(2, withoutWhole.codings.size)
        assertTrue(withoutWhole.codings.any { it.codeId == code("Kooperation").id })
        assertNull(c.withMemo(coop, " ").codings.single { it.codeId == code("Kooperation").id }.memo)
        assertEquals(2, c.remove(coop).codings.size)
    }

    @Test
    fun pseudonymizedCopiesKeepTheirCodings() {
        val t = coded(interview(1, "Anna Müller", "Herrn Schmidt", "Oberschule Pirna"))
        val found = TranscriptAnon.findings(t, emptyList(), extra = listOf("Oberschule Pirna"))
        val names = found.map { it.value }
        assertTrue("Anna Müller" in names && "Schmidt" in names && "Oberschule Pirna" in names, names.toString())
        assertTrue(found.first { it.value == "Oberschule Pirna" }.count >= 1)
        // Checked by hand: „Schmidt“ stays (e.g. a public person) – the rest is replaced.
        val r = TranscriptAnon.apply(t, found.filter { it.value != "Schmidt" }.map { it.value to it.pseudonym })
        val p = r.transcript
        assertTrue(p.title.endsWith("(pseudonymisiert)"))
        assertEquals("Person A", p.speakers[1])
        val text = p.segments[1].text
        assertTrue("Pirna" !in text && "Schmidt" in text, text)
        // The codings came along, the marked words moved with the shorter text.
        val c = TranscriptCoding.of(p)
        assertEquals(3, c.codings.size)
        val coop = c.codings.single { it.codeId == code("Kooperation").id }
        assertTrue(coop.quote(p.segments[1]).startsWith("Mit Herrn Schmidt bespreche"), coop.quote(p.segments[1]))
        assertTrue(coop.quote(p.segments[1]).endsWith("."), coop.quote(p.segments[1]))
        assertTrue("Pirna" !in coop.quote(p.segments[1]))
        assertEquals(listOf(0.0, 5.0, 30.0), p.segments.map { it.start })
    }

    @Test
    fun threeInterviewsAsOneProjectAndBack() {
        val raw = listOf(
            interview(1, "Anna Müller", "Herrn Schmidt", "Oberschule Pirna"),
            interview(2, "Ben Kowalski", "Frau Lange", "Gymnasium Bautzen"),
            interview(3, "Clara Neumann", "Herrn Vogt", "Grundschule Meißen"),
        ).map(::coded)
        val pseudo = raw.map { t ->
            val f = TranscriptAnon.findings(t, emptyList(), extra = listOf("Oberschule Pirna", "Gymnasium Bautzen", "Grundschule Meißen"))
            TranscriptAnon.apply(t, f.map { it.value to it.pseudonym }).transcript
        }
        val items = pseudo.map { it to TranscriptCoding.of(it) }
        val bytes = ByteArrayOutputStream().also { RefiQda.writeProject(it, "Lehrkräfte-Studie", items, book) }.toByteArray()
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { e -> entries[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val qde = entries.getValue("project.qde")
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(qde.byteInputStream())
        assertEquals(3, Regex("<TextSource ").findAll(qde).count())
        assertEquals(3, entries.keys.count { it.startsWith("Sources/") })
        assertEquals(9, Regex("<Coding guid").findAll(qde).count())
        assertEquals(3, Regex("<NoteRef ").findAll(qde).count())
        assertTrue("Ritual?" in qde)
        // No real name went into the project.
        val all = entries.values.joinToString("\n")
        listOf("Müller", "Kowalski", "Neumann", "Pirna", "Bautzen", "Meißen").forEach { assertTrue(it !in all, it) }
        // The marked words are where MAXQDA expects them (code points of the source text).
        val sourceText = entries.getValue(entries.keys.first { it.startsWith("Sources/") && entries[it]!!.contains("Person A") && entries[it]!!.contains("jeden Montag") })
        val sel = Regex("""<PlainTextSelection guid="[^"]+" name="([^"]*)" startPosition="(\d+)" endPosition="(\d+)"""").findAll(qde).first { it.groupValues[1].startsWith("Mit ") }
        val cps = sourceText.codePoints().toArray()
        val marked = String(cps, sel.groupValues[2].toInt(), sel.groupValues[3].toInt() - sel.groupValues[2].toInt())
        assertTrue(marked.startsWith("Mit ") && marked.endsWith("."), marked)

        // Back from MAXQDA: the codebook merges into the one of the same name, codings land on the same words.
        val back = RefiQda.read(bytes, "Lehrkräfte-Studie.qdpx")
        assertEquals(book.codes.map { it.name }, back.codebook!!.codes.map { it.name })
        assertEquals(book.codes[1].let { c -> book.code(c.parentId!!)!!.name }, back.codebook.code(back.codebook.codes[1].parentId!!)!!.name)
        val (merged, ids) = book.merge(back.codebook)
        assertEquals(book.codes.size, merged.codes.size)
        val fresh = pseudo.map { it.withCoding(TranscriptCoding(null, emptyList())) }
        back.sources.forEach { src ->
            val t = assertNotNull(InterviewAnalysis.matchSource(src.name, fresh))
            val r = RefiQda.place(t, src.copy(selections = src.selections.map { s -> s.copy(codeIds = s.codeIds.mapNotNull { ids[it] }) }), TranscriptCoding(merged.id, emptyList()))
            assertEquals(0, r.missed)
            val original = TranscriptCoding.of(pseudo.first { it.title == t.title })
            assertEquals(original.codings.map { Triple(it.codeId, it.from to it.to, it.memo) }.toSet(), r.coding.codings.map { Triple(it.codeId, it.from to it.to, it.memo) }.toSet())
        }
        // A source whose text was changed elsewhere: the coded words are found in the passages.
        val changed = back.sources.first().let { s -> s.copy(text = s.text.replace("#", "§")) }
        val target = InterviewAnalysis.matchSource(changed.name, fresh)!!
        val r = RefiQda.place(target, changed.copy(selections = changed.selections.map { s -> s.copy(codeIds = s.codeIds.mapNotNull { ids[it] }) }), TranscriptCoding(merged.id, emptyList()))
        assertTrue(r.placed >= 2, "${r.placed} / ${r.missed}")
    }

    @Test
    fun codebookFromMaxqdaAndNewCodes() {
        val qdc = """<?xml version="1.0" encoding="utf-8"?>
            <CodeBook xmlns="urn:QDA-XML:codebook:1.0" origin="MAXQDA">
              <Codes>
                <Code guid="A1" name="Planung" isCodable="true" color="#ff0000"><Description>wie geplant wird</Description>
                  <Code guid="A2" name="Lernziele" isCodable="true"/>
                  <Code guid="A3" name="Material" isCodable="true"/>
                </Code>
                <Code guid="B1" name="Elternarbeit" isCodable="true"/>
              </Codes>
            </CodeBook>""".trimIndent().toByteArray()
        val r = RefiQda.read(qdc, "Codes.qdc")
        val incoming = r.codebook!!
        assertEquals(listOf("Planung", "Lernziele", "Material", "Elternarbeit"), incoming.codes.map { it.name })
        assertEquals("#FF0000", incoming.codes[0].color)
        assertEquals("wie geplant wird", incoming.codes[0].description)
        val (merged, ids) = book.merge(incoming)
        // Planung and Lernziele were there; Material under Planung and Elternarbeit are new.
        assertEquals(book.codes.size + 2, merged.codes.size)
        assertEquals(code("Planung").id, ids[incoming.codes[0].id])
        assertEquals(code("Lernziele").id, ids[incoming.codes[1].id])
        val material = merged.codes.first { it.name == "Material" }
        assertEquals(code("Planung").id, material.parentId)
    }

    @Test
    fun matrixRetrievalAndSuggestions() {
        val items = listOf(
            coded(interview(1, "Anna Müller", "Herrn Schmidt", "Schule A")),
            coded(interview(2, "Ben Kowalski", "Frau Lange", "Schule B")).let { t ->
                val c = TranscriptCoding.of(t)
                t.withCoding(c.toggle(t.segments[2], code("Klassenführung").id, "AB"))
            },
        ).map { it to TranscriptCoding.of(it) }
        val counts = InterviewAnalysis.counts(items, book)
        assertEquals(listOf(1, 1), counts[code("Planung").id])
        assertEquals(listOf(1, 0), counts[code("Klassenführung").id])
        val hits = InterviewAnalysis.hits(items, book, code("Kooperation").id)
        assertEquals(listOf("Interview 1", "Interview 2"), hits.map { it.transcript.title })
        assertTrue(hits.all { it.coding.quote(it.segment).startsWith("Mit ") })
        val table = InterviewAnalysis.table(items, book)
        assertTrue("| Planung | 1 | 1 | 2 |" in table, table)
        assertTrue("| Planung › Lernziele | 0 | 0 | 0 |" in table, table)
        assertEquals("Interview 2", InterviewAnalysis.matchSource("interview 2 (pseudonymisiert).txt", items.map { it.first })?.title)
        // Kana's suggestions: only codes of the scheme, at most three.
        assertEquals(listOf("Kooperation", "Lernziele"), CodeSuggest.parse("- Kooperation\n„Planung › Lernziele“\nUnsinn", book).map { it.name })
        assertTrue(CodeSuggest.parse("keiner", book).isEmpty())
        assertTrue("Klassenführung: Umgang mit Störungen" in CodeSuggest.prompt(book, "Text")[1].content)
    }

    @Test
    fun keyListOnlyInTheTresor() {
        val c = AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }))
        val mapping = listOf("Anna Müller" to "Person A", "Oberschule Pirna" to "[Einrichtung 1]")
        // Without a Tresor nothing is written anywhere.
        assertEquals(VaultStore.State.NOT_SET_UP, c.vault.state.value)
        assertFalse(KeyList.save(c, "Interview 1", mapping))
        c.vault.setUp("4711")
        assertTrue(KeyList.save(c, "Interview 1", mapping))
        val entry = c.vault.chats.value.single { it.title.contains("Zuordnungsliste") }
        assertTrue(entry.vault)
        assertTrue("| Anna Müller | Person A |" in entry.messages.single().content)
        // Locked: nothing is written in the clear.
        c.vault.lock()
        assertFalse(KeyList.save(c, "Interview 2", mapping))
        val clear = base.walkTopDown().filter { it.isFile }.any { f -> runCatching { f.readText() }.getOrDefault("").contains("Anna Müller") }
        assertFalse(clear, "Schlüsselliste nirgends im Klartext")
    }
}
