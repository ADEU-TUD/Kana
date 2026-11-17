package io.github.thepytales.llmhub.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.creative.CanvasOps
import io.github.thepytales.llmhub.creative.DesignEditorState
import io.github.thepytales.llmhub.creative.DesignViewport
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.data.Design
import io.github.thepytales.llmhub.data.DesignElement
import io.github.thepytales.llmhub.data.DesignPage
import io.github.thepytales.llmhub.data.ElementKind
import io.github.thepytales.llmhub.docs.SlideElement
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folien und Gestalten anfassen wie PowerPoint und Canva (AP-127 b): gleiche Abstände rasten ein und zeigen ihr Maß,
 * Strg-Ziehen kopiert, Format übertragen, und das Rechtsklick-Menü in Gestalten (mit echter rechter Maustaste) und auf
 * den Folien. Bilder in build/ap127.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasHandlingTest {
    private val base = Files.createTempDirectory("adeu-ap127").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun box(id: String, x: Float, y: Float = 50f, w: Float = 20f, h: Float = 20f, fill: String = "#00008C") =
        DesignElement(id = id, kind = ElementKind.RECT, x = x, y = y, w = w, h = h, fill = fill)

    private fun state(vararg e: DesignElement) = DesignEditorState(Design(title = "Test", pages = listOf(DesignPage(elements = e.toList()))))

    @Test
    fun equalGapsSnapAndShowTheirSize() {
        // A at 0–10, C at 40–50: B (10 wide) dropped at 22 sits in the middle at 20, both gaps 10 mm.
        val others = listOf(Rect(0f, 0f, 10f, 10f), Rect(40f, 0f, 50f, 10f))
        assertEquals(-2f, CanvasOps.equalGap(Rect(22f, 0f, 32f, 10f), others, horizontal = true, snap = 3f))
        assertNull(CanvasOps.equalGap(Rect(26f, 0f, 36f, 10f), others, horizontal = true, snap = 3f), "zu weit weg")
        val gaps = CanvasOps.gaps(Rect(20f, 0f, 30f, 10f), others, horizontal = true)
        assertEquals(listOf("10 mm", "10 mm"), gaps.map { it.label })
        // A row A, B with gap 8: a third one snaps to the same 8 after B.
        val row = listOf(Rect(0f, 0f, 10f, 10f), Rect(18f, 0f, 28f, 10f))
        assertEquals(-1f, CanvasOps.equalGap(Rect(37f, 0f, 47f, 10f), row, horizontal = true, snap = 3f))
        assertEquals(2, CanvasOps.gaps(Rect(36f, 0f, 46f, 10f), row, horizontal = true).size)
        // Not in the same row: nothing.
        assertNull(CanvasOps.equalGap(Rect(22f, 30f, 32f, 40f), others, horizontal = true, snap = 3f))

        // In the editor: dragging the middle box near the middle shows the two gaps.
        val s = state(box("a", 10f), box("b", 60f), box("c", 120f))
        s.selection = setOf("b")
        s.beginGesture()
        s.dragMove(listOf(s.single!!), Offset(4f, 0f), snap = 5f, free = false)
        assertEquals(65f, s.current.elements.first { it.id == "b" }.x, "Mitte zwischen a und c")
        assertEquals(listOf("35 mm", "35 mm"), s.spacings.map { it.label })
        s.endGesture()
        assertTrue(s.spacings.isEmpty())
    }

    @Test
    fun ctrlDragCopiesAndFormatPainter() {
        val s = state(box("a", 10f), box("b", 60f, fill = "#E6007E").copy(strokeWidth = 2f, stroke = "#000000", radius = 4f))
        s.selection = setOf("a")
        s.beginGesture()
        val copies = s.copiesForDrag()
        s.dragMove(copies, Offset(30f, 30f), snap = 0f, free = true)
        s.endGesture()
        assertEquals(3, s.current.elements.size)
        assertEquals(10f, s.current.elements.first { it.id == "a" }.x, "das Original bleibt")
        assertEquals(40f, s.current.elements.first { it.id == copies[0].id }.x, "die Kopie ist gezogen")
        s.undo()
        assertEquals(2, s.current.elements.size, "ein Schritt Rückgängig")

        s.selection = setOf("b")
        assertTrue(s.copyFormat())
        s.selection = setOf("a")
        assertTrue(s.pasteFormat())
        val a = s.current.elements.first { it.id == "a" }
        assertEquals(listOf("#E6007E", "#000000"), listOf(a.fill, a.stroke))
        assertEquals(4f, a.radius)
        assertEquals(10f, a.x, "Lage bleibt")
    }

    @Test
    fun theSlideMenuWorksOnTheElementsOfASlide() {
        val list = listOf(SlideElement(kind = "text", text = "Titel", x = 2.0, y = 2.0), SlideElement(kind = "form", fill = "#E6007E", color = "#FFFFFF", size = 28, bold = true, x = 10.0))
        val (dup, sel) = slideAction(CanvasAction.DUPLICATE, list, setOf(0))!!
        assertEquals(3, dup.size)
        assertEquals(setOf(2), sel)
        assertEquals(2.5, dup[2].x)
        assertNull(slideAction(CanvasAction.COPY, list, setOf(1)))
        val (pasted, _) = slideAction(CanvasAction.PASTE, list, emptySet())!!
        assertEquals("#E6007E", pasted.last().fill)
        val (cut, none) = slideAction(CanvasAction.CUT, list, setOf(0))!!
        assertEquals(1, cut.size)
        assertTrue(none.isEmpty())
        assertNull(slideAction(CanvasAction.COPY_FORMAT, list, setOf(1)))
        val (formatted, _) = slideAction(CanvasAction.PASTE_FORMAT, list, setOf(0))!!
        assertEquals(listOf("#E6007E", "#FFFFFF"), listOf(formatted[0].fill, formatted[0].color))
        assertEquals("Titel", formatted[0].text)
        val (front, frontSel) = slideAction(CanvasAction.FRONT, list, setOf(0))!!
        assertEquals("Titel", front.last().text)
        assertEquals(setOf(1), frontSel)
    }

    // ── Gestalten with the right mouse button ─────────────────────────────────────────────────────────────

    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 6) {
        repeat(rounds) { Thread.sleep(60); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File("build/ap127").apply { mkdirs() }
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
        val window = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        if (roots > 1) {
            val img = onAllNodes(isRoot())[roots - 1].captureToImage().toAwtImage()
            val b = onAllNodes(isRoot())[roots - 1].fetchSemanticsNode().boundsInRoot
            window.createGraphics().apply { drawImage(img, b.left.toInt(), b.top.toInt(), null); dispose() }
        }
        javax.imageio.ImageIO.write(window, "png", File(dir, "$name.png"))
    }

    private fun SkikoComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun rightClickOpensTheMenuInGestalten() = runSkikoComposeUiTest(Size(1400f, 900f), Density(1f)) {
        mainClock.autoAdvance = false
        val design = c.designs.save(Design(title = "Plakat", pages = listOf(DesignPage(elements = listOf(box("a", 40f, 60f, 60f, 40f), box("b", 120f, 60f, 40f, 40f, fill = "#E6007E"))))))
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { DesignEditor(c, design.id, onClose = {}) } }
            }
        }
        settle(10)
        val canvas = onNodeWithTag("design.canvas")
        val size = canvas.fetchSemanticsNode().size
        val view = Size(size.width.toFloat(), size.height.toFloat())
        val page = Size(design.width, design.height)
        val scale = DesignViewport.fit(view, page)
        val origin = DesignViewport.origin(view, page, 1f, Offset.Zero)
        fun at(xMm: Float, yMm: Float) = origin + Offset(xMm, yMm) * scale

        canvas.performMouseInput { moveTo(at(70f, 80f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        settle()
        assertTrue(has("Format übertragen") && has("In den Vordergrund") && has("Duplizieren"), "Menü am Element")
        shot("1-rechtsklick-element")
        onAllNodesWithText("Duplizieren").onFirst().performClick()
        settle()
        assertTrue(!has("Format übertragen"), "das Menü schließt")

        // Right click on the empty page: Einfügen and Alles auswählen.
        canvas.performMouseInput { moveTo(at(20f, 250f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        settle()
        assertTrue(has("Alles auswählen") && !has("Format übertragen"), "Menü auf leerer Fläche")
        shot("2-rechtsklick-leer")
        onAllNodesWithText("Alles auswählen").onFirst().performClick()
        settle()

        // Ausrichten › opens its own entries.
        canvas.performMouseInput { moveTo(at(140f, 80f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        settle()
        onAllNodesWithText("Ausrichten ›").onFirst().performClick()
        settle()
        assertTrue(has("Waagrecht verteilen") && has("Links ausrichten"))
        shot("3-ausrichten")
        onAllNodesWithText("Links ausrichten").onFirst().performClick()
        settle(12)
        val saved = c.designs.get(design.id)!!.pages[0].elements
        assertEquals(3, saved.size, "Duplizieren ist gespeichert")
        assertNotNull(saved.firstOrNull { it.id == "b" })
    }

    @Test
    fun theSlideStripMovesByDraggingAndHasAMenuAndTheLayoutsAGallery() = runSkikoComposeUiTest(Size(1400f, 900f), Density(1f)) {
        mainClock.autoAdvance = false
        val deck = io.github.thepytales.llmhub.docs.LiveDoc.create(
            "Vortrag",
            io.github.thepytales.llmhub.docs.SlideDeck.markdown(listOf(
                io.github.thepytales.llmhub.docs.SlideDeck.Slide("Titel", layout = io.github.thepytales.llmhub.docs.SlideDeck.Layout.TITLE),
                io.github.thepytales.llmhub.docs.SlideDeck.Slide("Erste"),
                io.github.thepytales.llmhub.docs.SlideDeck.Slide("Zweite"),
                io.github.thepytales.llmhub.docs.SlideDeck.Slide("Dritte"),
            )),
            "Sie", kind = io.github.thepytales.llmhub.docs.DocKind.SLIDES,
        )
        c.docs.save(deck)
        fun titles() = io.github.thepytales.llmhub.docs.SlideDeck.parse(c.docs.get(deck.id)!!.tabs.first().markdown).map { it.title }
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { DocPane(c, deck.id, onClose = {}) } }
            }
        }
        settle(12)
        // The strip: slide numbers 1–4 under the thumbnails; „2“ is the first content slide.
        val two = onAllNodesWithText("2").onFirst()
        val b = two.fetchSemanticsNode().boundsInRoot
        val thumb = Offset(b.left + 60f, b.top - 40f)
        val root = onAllNodes(isRoot())[0]
        root.performMouseInput { moveTo(thumb); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        settle()
        assertTrue(has("Neue Folie danach") && has("Folie duplizieren") && has("Vorführen ab hier"), "Menü der Folie")
        shot("4-folienleiste-menue")
        onAllNodesWithText("Folie duplizieren").onFirst().performClick()
        settle(6)
        assertEquals(listOf("Titel", "Erste", "Erste", "Zweite", "Dritte"), titles())

        // Dragging slide 2 („Erste“) down by two places.
        val step = onAllNodesWithText("3").onFirst().fetchSemanticsNode().boundsInRoot.top - b.top
        root.performMouseInput {
            moveTo(thumb); press(MouseButton.Primary)
            repeat(10) { moveBy(Offset(0f, step * 2.05f / 10)) }
            release(MouseButton.Primary)
        }
        settle(6)
        assertEquals(listOf("Titel", "Erste", "Zweite", "Erste", "Dritte"), titles(), "gezogen")

        // „+ Neue Folie ▾“ shows the layouts as small slides.
        onAllNodesWithText("+ Neue Folie ▾").onFirst().performClick()
        settle()
        shot("5-layout-galerie")
        val layouts = io.github.thepytales.llmhub.docs.SlideDeck.Layout.entries.filter { it != io.github.thepytales.llmhub.docs.SlideDeck.Layout.TITLE }
        assertTrue(layouts.all { has(it.label) }, "alle Layouts")
        // In the menu (the chips under the slide have the same names).
        onAllNodes(androidx.compose.ui.test.hasText(layouts.first().label) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isPopup())).onFirst().performClick()
        settle(6)
        assertEquals(6, titles().size)
    }

    @Test
    fun typingOnTheCanvasCorrectsLikeTheDocument() = runSkikoComposeUiTest(Size(800f, 300f), Density(1f)) {
        mainClock.autoAdvance = false
        c.settings.update { it.copy(docAutocorrectOff = "", docAutocorrectOwn = "mfg\tMit freundlichen Grüßen") }
        var text = ""
        setContent {
            LlmHubTheme(AppSettings()) {
                io.github.thepytales.llmhub.creative.CanvasTextEditor(
                    "", false, androidx.compose.ui.text.TextStyle.Default,
                    androidx.compose.ui.Modifier.size(600.dp, 120.dp).testTag("feld"),
                    onChange = { t, _ -> text = t }, onDone = {},
                )
            }
        }
        settle(3)
        val field = onNodeWithTag("feld").onChildren().onFirst()
        "das ist gut. dann -- mfg ".forEach { ch -> field.performTextInput(ch.toString()); mainClock.advanceTimeBy(20) }
        settle(2)
        assertEquals("Das ist gut. Dann – Mit freundlichen Grüßen ", text)
    }
}
