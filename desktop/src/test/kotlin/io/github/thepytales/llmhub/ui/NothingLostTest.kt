package io.github.thepytales.llmhub.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.agent.ActionHosts
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.docs.DocKind
import io.github.thepytales.llmhub.docs.DocTab
import io.github.thepytales.llmhub.docs.DocsStore
import io.github.thepytales.llmhub.docs.SheetEngine
import io.github.thepytales.llmhub.video.VideoClip
import io.github.thepytales.llmhub.video.VideoProject
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nichts geht verloren (AP-134): (a) the PDF-Werkzeugkasten writes its open PDFs down, so a hard end of the process
 * (here: forgetting everything in memory) loses nothing – at the next start *Ungespeicherte Arbeit wiederherstellen?*
 * offers it with *Wiederherstellen* and *Verwerfen*; the other editors write their stores within a second while one
 * works (checked on disk without closing the editor). (b) The Verlauf names the steps and goes back to one of them,
 * Wiederholen goes on from there.
 */
@OptIn(ExperimentalTestApi::class)
class NothingLostTest {
    private val base = Files.createTempDirectory("adeu-ap134").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() {
        PdfWork.forgetForTest()
        base.deleteRecursively()
    }

    private fun pdf(pages: Int): File = File(base, "bericht.pdf").also { f ->
        PDDocument().use { d -> repeat(pages) { d.addPage(PDPage()) }; d.save(f) }
    }

    private fun pages(f: File) = Loader.loadPDF(f).use { it.numberOfPages }

    /** A change of the open PDF like the screen makes it (Lora's way uses the same history). */
    private fun dropLastPage() = runBlocking {
        ActionHosts.changeOpenPdf!!("Seite löschen") { src, out ->
            Loader.loadPDF(src).use { d -> d.removePage(d.numberOfPages - 1); d.save(out) }
        }
    }

    @Test
    fun aPdfWithUnsavedChangesComesBackAfterAHardEnd() {
        val ctx = c.appContext
        val source = pdf(3)
        runBlocking { PdfWork.open(ctx, source) }
        assertTrue(dropLastPage() && dropLastPage())
        assertEquals(1, pages(PdfWork.tabs.single().file))

        // Hard end: nothing in memory survives, only the files.
        PdfWork.forgetForTest()
        val found = PdfWork.recoverable(ctx)
        assertEquals(1, found.size)
        assertEquals("bericht.pdf", found[0].name)
        assertEquals(2, found[0].history.size)

        val tab = PdfWork.restore(ctx, found[0])
        assertEquals(1, pages(tab.file), "der Stand vor dem Ende")
        assertTrue(tab.unsaved)
        assertEquals(source.absolutePath, tab.original?.absolutePath)
        // Rückgängig goes on after restoring.
        ActionHosts.undoOpenPdf!!.invoke()
        assertEquals(2, pages(PdfWork.tabs.single().file))
        assertTrue(PdfWork.recoverable(ctx).isEmpty(), "nicht doppelt angeboten")
    }

    @Test
    fun savedOrUnchangedPdfsAreNotOfferedAndVerwerfenDeletes() {
        val ctx = c.appContext
        runBlocking { PdfWork.open(ctx, pdf(2)) }
        val unchanged = PdfWork.tabs.single().file
        PdfWork.forgetForTest()
        assertTrue(PdfWork.recoverable(ctx).isEmpty(), "ohne Änderung nichts anzubieten")
        assertFalse(unchanged.exists(), "die Arbeitskopie wird aufgeräumt")

        runBlocking { PdfWork.open(ctx, pdf(2)) }
        dropLastPage()
        val tab = PdfWork.tabs.single()
        tab.savedFile = tab.file
        PdfWork.persist(ctx)
        PdfWork.forgetForTest()
        assertTrue(PdfWork.recoverable(ctx).isEmpty(), "gespeichert: nichts anzubieten")

        runBlocking { PdfWork.open(ctx, pdf(2)) }
        dropLastPage()
        val files = PdfWork.tabs.single().let { it.history + it.file }
        PdfWork.forgetForTest()
        val e = PdfWork.recoverable(ctx).single()
        PdfWork.discard(ctx, e)
        assertTrue(files.none { it.exists() }, "Verwerfen löscht die Arbeitskopien")
        PdfWork.forgetForTest()
        assertTrue(PdfWork.recoverable(ctx).isEmpty())
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 8) {
        repeat(rounds) { Thread.sleep(60); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    @Test
    fun theStartAsksAndRestoresIntoTheToolbox() = runSkikoComposeUiTest(Size(1000f, 800f), Density(1f)) {
        mainClock.autoAdvance = false
        val ctx = c.appContext
        runBlocking { PdfWork.open(ctx, pdf(2)) }
        assertTrue(dropLastPage(), "Änderung")
        assertEquals(1, PdfWork.tabs.single().history.size, File(ctx.filesDir, "pdf-arbeit.json").readText())
        PdfWork.forgetForTest()
        val opened = mutableListOf<String>()
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { RecoveryPrompt(c, onOpen = { opened += it }) }
            }
        }
        repeat(20) { if (onAllNodesWithText("Ungespeicherte Arbeit wiederherstellen?").fetchSemanticsNodes().isEmpty()) settle(2) }
        assertTrue(onAllNodesWithText("Ungespeicherte Arbeit wiederherstellen?").fetchSemanticsNodes().isNotEmpty(), "Frage fehlt")
        assertTrue(onAllNodesWithText("PDF-Werkzeugkasten · 1 Änderung(en) nicht gespeichert").fetchSemanticsNodes().isNotEmpty())
        onAllNodesWithText("Wiederherstellen").onFirst().performClick()
        settle(4)
        assertEquals(listOf("pdf"), opened)
        assertEquals(1, PdfWork.tabs.size)
        assertEquals(1, pages(PdfWork.tabs.single().file))
    }

