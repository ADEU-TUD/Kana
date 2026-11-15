package io.github.thepytales.llmhub.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.docs.DocFields
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wissenschaft im Dokument (AP-126 c) with real clicks: a picture numbered by its Rechtsklick-Menü, a table caption,
 * numbered headings, a Querverweis, a Lesezeichen and the Abbildungsverzeichnis – and a cross reference that follows the
 * numbers when the document is saved.
 */
@OptIn(ExperimentalTestApi::class)
class DocEditorScienceTest {
    private val base = Files.createTempDirectory("adeu-ap126c-ui").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() { base.deleteRecursively() }

    private fun SkikoComposeUiTest.settle(rounds: Int = 8) {
        repeat(rounds) { Thread.sleep(80); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.open(markdown: String): String {
        mainClock.autoAdvance = false
        val doc = DocsHandoff.open(c, "Hausarbeit", markdown)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { androidx.compose.material3.Surface { DocPane(c, doc.id, onClose = {}) } }
            }
        }
        settle(14)
        return doc.id
    }

    private fun md(id: String) = c.docs.get(id)!!.tabs.first().markdown
    private fun SkikoComposeUiTest.has(text: String, sub: Boolean = false) = onAllNodesWithText(text, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File("build/ap126").apply { mkdirs() }
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
        val window = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        if (roots > 1) {
            val b = onAllNodes(isRoot())[roots - 1].fetchSemanticsNode().boundsInRoot
            val g = window.createGraphics()
            g.drawImage(onAllNodes(isRoot())[roots - 1].captureToImage().toAwtImage(), b.left.toInt(), b.top.toInt(), null)
            g.dispose()
        }
        javax.imageio.ImageIO.write(window, "png", File(dir, "$name.png"))
    }

    private fun picture(): String = File(base, "bild.png").also { f ->
        val bi = java.awt.image.BufferedImage(400, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics(); g.color = java.awt.Color(0, 48, 93); g.fillRect(0, 0, 400, 200); g.dispose()
        javax.imageio.ImageIO.write(bi, "png", f)
    }.absolutePath

    private fun SkikoComposeUiTest.menuItem(menu: String, item: String) {
        onAllNodesWithText(menu).onFirst().performClick()
        settle(3)
        // Long menus (Einfügen, Format) lie partly outside the test window: the entry is clicked by its action.
        onAllNodesWithText(item, substring = true).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle(6)
    }

    @Test
    fun captionsCrossReferenceBookmarkAndLists() = runSkikoComposeUiTest(Size(1400f, 1400f), Density(1f)) {
        val id = open("# Hausarbeit\n\n## Einleitung\n\nWie gezeigt.\n\n![Hörsaal](${picture()}){width=40%}\n\n## Methode\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n")
        // Rechtsklick on the picture › Mit Nummer beschriften: „Abbildung 1:“ before the caption.
        onAllNodesWithContentDescription("Hörsaal").onFirst().performMouseInput { rightClick(center) }
        settle(3)
        onAllNodesWithText("Mit Nummer beschriften").onFirst().performClick()
        settle(8)
        assertTrue("nr=abb-hoersaal" in md(id), md(id))
        assertTrue(has("Abbildung 1:"), "Nummer am Bild")
        // Rechtsklick into the table › Beschriftung: „Tabelle 1“ above it, then its text.
        onAllNodesWithContentDescription("Zelle 1, 1").onFirst().performMouseInput { rightClick(center) }
        settle(3)
        onAllNodesWithText("Beschriftung hinzufügen", substring = true).onFirst().performClick()
        settle(8)
        assertTrue(has("Tabelle 1"), "Beschriftung über der Tabelle")
        onAllNodesWithContentDescription("Beschriftung bearbeiten").onFirst().performClick()
        settle(3)
        onAllNodes(hasSetTextAction() and hasText("Tabelle 1:")).onFirst().performTextInput("Kosten nach Phasen")
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(8)
        assertTrue(Regex("<!-- tabelle: Kosten nach Phasen; nr=tab-[a-z-]+ -->\\n\\n\\| A").containsMatchIn(md(id)), md(id))
        // Format › Überschriften nummerieren.
        menuItem("Format", "Überschriften nummerieren")
        settle(4)
        assertTrue(DocFields.numberedHeadings(md(id)), md(id))
        // A Querverweis at the end of „Wie gezeigt.“
        val field = onAllNodesWithContentDescription("Dokumenttext").onFirst()
        field.performClick()
        settle(2)
        val st = FlowState.shown?.get()!!
        val seg = st.segs.filterIsInstance<FlowSeg.Text>().first()
        seg.selection = androidx.compose.ui.text.TextRange(seg.rich.text.indexOf("Wie gezeigt") + "Wie gezeigt".length)
        st.active = seg
        menuItem("Einfügen", "Querverweis")
        onAllNodesWithText("Abbildung").onFirst().performClick()
        settle(3)
        shot("8-querverweis")
        onAllNodesWithText("Abbildung 1: Hörsaal").onFirst().performClick()
        settle(10)
        assertTrue("Wie gezeigt[Abbildung 1](#abb-hoersaal)." in md(id) || "Wie gezeigt [Abbildung 1](#abb-hoersaal)." in md(id), md(id))
        // Einfügen › Lesezeichen … names the paragraph.
        menuItem("Einfügen", "Lesezeichen")
        onAllNodes(hasSetTextAction() and hasText("Name")).onFirst().performTextInput("Wichtig")
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(8)
        assertTrue(";b=wichtig-->" in md(id), md(id))
        // Einfügen › Abbildungsverzeichnis lists the picture.
        menuItem("Einfügen", "Abbildungsverzeichnis")
        assertTrue("[Abbildungsverzeichnis]" in md(id), md(id))
        assertTrue(onAllNodesWithText("Abbildung 1: Hörsaal").fetchSemanticsNodes().size >= 1, "Eintrag im Verzeichnis")
        shot("9-wissenschaft")
    }

    /** A cross reference whose number is out of date takes the current one when the document is saved. */
    @Test
    fun crossReferenceFollowsTheNumbers() = runSkikoComposeUiTest(Size(1300f, 900f), Density(1f)) {
        val id = open("# Arbeit\n\nSiehe [Abbildung 7](#abb-b) und [dort](#abb-b).\n\n![A](${picture()}){nr=abb-a}\n\n![B](${picture()}){nr=abb-b}\n")
        val field = onAllNodesWithContentDescription("Dokumenttext").onFirst()
        field.performClick()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        field.performTextInput("x")
        settle(12)
        assertTrue("[Abbildung 2](#abb-b)" in md(id) && "[dort](#abb-b)" in md(id), md(id))
    }
}
