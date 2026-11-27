package io.github.thepytales.llmhub.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.transcribe.Codebook
import io.github.thepytales.llmhub.transcribe.Segment
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.transcribe.TranscriptCoding
import io.github.thepytales.llmhub.transcribe.withCoding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AP-136 in the interface: code marked words, pseudonymize with checking, look at codes across interviews. */
@OptIn(ExperimentalTestApi::class)
class InterviewToolsUiTest {
    private val base = Files.createTempDirectory("adeu-ap136-ui").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private val book = Codebook.parse("Lehrkräfte", "Planung: wie Unterricht geplant wird\nKooperation: Zusammenarbeit im Kollegium")

    private fun interview(n: Int, teacher: String, colleague: String) = Transcript(
        id = "ui$n", title = "Interview $n", created = "2026-11-0$n", duration = 60.0, language = "de", modelName = "x", device = null,
        speakers = mapOf(0 to "I", 1 to teacher),
        segments = listOf(
            Segment(0, 0.0, 5.0, "Wie planen Sie?", 0),
            Segment(1, 5.0, 30.0, "Ich plane mit Lernzielen. Mit $colleague bespreche ich das jeden Montag.", 1),
        ),
        audio = null, folder = null, marks = emptyList(), notice = null, raw = JsonObject(emptyMap()),
    )

    private fun SkikoComposeUiTest.settle(rounds: Int = 8) {
        repeat(rounds) { Thread.sleep(60); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.show(content: @androidx.compose.runtime.Composable () -> Unit) {
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { content() } }
            }
        }
        settle(10)
    }

    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        runCatching { javax.imageio.ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "ap136-$name.png")) }
    }

    @Test
    fun markedWordsGetTheCodeAndANewCode() = runSkikoComposeUiTest(Size(1100f, 900f), Density(1f)) {
        val t = interview(1, "Anna Müller", "Herrn Schmidt")
        val seg = t.segments[1]
        var coding by androidx.compose.runtime.mutableStateOf(TranscriptCoding(book.id, emptyList()))
        var scheme by androidx.compose.runtime.mutableStateOf(book)
        show { CodePickerDialog(seg, coding, scheme, "AB", onCoding = { coding = it }, onCodebook = { scheme = it }, onDismiss = {}) }
        // Mark „Mit Herrn Schmidt …“ and code only these words.
        val from = seg.text.indexOf("Mit ")
        onNodeWithTag("code.passage").performTextInputSelection(TextRange(from, seg.text.length))
        settle(2)
        assertTrue(onAllNodesWithText("Markiert:", substring = true).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Kooperation").performClick()
        settle(2)
        val coop = coding.codings.single()
        assertEquals(from to seg.text.length, coop.from to coop.to)
        assertEquals("Mit Herrn Schmidt bespreche ich das jeden Montag.", coop.quote(seg))
        // A new code right here, used for the marked words at once.
        onNodeWithText("Neuer Code").performTextInput("Rituale")
        settle(1)
        onNodeWithText("Anlegen").performClick()
        settle(2)
        assertTrue(scheme.codes.any { it.name == "Rituale" })
        assertEquals(2, coding.codings.size)
        // A memo for the coding.
        onAllNodesWithText("Memo")[0].performTextInput("jeden Montag")
        settle(2)
        assertTrue(coding.codings.any { it.memo == "jeden Montag" })
        shot("codieren")
    }

    @Test
    fun pseudonymizeWithCheckingAndKeyListInTheTresor() = runSkikoComposeUiTest(Size(1100f, 950f), Density(1f)) {
        val t = interview(1, "Anna Müller", "Herrn Schmidt").withCoding(TranscriptCoding(book.id, emptyList()).toggle(interview(1, "Anna Müller", "Herrn Schmidt").segments[1], book.codes[0].id, "AB"))
        runBlocking { c.transcription.save(t) }
        c.vault.setUp("4711")
        var made: Transcript? = null
        show { PseudonymizeDialog(c, t, onDone = { made = it }, onDismiss = {}) }
        var waited = 0
        // Finding the names takes a moment (longer while other tests run beside it).
        while (onAllNodesWithText("Gefunden", substring = true).fetchSemanticsNodes().isEmpty() && waited < 500) { settle(1); waited++ }
        assertTrue(onAllNodesWithText("Anna Müller").fetchSemanticsNodes().isNotEmpty(), "Fundstellen nach ${waited} Runden")
        assertTrue(onAllNodesWithText("Schmidt").fetchSemanticsNodes().isNotEmpty())
        shot("pseudonymisieren")
        // „Schmidt“ stays: left out by hand.
        onNodeWithTag("fund:Schmidt").performClick()
        settle(1)
        onNodeWithText("Kopie pseudonymisieren").performClick()
        waited = 0
        while (made == null && waited < 100) { settle(1); waited++ }
        val copy = assertNotNull(made)
        val text = copy.segments.joinToString(" ") { it.text } + copy.speakers.values
        assertTrue("Müller" !in text && "Anna" !in text && "Schmidt" in text, text)
        assertEquals(null, copy.audio)
        assertEquals(1, TranscriptCoding.of(copy).codings.size)
        // Saved beside the original; the key list only in the Tresor.
        assertNotNull(runBlocking { c.transcription.load(copy.id) })
        assertNotNull(runBlocking { c.transcription.load(t.id) })
        assertTrue(c.vault.chats.value.any { it.title.contains("Zuordnungsliste") && "Anna Müller" in it.messages.first().content })
    }

    @Test
    fun codesAcrossInterviews() = runSkikoComposeUiTest(Size(1200f, 1000f), Density(1f)) {
        listOf(interview(1, "Anna Müller", "Herrn Schmidt"), interview(2, "Ben Kowalski", "Frau Lange")).forEach { t ->
            val s = t.segments[1]
            val cod = TranscriptCoding(book.id, emptyList()).toggle(s, book.codes[0].id, "AB").mark(s, book.codes[1].id, "AB", s.text.indexOf("Mit "), s.text.length, memo = "Montag")
            runBlocking { c.transcription.save(t.withCoding(cod)) }
        }
        c.codebooks.upsert(book)
        var opened: String? = null
        show { InterviewAnalysisSheet(c, folder = null, onOpen = { opened = it }, onDismiss = {}) }
        var waited = 0
        while (onAllNodesWithText("Interviews auswerten").fetchSemanticsNodes().isEmpty() || onAllNodesWithText("Transkripte werden gelesen", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            settle(1); if (++waited > 100) break
        }
        assertTrue(onAllNodesWithText("2 Transkripte, davon 2 codiert", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("auswerten")
        onNodeWithTag("code:Kooperation").performClick()
        settle(3)
        assertTrue(onAllNodesWithText("Code „Kooperation“ · 2 Stellen in 2 Interviews").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Memo: Montag").fetchSemanticsNodes().size == 2)
        onAllNodesWithText("Mit Frau Lange bespreche ich das jeden Montag.")[0].performClick()
        settle(2)
        assertEquals("ui2", opened)
        assertEquals("time:5.0", io.github.thepytales.llmhub.data.RecentWork.take(io.github.thepytales.llmhub.data.RecentWork.TRANSCRIPT, "ui2"))
        assertFalse(onAllNodesWithText("Code „Kooperation“", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