    @Test
    fun theOtherEditorsAreOnDiskWithinASecond() = runSkikoComposeUiTest(Size(1400f, 1000f), Density(1f)) {
        mainClock.autoAdvance = false
        // Dokument: typing is stored 0.7 s after the last key, while the editor is still open (no clean close).
        val doc = DocsHandoff.open(c, "Bericht", "# Bericht\n\nErster Satz.\n")
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { DocPane(c, doc.id, onClose = {}) } }
            }
        }
        settle(14)
        val field = onAllNodesWithContentDescription("Dokumenttext").onFirst()
        field.performClick()
        field.performTextInput("Neu getippt ")
        settle(6)
        val disk = DocsStore(File(base, "files")).get(doc.id)!!.tabs.first().markdown
        assertTrue("Neu getippt" in disk, disk)
    }

    // ── (b) Verlauf ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDocumentVerlaufNamesStepsAndJumpsBack() {
        val s = FlowState("Eins\n\nZwei\n")
        val first = s.segs.filterIsInstance<FlowSeg.Text>().first()
        s.active = first
        first.selection = TextRange(first.rich.text.length)
        s.insertText(" und mehr")
        first.selection = TextRange(0, 4)
        s.insertText("Drei")
        val steps = s.history()
        assertEquals(listOf("Ersetzt: „Eins“ durch „Drei“", "Geschrieben: „und mehr“"), steps.map { it.label })
        assertTrue(steps.all { it.at > 0 })
        s.undoSteps(2)
        assertEquals("Eins\n\nZwei", s.markdown().trim())
        assertTrue(s.history().isEmpty())
        s.redo()
        assertTrue("und mehr" in s.markdown() && "Drei" !in s.markdown(), "Wiederholen geht weiter: " + s.markdown())
        assertEquals(1, s.history().size)
    }

    @Test
    fun namesForTablesSlidesAndVideo() {
        val a = SheetEngine.markdown(listOf(listOf("Name", "Note"), listOf("Ada", "1"), listOf("Bo", "2")))
        val b = SheetEngine.markdown(listOf(listOf("Name", "Note"), listOf("Ada", "1,3"), listOf("Bo", "2")))
        assertEquals("Zelle B2: „1,3“", StepNames.sheet(a, b))
        assertEquals("Zeile eingefügt", StepNames.sheet(a, SheetEngine.markdown(SheetEngine.grid(a) + listOf(listOf("Cy", "3")))))
        assertEquals("Spalte gelöscht", StepNames.sheet(a, SheetEngine.markdown(SheetEngine.grid(a).map { it.take(1) })))
        val tabs = listOf(DocTab(id = "t", title = "Tabelle 1", markdown = a))
        assertEquals("Umbenannt in „Noten“", StepNames.tabs(DocKind.SHEET, tabs, listOf(tabs[0].copy(title = "Noten"))))
        assertEquals("Tabellenblatt hinzugefügt", StepNames.tabs(DocKind.SHEET, tabs, tabs + DocTab(id = "u")))

        val deck = "# Start\n\n---\n\n# Inhalt\n\n- Punkt eins\n"
        assertEquals("Folie 2: Text geändert", StepNames.slides(deck, deck.replace("Punkt eins", "Punkt zwei")))
        assertEquals("Folie 1: Titel geändert", StepNames.slides(deck, deck.replace("# Start", "# Anfang")))
        assertEquals("Folie gelöscht", StepNames.slides(deck, "# Start\n"))

        assertEquals("Absatz eingefügt", StepNames.text("Eins Zwei", "Eins\nZwei"))
        assertEquals("Gelöscht: „Zwei“", StepNames.text("Eins Zwei", "Eins "))

        val clip = VideoClip(path = "a.mp4", inSec = 0.0, outSec = 10.0)
        val p = VideoProject(title = "Film", clips = listOf(clip))
        assertEquals("Clip 1 gekürzt", describeVideoStep(p, p.copy(clips = listOf(clip.copy(outSec = 8.0)))))
        assertEquals("Clip geteilt", describeVideoStep(p, p.copy(clips = listOf(clip.copy(outSec = 4.0), clip.copy(id = "b", inSec = 4.0)))))
        assertEquals("Clip gelöscht", describeVideoStep(p, p.copy(clips = emptyList())))
        assertEquals("Umbenannt in „Kurs“", describeVideoStep(p, p.copy(title = "Kurs")))
    }

    @Test
    fun theVerlaufDialogJumps() = runSkikoComposeUiTest(Size(800f, 800f), Density(1f)) {
        mainClock.autoAdvance = false
        var jumped = -1
        setContent {
            LlmHubTheme(AppSettings()) {
                HistoryDialog(
                    listOf(HistoryStep("Gelöscht: „Zwei“", System.currentTimeMillis()), HistoryStep("Geschrieben: „Eins“", System.currentTimeMillis() - 120_000)),
                    onJump = { jumped = it }, onDismiss = {},
                )
            }
        }
        settle(4)
        assertTrue(onAllNodesWithText("Jetzt").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("vor 2 Min.").fetchSemanticsNodes().isNotEmpty())
        onAllNodesWithText("Geschrieben: „Eins“").onFirst().performClick()
        settle(2)
        assertEquals(1, jumped, "zurück bis direkt nach diesem Schritt")
    }
}
