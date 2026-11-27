package io.github.thepytales.llmhub

import android.content.Context
import io.github.thepytales.llmhub.docs.OcrLine
import io.github.thepytales.llmhub.docs.OcrPage
import io.github.thepytales.llmhub.docs.OcrWord
import io.github.thepytales.llmhub.docs.PdfBatch
import io.github.thepytales.llmhub.docs.PdfBatchItem
import io.github.thepytales.llmhub.docs.PdfBatchSteps
import io.github.thepytales.llmhub.docs.PdfBookmarks
import io.github.thepytales.llmhub.docs.PdfBox
import io.github.thepytales.llmhub.docs.PdfOcr
import io.github.thepytales.llmhub.docs.PdfOutlineEntry
import io.github.thepytales.llmhub.docs.PdfPageTools
import io.github.thepytales.llmhub.docs.PdfStage3
import io.github.thepytales.llmhub.docs.PdfStamp
import io.github.thepytales.llmhub.docs.PdfTools
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PageMode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PDF im Stapel und Seitenarbeit (AP-137): Stapel, Wasserzeichen und Kopfzeile, Seiten zwischen PDFs, Lesezeichen. */
class PdfBatchTest {
    private val dir: File = Files.createTempDirectory("kana-ap137").toFile()
    private val context = Context(File(dir, "files").apply { mkdirs() }, File(dir, "cache").apply { mkdirs() })

    @AfterTest
    fun cleanUp() { dir.deleteRecursively() }

    // ── Test files ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** A scanned page as the scanner delivers it: large, fine grain, JPEG of high quality. */
    private val scanJpeg: ByteArray by lazy {
        val img = BufferedImage(1754, 2480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
        val r = Random(7)
        for (y in 0 until img.height step 3) for (x in 0 until img.width step 3) {
            val v = 236 + r.nextInt(20)
            g.color = Color(v, v, v); g.fillRect(x, y, 3, 3)
        }
        g.color = Color.BLACK; g.font = Font(Font.SERIF, Font.PLAIN, 64)
        g.drawString("Protokoll der Sitzung", 200, 400)
        g.dispose()
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        writer.output = ImageIO.createImageOutputStream(out)
        writer.write(null, IIOImage(img, null, null), writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.97f })
        writer.dispose()
        out.toByteArray()
    }

    /** A scan without text; its number stands in the title (the stand-in for the Texterkennung reads it there). */
    private fun scan(f: File, number: Int): File = f.also {
        it.parentFile.mkdirs()
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs -> cs.drawImage(JPEGFactory.createFromByteArray(doc, scanJpeg), 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
            doc.documentInformation.title = "Scan $number"
            doc.save(it)
        }
    }

