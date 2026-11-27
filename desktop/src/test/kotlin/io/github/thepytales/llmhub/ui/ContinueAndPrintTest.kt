package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.data.RecentWork
import io.github.thepytales.llmhub.docs.DocKind
import io.github.thepytales.llmhub.docs.SlideDeck
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AP-135 (b) Weiterarbeiten, (c) Tastenübersicht je Werkzeug, (d) Strg+P überall: the editors note where one stopped
 * and open there again from the list; Strg+/ shows the keys of the open editor first; Strg+P shows a preview of what
 * the editor prints and prints through the system's dialog.
 */
@OptIn(ExperimentalTestApi::class)
class ContinueAndPrintTest {
    private val base = Files.createTempDirectory("adeu-ap135bcd").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @BeforeTest
    fun fresh() = RecentWork.reset()

    @AfterTest
    fun cleanUp() {
        RecentWork.reset()
        io.github.thepytales.llmhub.docs.DocPrint.dialog = null
        base.deleteRecursively()
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 8) {
        repeat(rounds) { Thread.sleep(80); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    /** Pictures of the new parts for the README and the Vorher/Nachher-Seite: build/screenshots/ap135-*.png. */
    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        runCatching { javax.imageio.ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "ap135-$name.png")) }
    }

    private fun SkikoComposeUiTest.show(content: @androidx.compose.runtime.Composable () -> Unit) {
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { content() } }
            }
        }
        settle(12)
    }

    @Test
    fun listKeepsEachFileOnceNewestFirst() {
        var list = emptyList<RecentWork.Entry>()
        repeat(15) { i -> list = RecentWork.add(list, RecentWork.Entry(RecentWork.DOC, "d$i", "Dokument $i")) }
        assertEquals(RecentWork.MAX, list.size)
        assertEquals("d14", list.first().ref)
        list = RecentWork.add(list, RecentWork.Entry(RecentWork.DOC, "d10", "Dokument 10", "text:0:4", "Seite 2"))
        assertEquals("d10", list.first().ref)
        assertEquals(1, list.count { it.ref == "d10" })
        assertEquals(3, RecentWork.number("page:3", "page"))
        assertNull(RecentWork.number("slide:3", "page"))
        assertEquals(5 to 2, RecentWork.cell("cell:5:2:tab1"))
        assertEquals(83.5, RecentWork.seconds("time:83.5"))
        // Opened without a place: the place of last time stays (the editor reports the new one).
        RecentWork.touch(c, RecentWork.Entry(RecentWork.SLIDES, "p1", "Vortrag", "slide:4", "Folie 5"))
        RecentWork.touch(c, RecentWork.Entry(RecentWork.DOC, "x", "Anderes"))
        RecentWork.touch(c, RecentWork.Entry(RecentWork.SLIDES, "p1", "Vortrag"))
        assertEquals("slide:4", RecentWork.recent(c).first().at)
        // Written to the device: after a restart the list is there.
        RecentWork.flush(c)
        RecentWork.reset()
        assertEquals(listOf("p1", "x"), RecentWork.recent(c).map { it.ref })
    }

    @Test
    fun slidesTableAndDocumentOpenWhereOneStopped() = runSkikoComposeUiTest(Size(1300f, 900f), Density(1f)) {
        val deck = DocsHandoff.open(c, "Vortrag", SlideDeck.markdown((1..4).map { SlideDeck.Slide("Folie $it", "Text $it") }), kind = DocKind.SLIDES)
        val sheet = DocsHandoff.open(c, "Noten", "| Name | Note |\n| --- | --- |\n| Anna | 2 |\n| Ben | 1 |\n| Cem | 3 |\n", kind = DocKind.SHEET)
        val text = DocsHandoff.open(c, "Antrag", "# Antrag\n\nErster Absatz mit etwas Text.\n\n## Methoden\n\nZweiter Absatz.\n")
        var open by mutableStateOf<String?>(null)
        show { open?.let { id -> androidx.compose.runtime.key(id) { DocPane(c, id, onClose = {}) } } }
        // From the list: the third slide.
        RecentWork.resume(RecentWork.Entry(RecentWork.SLIDES, deck.id, deck.title, "slide:2", "Folie 3"))
        open = deck.id
        settle(14)
        assertEquals(2, io.github.thepytales.llmhub.agent.ActionHosts.slides?.selected())
        assertEquals("slide:2", RecentWork.recent(c).first { it.ref == deck.id }.at)
        assertEquals("Folie 3", RecentWork.recent(c).first { it.ref == deck.id }.place)
        // The table at C… no: row 4, column B (Ben's grade is row 3 from 0).
        RecentWork.resume(RecentWork.Entry(RecentWork.SHEET, sheet.id, sheet.title, "cell:3:1:", "Zelle B4"))
        open = sheet.id
        settle(14)
        val cell = RecentWork.recent(c).first { it.ref == sheet.id }
        assertTrue(cell.at.startsWith("cell:3:1:"), cell.at)
        assertEquals("Zelle B4", cell.place)
        // The document: the cursor in „Zweiter Absatz“ under „Methoden“.
        val restore = "text:0:" + "Antrag\nErster Absatz mit etwas Text.\nMethoden\nZw".length
        RecentWork.resume(RecentWork.Entry(RecentWork.DOC, text.id, text.title, restore))
        open = text.id
        settle(20)
        val place = RecentWork.recent(c).first { it.ref == text.id }
        assertEquals(restore, place.at)
        assertTrue("Methoden" in place.place, place.place)
        // The list on the start page: newest first, with the place; a click asks to open it there.
        open = null
        settle(4)
        val names = RecentWork.recent(c).map { it.title }
        assertEquals(listOf("Antrag", "Noten", "Vortrag"), names.take(3))
    }

    @Test
    fun startPageListOpensTheEditorThere() = runSkikoComposeUiTest(Size(900f, 700f), Density(1f)) {
        val deck = DocsHandoff.open(c, "Vortrag", SlideDeck.markdown((1..3).map { SlideDeck.Slide("Folie $it", "") }), kind = DocKind.SLIDES)
        RecentWork.touch(c, RecentWork.Entry(RecentWork.SLIDES, deck.id, deck.title, "slide:1", "Folie 2"))
        RecentWork.touch(c, RecentWork.Entry(RecentWork.DOC, "weg", "Gelöschtes Dokument"))
        show { ContinueWorkList(c) }
        assertTrue(onAllNodesWithText("WEITERARBEITEN").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Präsentation · Folie 2").fetchSemanticsNodes().isNotEmpty())
        shot("weiterarbeiten")
        // A document that is gone is not offered.
        assertTrue(onAllNodesWithText("Gelöschtes Dokument").fetchSemanticsNodes().isEmpty())
        onNodeWithText("Vortrag").performClick()
        val asked = assertNotNull(RecentWork.openRequest.value)
        RecentWork.openRequest.value = null
        var route: String? = null
        openWork(c, asked, navigate = { route = it })
        assertEquals("doc/${deck.id}", route)
        assertEquals("slide:1", RecentWork.take(RecentWork.SLIDES, deck.id))
    }

    @Test
    fun shortcutsOfTheOpenToolComeFirst() = runSkikoComposeUiTest(Size(1300f, 1100f), Density(1f)) {
        val sheet = DocsHandoff.open(c, "Noten", "| Name | Note |\n| --- | --- |\n| Anna | 2 |\n", kind = DocKind.SHEET)
        var keys by mutableStateOf(false)
        show {
            Box {
                DocPane(c, sheet.id, onClose = {})
                if (keys) ShortcutsDialog(onDismiss = { keys = false })
            }
        }
        keys = true
        settle(4)
        assertTrue(onAllNodesWithText("Tastenkürzel: Tabelle").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("In der Tabelle (wie in Excel)").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Überall in der App").fetchSemanticsNodes().isNotEmpty())
        val excel = onNodeWithText("In der Tabelle (wie in Excel)").fetchSemanticsNode().boundsInRoot.top
        val app = onNodeWithText("Überall in der App").fetchSemanticsNode().boundsInRoot.top
        assertTrue(excel < app, "Werkzeug zuerst")
        shot("tasten-tabelle")
        // Without an editor: only the app's keys and a hint.
        keys = false
        settle(2)
    }

    @Test
    fun printShowsPagesThenPrints() = runSkikoComposeUiTest(Size(1300f, 900f), Density(1f)) {
        val doc = DocsHandoff.open(c, "Antrag", "# Antrag\n\nErster Absatz.\n")
        var preview by mutableStateOf<Printable?>(null)
        show {
            Box {
                DocPane(c, doc.id, onClose = {})
                preview?.let { PrintPreviewDialog(it, onDismiss = { preview = null }) }
            }
        }
        // Strg+P (AppRoot asks PrintRequest): the document says what it prints.
        val p = assertNotNull(PrintRequest.printableOrHint())
        assertEquals("Antrag", p.title)
        preview = p
        var waited = 0
        while (onAllNodesWithText("1 Seite").fetchSemanticsNodes().isEmpty() && waited < 400) { settle(1); waited++ }
        assertTrue(onAllNodesWithText("1 Seite").fetchSemanticsNodes().isNotEmpty(), "Vorschau mit Seitenzahl")
        assertTrue(onAllNodesWithText("Druck-Lotse …").fetchSemanticsNodes().isNotEmpty())
        shot("druckvorschau")
        // „Drucken …“ opens the system's dialog (here: a stand-in that says „Abbrechen“).
        var asked = 0
        io.github.thepytales.llmhub.docs.DocPrint.dialog = { _, pages -> asked = pages.numberOfPages; false }
        onNodeWithText("Drucken …").performClick()
        waited = 0
        while (asked == 0 && waited < 100) { settle(1); waited++ }
        assertEquals(1, asked, "Druckdialog mit einer Seite")
    }
}
