package io.github.thepytales.llmhub

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.ui.DialogPlace
import io.github.thepytales.llmhub.ui.DirProbe
import io.github.thepytales.llmhub.ui.LlmHubTheme
import io.github.thepytales.llmhub.ui.LoraFileBrowser
import io.github.thepytales.llmhub.ui.LoraFileDialog
import io.github.thepytales.llmhub.ui.dialogPlaces
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ordner wählen im Kana-Dateidialog („Ordner freigeben …“, „Ordner an den Chat anhängen“). Windows: a network drive
 * that does not answer (H: at home without VPN) made the dialog wait on the event thread – the app froze and Windows
 * closed it. Here such a drive is simulated: its check hangs for 20 s.
 */
@OptIn(ExperimentalTestApi::class)
class FolderPickTest {
    private val home = File(System.getProperty("user.home"))

    /** The home folder stands for the network drive that does not answer. */
    private fun hangingDrive() {
        DirProbe.check = { f -> if (f.absoluteFile == home.absoluteFile) { Thread.sleep(20_000); true } else f.isDirectory }
    }

    @AfterTest
    fun restore() {
        DirProbe.check = { it.isDirectory }
        LoraFileDialog.appLoaded = true
    }

    @Test
    fun placesDoNotWaitForADriveThatDoesNotAnswer() {
        hangingDrive()
        val t0 = System.nanoTime()
        val places = dialogPlaces(null, LoraFileDialog.Mode.FOLDER).flatMap { it.second }.filterIsInstance<DialogPlace.Folder>()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "Seitenleiste nach $ms ms")
        assertTrue(places.none { it.dir.absoluteFile == home.absoluteFile }, "nicht erreichbarer Ort fehlt")
        assertTrue(places.isNotEmpty(), "die übrigen Orte sind da")
        assertTrue(!DirProbe.isDirectory(home, 200), "Prüfung mit Zeitgrenze")
    }

    @Test
    fun folderDialogPicksAFolder() = runSkikoComposeUiTest(Size(940f, 620f), Density(1f)) {
        mainClock.autoAdvance = false
        hangingDrive()
        val base = Files.createTempDirectory("adeu-folder-pick").toFile()
        val c = AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }))
        val dir = File(base, "Projekte").apply { mkdirs() }
        File(dir, "Protokolle").mkdirs()
        File(dir, "Bericht.pdf").writeText("x")
        var picked: List<File>? = null
        // No remembered places from the real data folder in the test.
        LoraFileDialog.appLoaded = false
        val t0 = System.nanoTime()
        setContent {
            LlmHubTheme(AppSettings()) {
                androidx.compose.material3.Surface {
                    LoraFileBrowser(c, LoraFileDialog.Request(LoraFileDialog.Mode.FOLDER, "Ordner für den Assistenten freigeben", start = dir, purpose = "folder")) { picked = it }
                }
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 3_000, "Dialog nach $ms ms (darf nicht auf das Laufwerk warten)")
        repeat(25) { if (onAllNodesWithText("Protokolle").fetchSemanticsNodes().isEmpty()) { Thread.sleep(100); mainClock.advanceTimeBy(300) } }
        waitForIdle()
        onAllNodesWithText("Protokolle")[0].performClick()
        repeat(3) { Thread.sleep(100); mainClock.advanceTimeBy(300) }
        onAllNodesWithText("Ordner wählen")[0].performClick()
        repeat(4) { Thread.sleep(100); mainClock.advanceTimeBy(300) }
        waitForIdle()
        assertEquals(File(dir, "Protokolle"), picked?.singleOrNull())
    }
}
