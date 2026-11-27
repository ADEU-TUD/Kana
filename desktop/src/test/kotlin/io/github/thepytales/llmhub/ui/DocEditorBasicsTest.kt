package io.github.thepytales.llmhub.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.DocPrint
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Grundausstattung der Dokumente (AP-126 b) with real clicks and keys: *Datei* with Neu, Herunterladen als, Drucken
 * (Strg+P), Sprache and Details; *Bearbeiten* with the clipboard and Löschen; *Einfügen › Seitenzahlen …*; header and
 * footer edited on the sheet by a double click.
 */
@OptIn(ExperimentalTestApi::class)
class DocEditorBasicsTest {
    private val base = Files.createTempDirectory("adeu-ap126b-ui").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() {
        DocPrint.dialog = null
        base.deleteRecursively()
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 8) {
        repeat(rounds) { Thread.sleep(80); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.open(markdown: String): String {
        mainClock.autoAdvance = false
        val doc = DocsHandoff.open(c, "Bericht", markdown)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { DocPane(c, doc.id, onClose = {}) } }
            }
        }
        settle(14)
        return doc.id
    }

    /** Pictures for the comparison page (build/ap126). */
    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File("build/ap126").apply { mkdirs() }
        val roots = onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size
        val img = onAllNodes(androidx.compose.ui.test.isRoot())[roots - 1].captureToImage().toAwtImage()
        // A menu or dialog is a root of its own: draw it over the window.
        if (roots > 1) {
            val window = onAllNodes(androidx.compose.ui.test.isRoot())[0].captureToImage().toAwtImage()
            val b = onAllNodes(androidx.compose.ui.test.isRoot())[roots - 1].fetchSemanticsNode().boundsInRoot
            val g = window.createGraphics()
            g.drawImage(img, b.left.toInt(), b.top.toInt(), null)
            g.dispose()
            javax.imageio.ImageIO.write(window, "png", File(dir, "$name.png"))
        } else javax.imageio.ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    private fun md(id: String) = c.docs.get(id)!!.tabs.first().markdown
    private fun SkikoComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun SkikoComposeUiTest.menu(name: String) {
        onAllNodesWithText(name).onFirst().performClick()
        settle(4)
    }

    @Test
    fun fileMenuDetailsLanguageAndPrinting() = runSkikoComposeUiTest(Size(1400f, 1000f), Density(1f)) {
        val id = open("# Bericht\n\nEin kurzer Satz mit sieben Wörtern hier.\n")
        menu("Datei")
        listOf("Neues Dokument", "Herunterladen als ▸", "Drucken …", "Sprache: Deutsch …", "Details …").forEach { assertTrue(has(it), "Datei: $it") }
        // Herunterladen als opens its formats in place.
        onAllNodesWithText("Herunterladen als ▸").onFirst().performClick()
        settle(3)
        assertTrue(has("Word (.docx)") && has("Markdown"), "Formate")
        shot("1-datei-menue")
        onAllNodesWithText("Details …").onFirst().performClick()
        settle(6)
        assertTrue(has("Wörter") && has("Zuletzt geändert"), "Details")
        // Bericht (1) + 7 words.
        assertTrue(has("8"), "acht Wörter")
        shot("2-details")
        onAllNodesWithText("Schließen").onFirst().performClick()
        settle(4)
        // Sprache: English (GB) – kept in the document.
        menu("Datei")
        onAllNodesWithText("Sprache: Deutsch …").onFirst().performClick()
        settle(4)
        onAllNodesWithText("Englisch (GB)").onFirst().performClick()
        shot("3-sprache")
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(8)
        assertEquals("en-GB", DocMarkup.language(md(id)), md(id))
        // Strg+P: the document goes to the print dialog as PDF pages – since AP-135 (d) after the preview with its
        // pages (Strg+P in the window is the app's key, here the document's own one on a keyboard without the window).
        var pages = -1
        DocPrint.dialog = { _, p -> pages = p.numberOfPages; false }
        val field = onAllNodesWithContentDescription("Dokumenttext").onFirst()
        field.performClick()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
        repeat(160) { if (onAllNodesWithText("1 Seite").fetchSemanticsNodes().isEmpty()) { Thread.sleep(100); mainClock.advanceTimeBy(200) } }
        settle(2)
        onAllNodesWithText("Drucken …").onFirst().performClick()
        repeat(60) { if (pages < 0) { Thread.sleep(250); mainClock.advanceTimeBy(200) } }
        settle(4)
        assertEquals(1, pages, "eine Seite an den Drucker")
    }

    @Test
    fun editMenuPageNumbersAndHeaderOnTheSheet() = runSkikoComposeUiTest(Size(1400f, 1000f), Density(1f)) {
        val id = open("# Bericht\n\nErster Absatz bleibt. Weg damit.\n")
        // Bearbeiten › Löschen removes the marked text.
        val field = onAllNodesWithContentDescription("Dokumenttext").onFirst()
        field.performClick()
        settle(2)
        // To the end, then three words back with Umschalt: „Weg damit.“ is marked.
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        repeat(3) { field.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } } } }
        settle(2)
        val st = FlowState.shown?.get()!!
        val before = "${st.active?.selection} ${st.spanAnchor}"
        menu("Bearbeiten")
        val after = "${st.active?.selection} ${st.spanAnchor}"
        listOf("Ausschneiden", "Kopieren", "Einfügen", "Nur Text einfügen", "Löschen").forEach { assertTrue(has(it), "Bearbeiten: $it") }
        shot("4-bearbeiten-menue")
        onAllNodes(hasText("Löschen") and hasText("Entf")).onFirst().performClick()
        settle(8)
        assertTrue("Erster Absatz bleibt." in md(id) && "Weg damit" !in md(id), md(id) + " vorher $before nachher $after jetzt ${st.active?.selection}")
        // Einfügen › Seitenzahlen …: in the middle of the header, only the number.
        menu("Einfügen")
        onAllNodesWithText("Seitenzahlen …").onFirst().performClick()
        settle(4)
        assertTrue(has("Seitenzahlen anzeigen"), "Dialog")
        onAllNodes(hasText("Mitte") and hasClickAction()).onFirst().performClick()
        onAllNodes(hasText("1") and hasClickAction()).onFirst().performClick()
        settle(2)
        shot("5-seitenzahlen")
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(10)
        assertTrue("<!-- seitenzahlen: oben mitte, zahl -->" in md(id), md(id) + " | state: " + FlowState.shown?.get()?.markdown() + " | dialog offen: " + has("Seitenzahlen anzeigen"))
        // A double click into the header of the sheet: type, Enter – the header of the document.
        val text = onAllNodesWithContentDescription("Dokumenttext").onFirst().getBoundsInRoot()
        onRoot().performMouseInput { doubleClick(Offset(text.left.value + 40f, text.top.value - 38f)) }
        settle(6)
        val header = onAllNodesWithContentDescription("Kopfzeile").onFirst()
        header.performTextInput("Projektbericht {seite}")
        settle(2)
        shot("6-kopfzeile-bearbeiten")
        header.performKeyInput { pressKey(Key.Enter) }
        settle(10)
        assertEquals("Projektbericht {seite}", DocMarkup.pageSetup(md(id)).header, md(id))
        assertFalse(has("Enter übernimmt"), "Feld wieder zu")
        shot("7-blatt-mit-kopfzeile-und-seitenzahl")
    }
}
