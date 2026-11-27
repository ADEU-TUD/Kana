package io.github.thepytales.llmhub.ui

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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.docs.OcrLine
import io.github.thepytales.llmhub.docs.OcrPage
import io.github.thepytales.llmhub.docs.OcrWord
import io.github.thepytales.llmhub.docs.PdfBatch
import io.github.thepytales.llmhub.docs.PdfBatchSteps
import io.github.thepytales.llmhub.docs.PdfBox
import io.github.thepytales.llmhub.docs.PdfOcr
import io.github.thepytales.llmhub.docs.PdfStage3
import io.github.thepytales.llmhub.docs.PdfTools
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AP-137 in the interface: the area *Stapel*, Wasserzeichen und Kopfzeile, pages into another tab, Lesezeichen. */
@OptIn(ExperimentalTestApi::class)
class PdfBatchUiTest {
    private val base = Files.createTempDirectory("kana-ap137-ui").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }
    private val ctx = android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })
    private val c by lazy { AppContainer(ctx) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @AfterTest
    fun cleanUp() {
        PdfWork.tabs.toList().forEach { PdfWork.close(it) }
        PdfWork.batchShown = false
        PdfBatchRun.clear()
        PdfBatchRun.inputs.clear(); PdfBatchRun.roots.clear(); PdfBatchRun.outDir = null
        PdfBatchRun.ocrForTest = null
        base.deleteRecursively()
    }

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
        runCatching { javax.imageio.ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "ap137-$name.png")) }
    }

    private fun SkikoComposeUiTest.waitFor(text: String, substring: Boolean = true, rounds: Int = 300) {
        var n = 0
        while (onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty() && n++ < rounds) settle(1)
    }

    private fun pdf(f: File, vararg pages: String, sizes: List<Float> = emptyList(), scan: Boolean = false): File = f.also {
        it.parentFile.mkdirs()
        PDDocument().use { doc ->
            pages.forEachIndexed { i, line ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    if (scan) cs.drawImage(LosslessFactory.createFromImage(doc, BufferedImage(400, 560, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 400, 560); dispose() } }), 0f, 0f, 595f, 842f)
                    else line.split('\n').forEachIndexed { n, l ->
                        cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), sizes.getOrNull(n) ?: 12f); cs.newLineAtOffset(72f, 760f - n * 40f); cs.showText(l); cs.endText()
                    }
                }
            }
            doc.save(it)
        }
    }

    private fun text(f: File) = Loader.loadPDF(f).use { PDFTextStripper().getText(it) }

    @Test
    fun batchRunsFromTheToolboxWithProgressAndReport() = runSkikoComposeUiTest(Size(1200f, 1000f), Density(1f)) {
        val folder = File(base, "Scans")
        repeat(4) { i -> pdf(File(folder, "Kurs/scan${i + 1}.pdf"), "x", scan = true) }
        pdf(File(folder, "brief.pdf"), "Ein Brief mit Text auf der Seite")
        PdfBatchRun.ocrForTest = PdfBatch.Ocr { _, pages, _, _ ->
            Thread.sleep(150)
            PdfOcr.Result(pages.map { OcrPage(it, listOf(OcrLine(listOf(OcrWord("Anwesenheitsliste", PdfBox(0.1f, 0.1f, 0.4f, 0.03f)))))) }, emptyList(), "Test")
        }
        PdfBatchRun.steps = PdfBatchSteps(ocr = true, pdfA = true)
        show { PdfToolScreen(c, onBack = {}) }
        // Empty toolbox: the Stapel is one click away.
        onNodeWithText("Viele PDFs auf einmal (Stapel)").performClick()
        settle(3)
        assertTrue(PdfWork.batchShown)
        // The folder dialog cannot be clicked in a test: the folder goes in as „Ordner wählen …“ hands it over.
        PdfBatchRun.roots.add(folder)
        PdfBatchRun.inputs.addAll(PdfBatch.collect(listOf(folder)))
        settle(3)
        assertTrue(onAllNodesWithText("5 PDFs", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText(File(base, "Scans (bearbeitet)").absolutePath).fetchSemanticsNodes().isNotEmpty(), "Ziel neben dem Ordner")
        shot("stapel-auswahl")
        onNodeWithTag("pdf.stapel.start").performSemanticsAction(SemanticsActions.OnClick)
        settle(2)
        waitFor("Stapel läuft", rounds = 20)
        shot("stapel-laeuft")
        waitFor("Stapel fertig")
        assertTrue(onAllNodesWithText("Stapel fertig").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("5 PDFs: 5 geändert", substring = true).fetchSemanticsNodes().isNotEmpty(), "Bericht")
        assertTrue(onAllNodesWithText("hat schon Text", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("stapel-bericht")
        val out = File(base, "Scans (bearbeitet)")
        assertTrue(PdfStage3.isPdfA(File(out, "Kurs/scan3.pdf")))
        assertTrue(PdfTools.search(ctx, File(out, "Kurs/scan3.pdf"), "Anwesenheitsliste").isNotEmpty())
        assertTrue(File(out, "Bericht.txt").isFile)
        // The results open in the toolbox.
        onNodeWithText("Im Werkzeugkasten öffnen").performClick()
        var n = 0
        while (PdfWork.tabs.size < 5 && n++ < 100) settle(1)
        assertEquals(5, PdfWork.tabs.size)
        assertTrue(!PdfWork.batchShown)
    }

    @Test
    fun pagesGoIntoTheOtherTabAndWatermarkAndBookmarks() = runSkikoComposeUiTest(Size(1300f, 1000f), Density(1f)) {
        val a = pdf(File(base, "Kapitel.pdf"), "1 Einleitung\nText der Einleitung in normaler Schrift.", "2 Methode\nText der Methode in normaler Schrift.", sizes = listOf(22f, 11f))
        val b = pdf(File(base, "Anhang.pdf"), "Anhang Seite eins")
        runBlocking { PdfWork.open(ctx, a); PdfWork.open(ctx, b) }
        PdfWork.selected = 0
        show { PdfToolScreen(c, onBack = {}) }
        onNodeWithText("Seiten").performClick()
        settle(4)
        // (c) Page 2 of „Kapitel.pdf“ into „Anhang.pdf“, copied.
        onAllNodesWithText("2")[0].performClick()
        settle(2)
        onNodeWithText("In anderes PDF …").performClick()
        settle(3)
        shot("in-anderes-pdf")
        onNodeWithTag("pdf.transfer.go").performClick()
        var n = 0
        while (PdfWork.tabs[1].info?.pages != 2 && n++ < 100) settle(1)
        assertEquals(2, PdfWork.tabs[1].info?.pages)
        assertTrue("Methode" in text(PdfWork.tabs[1].file))
        assertEquals(2, PdfWork.tabs[0].info?.pages, "kopiert, nicht verschoben")
        assertEquals(1, PdfWork.tabs[1].history.size, "Rückgängig im Ziel")
        // (b) Wasserzeichen „Vertraulich“ and „Seite x von y“ below.
        onNodeWithText("Wasserzeichen & Kopfzeile").performClick()
        settle(3)
        onNodeWithText("Vertraulich").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText("Seite x von y unten").performSemanticsAction(SemanticsActions.OnClick)
        settle(3)
        shot("wasserzeichen")
        onNodeWithText("Auf die Seiten setzen").performClick()
        n = 0
        while (PdfWork.tabs[0].history.isEmpty() && n++ < 100) settle(1)
        settle(4)
        val stamped = text(PdfWork.tabs[0].file)
        assertTrue("VERTRAULICH" in stamped.filterNot { it.isWhitespace() } && "Seite 2 von 2" in stamped, stamped)
        // (d) Lesezeichen from the headings.
        onNodeWithText("Lesezeichen").performClick()
        settle(3)
        onNodeWithText("Aus Überschriften erzeugen").performClick()
        n = 0
        while (onAllNodesWithText("2 Methode").fetchSemanticsNodes().isEmpty() && n++ < 100) settle(1)
        shot("lesezeichen")
        onNodeWithText("Übernehmen").performClick()
        n = 0
        while (PdfWork.tabs[0].history.size < 2 && n++ < 100) settle(1)
        val outline = PdfTools.outline(ctx, PdfWork.tabs[0].file)
        assertEquals(listOf("1 Einleitung" to 1, "2 Methode" to 2), outline.map { it.title to it.page }, outline.toString())
        assertNotNull(PdfWork.tabs[0].info)
    }
}
