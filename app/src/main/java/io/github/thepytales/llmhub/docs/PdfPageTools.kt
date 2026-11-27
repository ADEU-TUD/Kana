package io.github.thepytales.llmhub.docs

import android.content.Context
import io.github.thepytales.llmhub.ui.tr
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PageMode
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seitenarbeit im PDF-Werkzeugkasten (AP-137) with pdfbox-android: watermarks with head and foot, pages from another
 * PDF, bookmarks. The Windows version (same path under desktop/) has the same functions with PDFBox 3; it writes the
 * text in the embedded Noto Sans, Android in Helvetica (letters outside Latin-1 become „?“).
 */
object PdfPageTools {
    // ---- Wasserzeichen, Kopf- und Fußzeile (b) -----------------------------------------------------------------------

    /**
     * Puts [stamps] onto the pages (see [PdfStamp]): text in Helvetica, pictures as they are; transparent where asked.
     * Marked as /Artifact, so screen readers skip it.
     * [name] fills {datei}.
     */
    fun stamp(context: Context?, file: File, stamps: List<PdfStamp>, out: File, name: String = file.name, date: String = PdfStamp.today()) {
        PdfTools.init(context)
        val list = stamps.filter { !it.empty }
        require(list.isNotEmpty()) { tr("Kein Text und kein Bild für das Wasserzeichen.") }
        PdfTools.load(file).use { doc ->
            val fonts = HashMap<Boolean, PDFont>()
            val images = HashMap<String, PDImageXObject?>()
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                val todo = list.filter { it.on(i, total) }
                if (todo.isEmpty()) return@forEachIndexed
                val (w, h) = PdfTools.displayed(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginMarkedContent(COSName.getPDFName("Artifact"))
                    cs.saveGraphicsState()
                    cs.transform(displayMatrix(page))
                    for (s in todo) {
                        cs.saveGraphicsState()
                        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = s.opacity.coerceIn(0.05f, 1f)
                            strokingAlphaConstant = s.opacity.coerceIn(0.05f, 1f)
                        })
                        val img = s.imagePath?.takeIf { it.isNotBlank() }?.let { p ->
                            images.getOrPut(p) {
                                runCatching {
                                    val bytes = File(p).readBytes()
                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
                                    if (p.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) JPEGFactory.createFromImage(doc, bmp) else LosslessFactory.createFromImage(doc, bmp)
                                }.getOrNull()
                            }
                        }
                        if (img != null) drawImage(cs, img, s, w, h)
                        else {
                            val font = fonts.getOrPut(s.bold) { font(doc, s.bold) }
                            drawText(cs, font, s, writable(font, s.resolve(i + 1, total, date, name)), w, h)
                        }
                        cs.restoreGraphicsState()
                    }
                    cs.restoreGraphicsState()
                    cs.endMarkedContent()
                }
            }
            save(doc, out)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun font(doc: PDDocument, bold: Boolean): PDFont = PdfTools.font(bold)

    /** [text] with only characters [font] can write. */
    private fun writable(font: PDFont, text: String): String = buildString {
        text.forEach { ch -> append(if (runCatching { font.getStringWidth(ch.toString()) }.isSuccess) ch else '?') }
    }

    private const val EDGE = 26f

    private fun drawText(cs: PDPageContentStream, font: PDFont, s: PdfStamp, text: String, w: Float, h: Float) {
        if (text.isBlank()) return
        val rgb = floatArrayOf(((s.color shr 16) and 255) / 255f, ((s.color shr 8) and 255) / 255f, (s.color and 255) / 255f)
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
        val unit = font.getStringWidth(text) / 1000f
        cs.beginText()
        when (s.place) {
            PdfStamp.Place.DIAGONAL, PdfStamp.Place.CENTER -> {
                val angle = if (s.place == PdfStamp.Place.DIAGONAL) atan2(h, w) else 0f
                val room = if (s.place == PdfStamp.Place.DIAGONAL) sqrt(w * w + h * h) * 0.72f else w * 0.8f
                val size = if (s.size > 0f) s.size else (room / unit.coerceAtLeast(0.1f)).coerceIn(12f, minOf(w, h) * 0.22f)
                val tw = unit * size
                val cap = size * 0.7f
                // Centre of the page, then along the angle back by half the width and down by half the cap height.
                val x = w / 2 - cos(angle) * tw / 2 + sin(angle) * cap / 2
                val y = h / 2 - sin(angle) * tw / 2 - cos(angle) * cap / 2
                cs.setFont(font, size)
                cs.setTextMatrix(Matrix(cos(angle), sin(angle), -sin(angle), cos(angle), x, y))
            }
            else -> {
                val size = if (s.size > 0f) s.size else 9f
                val tw = unit * size
                val x = when (s.place) {
                    PdfStamp.Place.HEADER_LEFT, PdfStamp.Place.FOOTER_LEFT -> EDGE + 10f
                    PdfStamp.Place.HEADER_RIGHT, PdfStamp.Place.FOOTER_RIGHT -> w - EDGE - 10f - tw
                    else -> (w - tw) / 2
                }
                val y = if (s.place.header) h - EDGE - size * 0.8f else EDGE
                cs.setFont(font, size)
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
            }
        }
        cs.showText(text)
        cs.endText()
    }

    private fun drawImage(cs: PDPageContentStream, img: PDImageXObject, s: PdfStamp, w: Float, h: Float) {
        val ratio = img.width.toFloat() / img.height.coerceAtLeast(1)
        if (!s.place.edge) {
            val iw = if (s.size > 0f) s.size else w * 0.45f
            val ih = iw / ratio
            cs.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih)
        } else {
            val ih = if (s.size > 0f) s.size else 22f
            val iw = ih * ratio
            val x = when (s.place) {
                PdfStamp.Place.HEADER_LEFT, PdfStamp.Place.FOOTER_LEFT -> EDGE + 10f
                PdfStamp.Place.HEADER_RIGHT, PdfStamp.Place.FOOTER_RIGHT -> w - EDGE - 10f - iw
                else -> (w - iw) / 2
            }
            val y = if (s.place.header) h - EDGE - ih else EDGE - ih * 0.25f
            cs.drawImage(img, x, y, iw, ih)
        }
    }

    /** Maps the displayed page (points, origin bottom left, rotation applied) to the PDF's user space. */
    private fun displayMatrix(page: PDPage): Matrix {
        val (w, h) = PdfTools.displayed(page)
        val (ox, oy) = PdfTools.toUser(page, 0f, 1f)
        val (xx, xy) = PdfTools.toUser(page, 1f, 1f)
        val (yx, yy) = PdfTools.toUser(page, 0f, 0f)
        return Matrix((xx - ox) / w, (xy - oy) / w, (yx - ox) / h, (yy - oy) / h, ox, oy)
    }

    // ---- Seiten aus einem anderen PDF (c) ---------------------------------------------------------------------------

    /**
     * [target] with the pages [pages] (0-based, in this order) of [source] inserted before page [at] (0-based; null or
     * past the end: at the end). Comments on those pages come along.
     */
    fun insertPages(context: Context?, target: File, source: File, pages: List<Int>, at: Int?, out: File) {
        PdfTools.init(context)
        require(pages.isNotEmpty()) { tr("Keine Seiten gewählt.") }
        PdfTools.load(source).use { src ->
            PdfTools.load(target).use { doc ->
                val before = at?.takeIf { it in 0 until doc.numberOfPages }?.let { doc.getPage(it) }
                pages.forEach { i ->
                    val p = src.pages.toList().getOrNull(i) ?: return@forEach
                    val imported = doc.importPage(p)
                    if (before != null) {
                        doc.pages.remove(imported)
                        doc.pages.insertBefore(imported, before)
                    }
                }
                save(doc, out)
            }
        }
    }

    // ---- Lesezeichen (d) --------------------------------------------------------------------------------------------

    /** Replaces the bookmarks of [file] by [entries] (levels as in [PdfBookmarks]); an empty list removes them. */
    fun setOutline(context: Context?, file: File, entries: List<PdfOutlineEntry>, out: File) {
        PdfTools.init(context)
        PdfTools.load(file).use { doc ->
            val list = PdfBookmarks.normalized(entries, doc.numberOfPages)
            if (list.isEmpty()) {
                doc.documentCatalog.documentOutline = null
                if (doc.documentCatalog.pageMode == PageMode.USE_OUTLINES) doc.documentCatalog.pageMode = PageMode.USE_NONE
            } else {
                val outline = PDDocumentOutline()
                val parents = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode>()
                parents += outline
                list.forEach { e ->
                    val item = PDOutlineItem()
                    item.title = e.title
                    item.destination = PDPageFitDestination().apply { page = doc.getPage(e.page - 1) }
                    while (parents.size > e.level + 1) parents.removeAt(parents.lastIndex)
                    parents.last().addLast(item)
                    parents += item
                }
                // Open the top level, so a reader shows the chapters.
                outline.children().forEach { it.openNode() }
                outline.openNode()
                doc.documentCatalog.documentOutline = outline
                doc.documentCatalog.pageMode = PageMode.USE_OUTLINES
            }
            save(doc, out)
        }
    }

    /** Saves to a temporary file first, so [out] may be the file that was read. */
    private fun save(doc: PDDocument, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        doc.save(tmp)
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }
}