    /** A PDF with real text: one line per entry, one page per list. */
    private fun textPdf(f: File, vararg pages: List<String>, sizes: List<Float>? = null, rotate: Int = 0): File = f.also {
        it.parentFile.mkdirs()
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            pages.forEach { lines ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 760f
                    lines.forEachIndexed { n, line ->
                        val size = sizes?.getOrNull(n) ?: 12f
                        cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(72f, y); cs.showText(line); cs.endText()
                        y -= size * 2f
                    }
                }
            }
            if (rotate != 0) doc.pages.last().rotation = rotate
            doc.save(it)
        }
    }

    private fun text(f: File, page: Int? = null) = Loader.loadPDF(f).use { d -> PDFTextStripper().apply { if (page != null) { startPage = page; endPage = page } }.getText(d) }

    /** Stands in for the Texterkennung: „Protokoll Nummer n“ in the middle of every page asked for. */
    private val fakeOcr = PdfBatch.Ocr { file, pages, _, progress ->
        val n = Loader.loadPDF(file).use { it.documentInformation.title.orEmpty().removePrefix("Scan ") }
        val done = pages.map { p ->
            progress(pages.indexOf(p) + 1, pages.size)
            OcrPage(p, listOf(OcrLine(listOf(OcrWord("Protokoll", PdfBox(0.1f, 0.15f, 0.25f, 0.03f)), OcrWord("Nummer", PdfBox(0.37f, 0.15f, 0.2f, 0.03f)), OcrWord(n, PdfBox(0.6f, 0.15f, 0.06f, 0.03f))))))
        }
        PdfOcr.Result(done, emptyList(), "Test-Texterkennung")
    }

    // ── (a) Stapel ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun fiftyScansInOneRunSearchableSmallerAndPdfA() = runBlocking {
        // „Fertig, wenn“: a folder with 50 scans (in two subfolders) in one run searchable, smaller and PDF/A.
        val folder = File(dir, "Scans")
        repeat(50) { i -> scan(File(folder, (if (i < 25) "Kurs A" else "Kurs B") + "/scan${i + 1}.pdf"), i + 1) }
        val inputs = PdfBatch.collect(listOf(folder))
        assertEquals(50, inputs.size)
        // „scan2“ before „scan10“.
        assertEquals(listOf("Kurs A/scan1.pdf", "Kurs A/scan2.pdf", "Kurs A/scan3.pdf"), inputs.take(3).map { it.second })
        val out = assertNotNull(PdfBatch.defaultOut(listOf(folder)))
        assertEquals("Scans (bearbeitet)", out.name)
        val seen = mutableListOf<String>()
        val report = PdfBatch.run(context, inputs, PdfBatchSteps(ocr = true, shrink = true, pdfA = true), out, fakeOcr, progress = { p -> if (p.step.isNotEmpty()) seen += p.step })
        assertEquals(50, report.count(PdfBatchItem.CHANGED), report.text())
        assertFalse(report.cancelled)
        assertTrue(listOf("Texterkennung", "Verkleinern", "PDF/A").all { it in seen }, seen.distinct().toString())
        report.items.forEach { item ->
            val f = File(assertNotNull(item.out))
            assertTrue(f.isFile && f.absolutePath.startsWith(out.absolutePath), item.toString())
            assertEquals(item.relative, f.relativeTo(out).invariantSeparatorsPath)
            val n = item.relative.substringAfter("scan").substringBefore(".pdf")
            assertTrue(PdfTools.search(context, f, "Protokoll Nummer $n").isNotEmpty(), "durchsuchbar: ${item.relative}")
            assertTrue(f.length() < File(item.source).length() * 0.8, "kleiner: ${item.relative} ${File(item.source).length()} → ${f.length()}")
            assertTrue(PdfStage3.isPdfA(f), "PDF/A: ${item.relative}")
            // The invisible text of the Texterkennung needs no embedded font: no PDF/A problem left.
            assertTrue(item.notes.none { "Schriften" in it }, item.notes.toString())
            assertTrue(item.notes.any { "Text auf 1 Seite erkannt" in it } && item.notes.any { "→" in it } && item.notes.any { it.startsWith("PDF/A-2b") }, item.notes.toString())
        }
        // The originals stay as they were; the report lies beside the results.
        assertTrue(PdfTools.search(context, File(folder, "Kurs A/scan1.pdf"), "Protokoll").isEmpty())
        assertFalse(PdfStage3.isPdfA(File(folder, "Kurs A/scan1.pdf")))
        val bericht = File(out, "Bericht.txt").readText()
        assertTrue("50 PDFs: 50 geändert" in bericht && "Kurs B/scan50.pdf" in bericht, bericht)
        // No working files are left over.
        assertTrue(out.listFiles()!!.none { it.name.startsWith(".kana-stapel") })
    }

    @Test
    fun cancelStopsAndReportsWhatWasNotDone() = runBlocking {
        val folder = File(dir, "Abbruch")
        repeat(8) { i -> scan(File(folder, "s${i + 1}.pdf"), i + 1) }
        val out = File(dir, "Abbruch-Ergebnis")
        var files = 0
        val report = PdfBatch.run(
            context, PdfBatch.collect(listOf(folder)), PdfBatchSteps(ocr = true, shrink = true), out, fakeOcr,
            progress = { p -> if (p.step == "Prüfen") files++ }, cancelled = { files >= 3 },
        )
        assertTrue(report.cancelled)
        // The third file was stopped inside its steps; nothing half-done stays.
        assertEquals(2, report.count(PdfBatchItem.CHANGED), report.text())
        assertEquals(6, report.count(PdfBatchItem.CANCELLED))
        assertEquals(2, out.listFiles()!!.count { it.extension == "pdf" })
        assertTrue("abgebrochen" in File(out, "Bericht.txt").readText())
    }

    @Test
    fun leavesOutWhatItCannotDoAndSaysWhy() = runBlocking {
        val folder = File(dir, "Gemischt").apply { mkdirs() }
        val withText = textPdf(File(folder, "brief.pdf"), listOf("Sehr geehrte Damen und Herren, dies ist ein Brief mit Text."))
        val locked = textPdf(File(folder, "gesperrt.pdf"), listOf("Geheim"))
        Loader.loadPDF(locked).use { d -> d.protect(StandardProtectionPolicy("owner", "1234", AccessPermission()).apply { encryptionKeyLength = 128 }); d.save(locked) }
        val broken = File(folder, "kaputt.pdf").apply { writeText("kein PDF, nur Text") }
        val note = File(folder, "notiz.txt").apply { writeText("Notiz") }
        val out = File(dir, "Gemischt-Ergebnis")
        val inputs = PdfBatch.collect(listOf(withText, locked, broken, note))
        val report = PdfBatch.run(context, inputs, PdfBatchSteps(ocr = true), out, fakeOcr)
        fun item(name: String) = report.items.single { it.relative == name }
        assertEquals(PdfBatchItem.UNCHANGED, item("brief.pdf").status)
        assertEquals(listOf("hat schon Text"), item("brief.pdf").notes)
        assertEquals(PdfBatchItem.SKIPPED, item("gesperrt.pdf").status)
        assertTrue(item("gesperrt.pdf").notes.single().startsWith("mit Passwort geschützt"))
        assertEquals(PdfBatchItem.FAILED, item("kaputt.pdf").status)
        assertEquals(PdfBatchItem.SKIPPED, item("notiz.txt").status)
        assertEquals(listOf("kein PDF"), item("notiz.txt").notes)
        val bericht = report.text()
        assertTrue("Übersprungen" in bericht && "Fehler" in bericht && "Unverändert" in bericht, bericht)
        // An output folder that is the folder of the originals would overwrite them: refused per file.
        val same = PdfBatch.run(context, PdfBatch.collect(listOf(folder)).filter { it.second == "brief.pdf" }, PdfBatchSteps(shrink = true), folder, null)
        assertEquals(PdfBatchItem.SKIPPED, same.items.single().status)
        assertTrue(withText.readBytes().decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun redactByListKeepsTheOtherWordsSearchable() = runBlocking {
        val folder = File(dir, "Gutachten")
        textPdf(File(folder, "g1.pdf"), listOf("Gutachten zur Arbeit von Max Mustermann", "Matrikelnummer 4711123, Dresden", "Die Arbeit ist gut gegliedert."))
        textPdf(File(folder, "g2.pdf"), listOf("Zweitgutachten ohne Namen", "Sorgfältig zitiert."))
        val out = File(dir, "Gutachten-Ergebnis")
        val report = PdfBatch.run(context, PdfBatch.collect(listOf(folder)), PdfBatchSteps(redactTerms = listOf("Max Mustermann", "4711123")), out, null)
        val g1 = report.items.single { it.relative == "g1.pdf" }
        assertEquals(PdfBatchItem.CHANGED, g1.status, g1.notes.toString())
        assertTrue(g1.notes.single().startsWith("2 Stellen geschwärzt"), g1.notes.toString())
        val t = text(File(g1.out!!))
        assertFalse("Mustermann" in t || "4711123" in t || "Max" in t, t)
        assertTrue("Gutachten" in t && "gegliedert" in t && "Dresden" in t, "die übrigen Wörter bleiben durchsuchbar: $t")
        val g2 = report.items.single { it.relative == "g2.pdf" }
        assertEquals(PdfBatchItem.UNCHANGED, g2.status)
        assertEquals(listOf("Begriffe nicht gefunden"), g2.notes)
    }

    @Test
    fun mergePerFolderMakesOnePdfPerSubfolder() = runBlocking {
        val folder = File(dir, "Abgaben")
        textPdf(File(folder, "Gruppe 1/teil2.pdf"), listOf("Gruppe 1 Teil 2"))
        textPdf(File(folder, "Gruppe 1/teil10.pdf"), listOf("Gruppe 1 Teil 10"))
        textPdf(File(folder, "Gruppe 2/a.pdf"), listOf("Gruppe 2 A"), listOf("Gruppe 2 A Seite 2"))
        textPdf(File(folder, "deckblatt.pdf"), listOf("Deckblatt"))
        val out = File(dir, "Abgaben (bearbeitet)")
        val report = PdfBatch.run(context, PdfBatch.collect(listOf(folder)), PdfBatchSteps(mergePerFolder = true, pdfA = true), out, null)
        assertEquals(3, report.merged.size, report.text())
        val g1 = File(out, "Gruppe 1.pdf")
        assertEquals(2, PdfTools.info(context, g1).pages)
        // In natural order: Teil 2 before Teil 10.
        assertTrue(text(g1).indexOf("Teil 2") < text(g1).indexOf("Teil 10"))
        assertEquals(2, PdfTools.info(context, File(out, "Gruppe 2.pdf")).pages)
        assertTrue(File(out, "Abgaben (zusammengeführt).pdf").isFile)
        assertTrue(report.merged.all { PdfStage3.isPdfA(File(it.out!!)) })
        // The single results are in the merged files; their folders are gone.
        assertFalse(File(out, "Gruppe 1").exists())
        assertFalse(File(out, "deckblatt.pdf").exists())
    }

    // ── (b) Wasserzeichen, Kopf- und Fußzeile ───────────────────────────────────────────────────────────────────────

    @Test
    fun watermarkHeaderAndFooterWithPlaceholders() {
        val f = textPdf(File(dir, "bericht.pdf"), listOf("Erste Seite"), listOf("Zweite Seite"), listOf("Dritte Seite quer"), rotate = 90)
        val out = File(dir, "bericht-stempel.pdf")
        PdfPageTools.stamp(
            context, f,
            listOf(
                PdfStamp.ENTWURF,
                PdfStamp("Seite {n} von {N}", PdfStamp.Place.FOOTER_CENTER, opacity = 1f, pages = "2-"),
                PdfStamp("{datei} · {datum}", PdfStamp.Place.HEADER_RIGHT, opacity = 1f),
                PdfStamp("Vertraulich – Größe ß", PdfStamp.Place.HEADER_LEFT, opacity = 1f),
            ),
            out, name = "Bericht", date = "27.11.2026",
        )
        val p1 = text(out, 1)
        // The diagonal letters come out of the text in pieces („EN TW URF“); together they are the word.
        assertTrue("ENTWURF" in p1.filterNot { it.isWhitespace() } && "Bericht · 27.11.2026" in p1 && "Vertraulich – Größe ß" in p1, p1)
        assertFalse("Seite 1 von 3" in p1, "nur ab Seite 2")
        assertTrue("Seite 2 von 3" in text(out, 2))
        // The turned page gets it as well, readable in its direction.
        assertTrue("Seite 3 von 3" in text(out, 3), text(out, 3))
        Loader.loadPDF(out).use { d ->
            val content = d.getPage(0).contents.readAllBytes().decodeToString()
            assertTrue("/Artifact" in content, "als Artefakt markiert")
            val fonts = d.getPage(0).resources.fontNames.map { d.getPage(0).resources.getFont(it) }
            assertTrue(fonts.any { it.isEmbedded && it.name.contains("NotoSans") }, fonts.map { it.name }.toString())
        }
        // The original Helvetica of the page is the only font PDF/A still complains about.
        val problems = PdfStage3.toPdfA(out, "Bericht", File(dir, "bericht-a.pdf"))
        assertTrue(problems.all { "Helvetica" in it && "NotoSans" !in it }, problems.toString())
        assertEquals(PdfStamp.VERTRAULICH, PdfStamp.fromWish("Vertraulich"))
        assertEquals(PdfStamp.Place.FOOTER_CENTER, PdfStamp.fromWish("Seite x von y").place)
        assertEquals("Seite 4 von 9 · heute.pdf", PdfStamp("Seite {n} von {N} · {datei}").resolve(4, 9, file = "heute.pdf"))
    }

    // ── (c) Seiten aus einem anderen PDF ────────────────────────────────────────────────────────────────────────────

    @Test
    fun pagesGoIntoAnotherPdfAtThePlaceAsked() {
        val a = textPdf(File(dir, "a.pdf"), listOf("Seite A1"), listOf("Seite A2"), listOf("Seite A3"))
        val b = textPdf(File(dir, "b.pdf"), listOf("Seite B1"), listOf("Seite B2"))
        val out = File(dir, "b2.pdf")
        PdfPageTools.insertPages(context, b, a, listOf(0, 2), 1, out)
        assertEquals(listOf("B1", "A1", "A3", "B2"), (1..4).map { text(out, it).trim().removePrefix("Seite ") })
        PdfPageTools.insertPages(context, out, a, listOf(1), null, out)
        assertEquals("A2", text(out, 5).trim().removePrefix("Seite "))
        // The source stays as it was.
        assertEquals(3, PdfTools.info(context, a).pages)
    }

    // ── (d) Lesezeichen ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun bookmarksAreWrittenNestedAndComeFromHeadings() {
        val f = textPdf(
            File(dir, "arbeit.pdf"),
            listOf("1 Einleitung", "Hier beginnt der Text der Arbeit mit einem längeren Absatz.", "1.1 Hintergrund", "Noch mehr Text im Absatz darunter."),
            listOf("2 Methode", "Der Text zur Methode steht hier in normaler Größe."),
            sizes = listOf(20f, 11f, 14f, 11f),
        )
        val found = PdfTools.textRuns(context, f).let { (runs, sizes) -> PdfBookmarks.fromHeadings(runs, sizes) }
        assertEquals(listOf("1 Einleitung" to 0, "1.1 Hintergrund" to 1, "2 Methode" to 0), found.map { it.title to it.level }, found.toString())
        assertEquals(listOf(1, 1, 2), found.map { it.page })
        // A watermark across the pages does not join the headings it crosses.
        val stamped = File(dir, "arbeit-entwurf.pdf")
        PdfPageTools.stamp(context, f, listOf(PdfStamp.VERTRAULICH, PdfStamp.PAGE_OF), stamped)
        val again = PdfTools.textRuns(context, stamped).let { (runs, sizes) -> PdfBookmarks.fromHeadings(runs, sizes) }
        assertEquals(found.map { it.title }, again.map { it.title }, again.toString())
        val out = File(dir, "arbeit-lz.pdf")
        PdfPageTools.setOutline(context, f, found, out)
        assertEquals(found, PdfTools.outline(context, out))
        Loader.loadPDF(out).use { assertEquals(PageMode.USE_OUTLINES, it.documentCatalog.pageMode) }
        // Editing as a list: nest, move with the children, take out.
        var list = listOf(PdfOutlineEntry("A", 1, 0), PdfOutlineEntry("B", 1, 0), PdfOutlineEntry("B1", 2, 1), PdfOutlineEntry("C", 2, 0))
        assertEquals(list, PdfBookmarks.indent(list, 2), "schon so tief wie erlaubt")
        list = PdfBookmarks.move(list, 1, up = true)
        assertEquals(listOf("B", "B1", "A", "C"), list.map { it.title })
        list = PdfBookmarks.indent(list, 2)
        assertEquals(listOf(0, 1, 1, 0), list.map { it.level })
        list = PdfBookmarks.move(list, 0, up = false)
        assertEquals(listOf("C", "B", "B1", "A"), list.map { it.title })
        list = PdfBookmarks.delete(list, 1)
        assertEquals(listOf("C" to 0, "B1" to 0, "A" to 0), list.map { it.title to it.level })
        // An empty list removes the bookmarks.
        PdfPageTools.setOutline(context, out, emptyList(), out)
        assertTrue(PdfTools.outline(context, out).isEmpty())
    }
}
