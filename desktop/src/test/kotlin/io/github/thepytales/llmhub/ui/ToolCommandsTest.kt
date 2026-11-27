package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.docs.DocKind
import io.github.thepytales.llmhub.video.VideoClip
import io.github.thepytales.llmhub.video.VideoEdit
import io.github.thepytales.llmhub.video.VideoProject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Befehle des Werkzeugs in Strg+K (AP-135 a, „Fertig, wenn“): „Seitenränder“ im Dokument, „Spalte einfügen“ in der
 * Tabelle und „Untertitel“ im Video werden über Strg+K gefunden und ausgeführt – mit dem Code der Menüs.
 */
@OptIn(ExperimentalTestApi::class)
class ToolCommandsTest {
    private val base = Files.createTempDirectory("adeu-ap135a").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() {
        EditorCommands.recording = false
        base.deleteRecursively()
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 8) {
        repeat(rounds) { Thread.sleep(80); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    /** Shows [editor] with Strg+K above it, opened by the test. */
    private fun SkikoComposeUiTest.show(editor: @androidx.compose.runtime.Composable () -> Unit): () -> Unit {
        mainClock.autoAdvance = false
        var palette by mutableStateOf(false)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) {
                    androidx.compose.material3.Surface {
                        Box {
                            editor()
                            if (palette) CommandPalette(c, onNavigate = {}, onOpenChat = {}, onDismiss = { palette = false })
                        }
                    }
                }
            }
        }
        settle(12)
        return { palette = true }
    }

    private fun SkikoComposeUiTest.ask(open: () -> Unit, words: String) {
        open()
        settle(4)
        onNodeWithTag("palette.query").performTextInput(words)
        settle(4)
    }

    @Test
    fun wordsFindMenuEntries() {
        assertTrue("seitenrander" in EditorCommands.synonyms("Seite einrichten …").let(io.github.thepytales.llmhub.data.AppMap::norm))
        assertTrue("spalte einfugen" in EditorCommands.synonyms("Spalte rechts einfügen").let(io.github.thepytales.llmhub.data.AppMap::norm))
        assertEquals("", EditorCommands.synonyms("Etwas ganz anderes"))
    }

    @Test
    fun pageMarginsInTheDocument() = runSkikoComposeUiTest(Size(1300f, 900f), Density(1f)) {
        val doc = DocsHandoff.open(c, "Antrag", "Erster Absatz.\n")
        val open = show { DocPane(c, doc.id, onClose = {}) }
        // Nothing is recorded while Strg+K is closed.
        assertTrue(EditorCommands.current?.all.isNullOrEmpty(), EditorCommands.current?.all.toString())
        ask(open, "Seitenränder")
        val tool = EditorCommands.current!!
        assertEquals("Dokument", tool.area)
        assertTrue(tool.all.any { it.title == "Seite einrichten …" && it.path == "Datei" }, tool.all.joinToString())
        // The menus have many entries: Einfügen › Tabelle …, Format › Fett with its keys …
        assertTrue(tool.all.any { it.title == "Fett" && it.keys == "Strg+B" }, tool.all.joinToString())
        assertTrue(tool.all.size > 60, "${tool.all.size} Einträge")
        assertTrue(onAllNodesWithText("Seite einrichten …").fetchSemanticsNodes().isNotEmpty(), "Treffer in Strg+K")
        // Enter runs the best entry: the dialog *Seite einrichten* opens, Strg+K is closed.
        onNodeWithTag("palette.query").performKeyInput { pressKey(Key.Enter) }
        settle(6)
        assertTrue(onAllNodesWithText("Seitenränder").fetchSemanticsNodes().isNotEmpty(), "Dialog Seite einrichten")
        assertFalse(EditorCommands.recording)
        settle(2)
        assertTrue(EditorCommands.current?.all.isNullOrEmpty(), "nach dem Schließen nichts mehr aufgezeichnet")
    }

    @Test
    fun insertColumnInTheTable() = runSkikoComposeUiTest(Size(1300f, 900f), Density(1f)) {
        val doc = DocsHandoff.open(c, "Noten", "| Name | Note |\n| --- | --- |\n| Anna | 2 |\n| Ben | 1 |\n", kind = DocKind.SHEET)
        val open = show { DocPane(c, doc.id, onClose = {}) }
        ask(open, "Spalte einfügen")
        assertEquals("Tabelle", EditorCommands.current!!.area)
        assertTrue(onAllNodesWithText("Spalte links einfügen").fetchSemanticsNodes().isNotEmpty(), EditorCommands.current!!.all.joinToString())
        onNodeWithText("Spalte links einfügen").performClick()
        settle(8)
        val head = c.docs.get(doc.id)!!.tabs.first().markdown.lines().first { it.startsWith("|") }
        assertEquals(4, head.count { it == '|' }, head)
    }

    private fun ffmpeg(): String? = io.github.thepytales.llmhub.video.Media.ffmpeg()?.takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrNull() == true }

    @Test
    fun subtitlesInTheVideo() {
        val ff = ffmpeg() ?: return
        val clip = File(base, "vortrag.mp4")
        ProcessBuilder(ff, "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=25", "-t", "4", "-pix_fmt", "yuv420p", clip.absolutePath)
            .redirectErrorStream(true).start().apply { inputStream.readBytes(); waitFor(60, TimeUnit.SECONDS) }
        c.appContext.getSharedPreferences("ui_state", 0).edit().putBoolean("videoSetupSeen", true).apply()
        val p = VideoHub.store.save(VideoEdit.withPool(VideoProject(title = "Vorlesung Strg+K", clips = listOf(VideoClip(path = clip.absolutePath, inSec = 0.0, outSec = 4.0)))))
        runSkikoComposeUiTest(Size(1500f, 950f), Density(1f)) {
            val open = show { VideoScreen(c, onBack = {}) }
            onNodeWithText(p.title).performClick()
            settle(16)
            ask(open, "Untertitel")
            assertEquals("Video", EditorCommands.current!!.area)
            assertTrue(onAllNodesWithText("Aus der Transkription …").fetchSemanticsNodes().isNotEmpty(), EditorCommands.current!!.all.joinToString())
            onNodeWithText("Aus der Transkription …").performClick()
            settle(6)
            assertTrue(onAllNodesWithText("Transkript wählen").fetchSemanticsNodes().isNotEmpty(), "Auswahl des Transkripts offen")
        }
    }
}
