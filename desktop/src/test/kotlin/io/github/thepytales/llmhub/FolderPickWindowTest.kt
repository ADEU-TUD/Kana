package io.github.thepytales.llmhub

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.desktop.DesktopActions
import io.github.thepytales.llmhub.ui.LlmHubTheme
import io.github.thepytales.llmhub.ui.LoraFileDialog
import io.github.thepytales.llmhub.ui.LoraFileDialogHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ordner freigeben / Ordner an den Chat anhängen: the Kana-Dateidialog opens as its own window over the main window,
 * returns the folder, and the main window stays open. Needs a display (ADEU_GUI_TEST=1, e.g. with `xvfb-run`).
 */
class FolderPickWindowTest {
    @Test
    fun folderDialogWindowReturnsTheFolder() {
        if (System.getenv("ADEU_GUI_TEST") != "1") return
        val base = Files.createTempDirectory("adeu-folder-window").toFile()
        val c = AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }))
        val dir = File(base, "Projekte").apply { mkdirs() }
        val errors = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(errors) { errors += e } }
        var picked: File? = null
        var mainOpen = false
        LoraFileDialog.appLoaded = false
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Kana") {
                    DesktopActions.window = window
                    LlmHubTheme(AppSettings()) {
                        LoraFileDialogHost(c)
                        Text("Hauptfenster")
                    }
                    LaunchedEffect(Unit) {
                        launch {
                            // Waits for the dialog, lets it draw, then answers like a click on „Ordner wählen“.
                            while (LoraFileDialog.request.value == null) delay(50)
                            delay(1500)
                            LoraFileDialog.request.value?.result?.complete(listOf(dir))
                        }
                        picked = withContext(Dispatchers.IO) { DesktopActions.pickFolder("Ordner für den Assistenten freigeben") }
                        delay(800)
                        mainOpen = window.isVisible && window.isDisplayable
                        exitApplication()
                    }
                }
            }
        } finally {
            LoraFileDialog.appLoaded = true
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") { it.stackTraceToString() })
        assertEquals(dir, picked)
        assertTrue(mainOpen, "Hauptfenster offen")
    }
}
