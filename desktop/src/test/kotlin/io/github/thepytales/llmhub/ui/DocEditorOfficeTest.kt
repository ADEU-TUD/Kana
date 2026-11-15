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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.LocalSpelling
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AP-126 d–f with real clicks: Absatz-Dialog, Listen-Optionen, Rahmen, Seitenlayout with watermark, Zellen verbinden,
 * Rechtschreibung beim Tippen, das @-Menü with a Baustein, Steuerzeichen and Fokusmodus – pictures in build/ap126.
 */
@OptIn(ExperimentalTestApi::class)
class DocEditorOfficeTest {
    private val base = Files.createTempDirectory("adeu-ap126def-ui").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() {
        FlowSpell.check = { t, l -> LocalSpelling.check(t, l) }
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

    private fun md(id: String) = c.docs.get(id)!!.tabs.first().markdown

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

    private fun SkikoComposeUiTest.menuItem(menu: String, item: String) {
        onAllNodesWithText(menu).onFirst().performClick()
        settle(3)
        onAllNodesWithText(item, substring = true).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle(6)
    }

    /** Puts the cursor into the first text field at [offset] of the text [inText]. */
    private fun SkikoComposeUiTest.cursor(inText: String, plus: Int = 0) {
        onAllNodesWithContentDescription("Dokumenttext").onFirst().performClick()
        settle(2)
        val st = FlowState.shown?.get()!!
        val seg = st.segs.filterIsInstance<FlowSeg.Text>().first { inText in it.rich.text }
        seg.selection = TextRange(seg.rich.text.indexOf(inText) + plus)
        st.active = seg
        settle(2)
    }

    @Test
    fun paragraphListBorderAndPage() = runSkikoComposeUiTest(Size(1400f, 1500f), Density(1f)) {
        val id = open("# Bericht\n\nErster Absatz des Berichts.\n\nZweiter Absatz, der Abstand bekommt.\n\n1. Punkt eins\n2. Punkt zwei\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n")
        // Format › Absatz …: 18 pt vor, Seitenumbruch oberhalb.
        cursor("Zweiter Absatz")
        menuItem("Format", "Absatz …")
        onAllNodes(hasSetTextAction() and hasText("Vor (pt)")).onFirst().performTextClearance()
        onAllNodes(hasSetTextAction() and hasText("Vor (pt)")).onFirst().performTextInput("18")
        onAllNodesWithText("Mit nächstem Absatz zusammenhalten").onFirst().performClick()
        settle(2)
        shot("10-absatz-dialog")
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(10)
        assertTrue(Regex("<!--a:;v=18;k=1-->Zweiter Absatz").containsMatchIn(md(id)), md(id))
        // Format › Listen-Optionen …: a. b. c.
        cursor("Punkt eins")
        menuItem("Format", "Listen-Optionen")
        onAllNodesWithText("a. b. c.").onFirst().performClick()
        settle(2)
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(10)
        assertTrue("1. <!--a:;lf=a-->Punkt eins" in md(id) && "2. <!--a:;lf=a-->Punkt zwei" in md(id), md(id))
        // Format › Rahmen und Schattierung ….
        cursor("Erster Absatz")
        menuItem("Format", "Rahmen und Schattierung")
        onAllNodesWithText("Rahmen um den Absatz").onFirst().performClick()
        onAllNodesWithContentDescription("Farbe #E5F1FF").onFirst().performClick()
        settle(2)
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(10)
        assertTrue("<!--a:;r=1;h=e5f1ff-->Erster Absatz" in md(id), md(id))
        // Format › Seitenlayout …: Wasserzeichen ENTWURF, Seitenfarbe.
        menuItem("Format", "Seitenlayout")
        onAllNodesWithText("ENTWURF").onFirst().performClick()
        onAllNodesWithContentDescription("Farbe #FFF8E1").onFirst().performClick()
        settle(2)
        onAllNodesWithText("Übernehmen").onFirst().performClick()
        settle(10)
        assertTrue(DocMarkup.layout(md(id)).let { it.watermark == "ENTWURF" && it.color == "fff8e1" }, md(id))
        // Zellen verbinden: Rechtsklick in die erste Zelle der zweiten Zeile.
        onAllNodesWithContentDescription("Zelle 2, 1").onFirst().performMouseInput { rightClick(center) }
        settle(3)
        onAllNodesWithText("Mit Zelle rechts verbinden").onFirst().performClick()
        settle(8)
        assertTrue("| 1 2 | << |" in md(id), md(id))
        shot("11-absatz-liste-rahmen-wasserzeichen")
    }

    @Test
    fun spellingAtMenuMarksAndFocus() = runSkikoComposeUiTest(Size(1300f, 1100f), Density(1f)) {
        FlowSpell.check = { text, _ ->
            Regex("Fehlr|Tipfehler").findAll(text).map { LocalSpelling.Error(it.range.first, it.value.length, it.value, listOf("Fehler")) }.toList()
        }
        val id = open("# Notiz\n\nEin Fehlr im Text.\n")
        cursor("im Text", plus = "im Text.".length)
        settle(10)
        val st = FlowState.shown?.get()!!
        val seg = st.segs.filterIsInstance<FlowSeg.Text>().first { "Fehlr" in it.rich.text }
        assertTrue(seg.spell?.second?.size == 1, "Wellenlinie unter dem Fehler: ${seg.spell}")
        shot("12-rechtschreibung-beim-tippen")
        // Das @-Menü: „@bespr“ and Enter put in the Baustein Besprechungsnotiz.
        val field = onAllNodesWithContentDescription("Dokumenttext").onFirst()
        field.performTextInput(" ")
        field.performTextInput("@")
        settle(3)
        assertTrue(st.atMenu != null, "@-Menü offen")
        field.performTextInput("bespr")
        settle(3)
        shot("13-at-menue")
        field.performKeyInput { pressKey(Key.Enter) }
        settle(12)
        assertTrue("\n## Besprechung am" in md(id) && "- [ ] Aufgabe" in md(id) && "@bespr" !in md(id), md(id))
        // Ansicht › Steuerzeichen anzeigen and Fokusmodus.
        menuItem("Ansicht", "Steuerzeichen anzeigen")
        assertTrue(st.showMarks)
        menuItem("Ansicht", "Fokusmodus")
        assertTrue(st.focusMode)
        settle(4)
        shot("14-steuerzeichen-fokus")
    }
}
