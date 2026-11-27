package io.github.thepytales.llmhub.docs

import android.content.Context
import io.github.thepytales.llmhub.ui.tr
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a PDF-Stapel (AP-137 a) does with every file, in this order: Texterkennung for scanned pages, Schwärzen of the
 * [redactTerms] (the redacted pages stay searchable), [stamps] (Wasserzeichen, Kopf- und Fußzeile), Verkleinern, PDF/A –
 * and with [mergePerFolder] the results of each folder become one PDF (PDF/A then for that one).
 */
@Serializable
data class PdfBatchSteps(
    val ocr: Boolean = false,
    /** null: the Texterkennung of this device; a model id: a vision model (only after the person agreed). */
    val ocrModel: String? = null,
    val redactTerms: List<String> = emptyList(),
    val stamps: List<PdfStamp> = emptyList(),
    val shrink: Boolean = false,
    val pdfA: Boolean = false,
    val mergePerFolder: Boolean = false,
) {
    val redact: Boolean get() = redactTerms.any { it.isNotBlank() }
    val any: Boolean get() = ocr || redact || stamps.isNotEmpty() || shrink || pdfA || mergePerFolder

    /** The steps as words, for the plan, the progress and the report. */
    fun labels(): List<String> = listOfNotNull(
        tr("Texterkennung").takeIf { ocr },
        tr("Schwärzen nach Liste ({0} Begriffe)", redactTerms.count { it.isNotBlank() }).takeIf { redact },
        tr("Wasserzeichen und Kopfzeile").takeIf { stamps.isNotEmpty() },
        tr("Verkleinern").takeIf { shrink },
        tr("Zusammenführen je Ordner").takeIf { mergePerFolder },
        "PDF/A".takeIf { pdfA },
    )
}

/** One file of a Stapel and what became of it. */
@Serializable
data class PdfBatchItem(
    val source: String,
    /** Path below the chosen folder („Kurs A/scan01.pdf“), or the file name. */
    val relative: String,
    val status: String = PENDING,
    val out: String? = null,
    val notes: List<String> = emptyList(),
    val sizeBefore: Long = 0,
    val sizeAfter: Long = 0,
) {
    companion object {
        const val PENDING = "pending"
        const val CHANGED = "changed"
        const val UNCHANGED = "unchanged"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

/** The end of a Stapel: every file with its notes, the merged PDFs and where everything is. */
@Serializable
data class PdfBatchReport(
    val items: List<PdfBatchItem>,
    val steps: PdfBatchSteps,
    val outDir: String,
    val merged: List<PdfBatchItem> = emptyList(),
    val cancelled: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = System.currentTimeMillis(),
) {
    fun count(status: String) = items.count { it.status == status }

    /** „48 geändert · 2 übersprungen“ */
    fun summary(): String = listOfNotNull(
        tr("{0} geändert", count(PdfBatchItem.CHANGED)).takeIf { count(PdfBatchItem.CHANGED) > 0 },
        tr("{0} unverändert", count(PdfBatchItem.UNCHANGED)).takeIf { count(PdfBatchItem.UNCHANGED) > 0 },
        tr("{0} übersprungen", count(PdfBatchItem.SKIPPED)).takeIf { count(PdfBatchItem.SKIPPED) > 0 },
        tr("{0} mit Fehler", count(PdfBatchItem.FAILED)).takeIf { count(PdfBatchItem.FAILED) > 0 },
        tr("{0} nicht bearbeitet (abgebrochen)", count(PdfBatchItem.CANCELLED)).takeIf { count(PdfBatchItem.CANCELLED) > 0 },
        tr("{0} zusammengeführt", merged.size).takeIf { merged.isNotEmpty() },
    ).joinToString(" · ")

    /** The report as plain text („Bericht.txt“ next to the results). */
    fun text(): String = buildString {
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
        appendLine(tr("PDF-Stapel vom {0}", f.format(Date(startedAt))) + if (cancelled) " – " + tr("abgebrochen") else "")
        appendLine(tr("Schritte") + ": " + steps.labels().joinToString(", "))
        appendLine(tr("Ergebnisse in") + ": " + outDir)
        appendLine("${items.size} " + tr("PDFs") + ": " + summary())
        val dur = ((endedAt - startedAt) / 1000).coerceAtLeast(0)
        appendLine(tr("Dauer") + ": " + if (dur >= 60) "${dur / 60} min ${dur % 60} s" else "$dur s")
        fun section(title: String, list: List<PdfBatchItem>) {
            if (list.isEmpty()) return
            appendLine()
            appendLine(title)
            list.forEach { appendLine("- " + it.relative + if (it.notes.isNotEmpty()) ": " + it.notes.joinToString(" · ") else "") }
        }
        section(tr("Zusammengeführt"), merged)
        section(tr("Geändert"), items.filter { it.status == PdfBatchItem.CHANGED })
        section(tr("Unverändert"), items.filter { it.status == PdfBatchItem.UNCHANGED })
        section(tr("Übersprungen"), items.filter { it.status == PdfBatchItem.SKIPPED })
        section(tr("Fehler"), items.filter { it.status == PdfBatchItem.FAILED })
        section(tr("Nicht bearbeitet (abgebrochen)"), items.filter { it.status == PdfBatchItem.CANCELLED })
    }
}

/** Where a Stapel stands: file [done] of [total], the one in work and its step. */
data class PdfBatchProgress(val done: Int, val total: Int, val current: String = "", val step: String = "")

/**
 * PDF-Stapel (AP-137 a): many PDFs or a folder (with subfolders) in one run, each with the same [PdfBatchSteps]. The
 * originals stay untouched; the results go into [run]'s outDir with the same subfolders, a „Bericht.txt“ beside them.
 * The same function serves the area *Stapel* of the werkzeugkasten and Kana's action „pdf.batch“ (also as a step of a
 * Kana-Ablauf).
 */
object PdfBatch {
    /** Recognizes pages [pages] (0-based) of a PDF; [work] is a folder for page pictures. */
    fun interface Ocr {
        suspend fun recognize(file: File, pages: List<Int>, work: File, progress: (Int, Int) -> Unit): PdfOcr.Result
    }

    /** Thrown from inside a step when the person pressed *Abbrechen*. */
    class Cancelled : RuntimeException("abgebrochen")

    /** The PDFs in [roots] (files, or folders with all their subfolders) with their path below the folder, sorted. */
    fun collect(roots: List<File>): List<Pair<File, String>> = roots.flatMap { r ->
        if (r.isDirectory) r.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .map { it to it.relativeTo(r).invariantSeparatorsPath }
            .sortedWith(compareBy(NaturalOrder) { it.second }).toList()
        else listOf(r to r.name)
    }.distinctBy { it.first.absolutePath }

    /** „Scans (bearbeitet)“ next to the folder; for single files a folder „bearbeitet“ next to the first one. */
    fun defaultOut(roots: List<File>): File? {
        val first = roots.firstOrNull() ?: return null
        val base = if (first.isDirectory) File(first.parentFile ?: first, first.name + " " + tr("(bearbeitet)")) else File(first.parentFile ?: File("."), tr("bearbeitet"))
        return base
    }

    /**
     * Runs [steps] on [inputs] (from [collect]); results go to [outDir] (never onto an original). [progress] hears about
     * every file and step, [cancelled] is asked between the steps (and between pages of the Texterkennung).
     */
    suspend fun run(
        context: Context?,
        inputs: List<Pair<File, String>>,
        steps: PdfBatchSteps,
        outDir: File,
        ocr: Ocr?,
        progress: (PdfBatchProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): PdfBatchReport {
        val started = System.currentTimeMillis()
        outDir.mkdirs()
        val work = File(outDir, ".kana-stapel-${System.nanoTime()}").apply { mkdirs() }
        val items = inputs.map { (f, rel) -> PdfBatchItem(f.absolutePath, rel, sizeBefore = f.length()) }.toMutableList()
        var stopped = false
        try {
            for ((i, input) in inputs.withIndex()) {
                if (cancelled()) { stopped = true; break }
                val (file, rel) = input
                progress(PdfBatchProgress(i, inputs.size, rel, tr("Prüfen")))
                items[i] = try {
                    one(context, file, rel, steps, outDir, File(work, "$i").apply { mkdirs() }, ocr, cancelled) { step -> progress(PdfBatchProgress(i, inputs.size, rel, step)) }
                } catch (e: Cancelled) {
                    stopped = true
                    items[i].copy(status = PdfBatchItem.CANCELLED)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    items[i].copy(status = PdfBatchItem.FAILED, notes = listOf(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName))
                } finally {
                    File(work, "$i").deleteRecursively()
                }
                if (stopped) break
            }
            if (stopped) for (k in items.indices) if (items[k].status == PdfBatchItem.PENDING) items[k] = items[k].copy(status = PdfBatchItem.CANCELLED)
            val merged = if (steps.mergePerFolder && !stopped) merge(context, items, steps, outDir, work, progress) else emptyList()
            val report = PdfBatchReport(items.toList(), steps, outDir.absolutePath, merged, stopped, started, System.currentTimeMillis())
            runCatching { File(outDir, tr("Bericht") + ".txt").writeText(report.text()) }
            progress(PdfBatchProgress(inputs.size, inputs.size, "", tr("Fertig")))
            return report
        } finally {
            work.deleteRecursively()
        }
    }

    /** One file through all steps; returns what became of it. */
    private suspend fun one(
        context: Context?, file: File, rel: String, steps: PdfBatchSteps, outDir: File, work: File, ocr: Ocr?,
        cancelled: () -> Boolean, step: (String) -> Unit,
    ): PdfBatchItem {
        val item = PdfBatchItem(file.absolutePath, rel, sizeBefore = file.length())
        if (!file.isFile) return item.copy(status = PdfBatchItem.SKIPPED, notes = listOf(tr("Datei nicht gefunden")))
        if (!file.extension.equals("pdf", true)) return item.copy(status = PdfBatchItem.SKIPPED, notes = listOf(tr("kein PDF")))
        val target = File(outDir, rel)
        if (target.canonicalPath == file.canonicalPath) return item.copy(status = PdfBatchItem.SKIPPED, notes = listOf(tr("das Ergebnis würde das Original überschreiben – einen anderen Zielordner wählen")))
        if (file.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) return item.copy(status = PdfBatchItem.SKIPPED, notes = listOf(tr("liegt im Zielordner")))
        val locked = item.copy(status = PdfBatchItem.SKIPPED, notes = listOf(tr("mit Passwort geschützt – erst im Werkzeugkasten entsperren")))
        val pages = runCatching { PdfTools.info(context, file).pages }.getOrElse { e ->
            return if (e.javaClass.simpleName.contains("Password")) locked else item.copy(status = PdfBatchItem.FAILED, notes = listOf(tr("lässt sich nicht lesen")))
        }
        // Readable, but with rights of its owner (no changes): left as it is.
        if (runCatching { PdfStage3.encrypted(file) }.getOrDefault(false)) return locked
        if (pages == 0) return item.copy(status = PdfBatchItem.SKIPPED, notes = listOf(tr("hat keine Seiten")))
        val notes = mutableListOf<String>()
        var changed = false
        var cur = File(work, "0.pdf").also { file.copyTo(it, overwrite = true) }
        var n = 0
        fun next() = File(work, "${++n}.pdf")
        fun check() { if (cancelled()) throw Cancelled() }

        // 1. Texterkennung: only pages without text.
        if (steps.ocr) {
            check()
            step(tr("Texterkennung"))
            val texts = PdfExtras.pageTexts(context, cur)
            val scanned = PdfOcr.scanned(texts)
            if (scanned.isEmpty()) notes += tr("hat schon Text")
            else if (ocr == null) notes += tr("Texterkennung nicht möglich: auf diesem Gerät gibt es keine")
            else {
                val r = ocr.recognize(cur, scanned, File(work, "ocr").apply { mkdirs() }) { _, _ -> check() }
                val found = r.pages.filter { it.lines.isNotEmpty() }
                if (found.isEmpty()) notes += tr("kein Text erkannt")
                else {
                    val out = next()
                    PdfExtras.addTextLayer(context, cur, found, out)
                    cur = out
                    changed = true
                    notes += (if (found.size == 1) tr("Text auf 1 Seite erkannt") else tr("Text auf {0} Seiten erkannt", found.size)) + " (${r.engine})"
                }
                if (r.failed.isNotEmpty()) notes += tr("nicht erkannt: Seite {0}", r.failed.joinToString(", ") { "${it + 1}" })
            }
        }
        // 2. Schwärzen nach Liste; the words around the black boxes stay searchable.
        if (steps.redact) {
            check()
            step(tr("Schwärzen"))
            if (!PdfStage3.SUPPORTED) notes += tr("Schwärzen gibt es nur am PC")
            else {
                val terms = steps.redactTerms.map { it.trim() }.filter { it.isNotEmpty() }
                val hits = PdfStage3.find(cur, terms)
                if (hits.isEmpty()) notes += tr("Begriffe nicht gefunden")
                else {
                    val out = next()
                    redactKeepingText(context, cur, hits, terms, out)
                    cur = out
                    changed = true
                    notes += tr("{0} Stellen geschwärzt (Seite {1})", hits.size, hits.map { it.page + 1 }.distinct().joinToString(", "))
                }
            }
        }
        // 3. Wasserzeichen, Kopf- und Fußzeile.
        if (steps.stamps.isNotEmpty()) {
            check()
            step(tr("Wasserzeichen"))
            val out = next()
            PdfPageTools.stamp(context, cur, steps.stamps, out, name = file.nameWithoutExtension)
            cur = out
            changed = true
            notes += steps.stamps.joinToString(", ") { s -> "„" + (s.text.ifBlank { File(s.imagePath.orEmpty()).name }) + "“" + if (s.place.edge) " (" + tr(s.place.label) + ")" else "" }
        }
        // 4. Verkleinern: only kept when it saves something.
        if (steps.shrink) {
            check()
            step(tr("Verkleinern"))
            if (!PdfStage3.SHRINK) notes += tr("Verkleinern gibt es hier nicht")
            else {
                val out = next()
                val (before, after) = PdfStage3.shrink(cur, out)
                if (after in 1 until (before * 0.97).toLong()) {
                    cur = out
                    changed = true
                    notes += size(before) + " → " + size(after)
                } else {
                    out.delete()
                    notes += tr("war schon klein")
                }
            }
        }
        // 5. PDF/A (with merging: for the merged file instead).
        if (steps.pdfA && !steps.mergePerFolder) {
            check()
            step("PDF/A")
            if (!PdfStage3.SUPPORTED) notes += tr("PDF/A gibt es nur am PC")
            else if (!changed && runCatching { PdfStage3.isPdfA(cur) }.getOrDefault(false)) notes += tr("ist schon PDF/A")
            else {
                val out = next()
                val problems = PdfStage3.toPdfA(cur, file.nameWithoutExtension, out)
                cur = out
                changed = true
                notes += "PDF/A-2b" + if (problems.isNotEmpty()) " – " + problems.joinToString(" ") { tr(it) } else ""
            }
        }
        check()
        target.parentFile?.mkdirs()
        cur.copyTo(target, overwrite = true)
        return item.copy(
            status = if (changed) PdfBatchItem.CHANGED else PdfBatchItem.UNCHANGED,
            out = target.absolutePath, notes = notes, sizeAfter = target.length(),
        )
    }

    /** Results of each folder (by their place below the chosen folder) into one PDF named after the folder. */
    private fun merge(context: Context?, items: List<PdfBatchItem>, steps: PdfBatchSteps, outDir: File, work: File, progress: (PdfBatchProgress) -> Unit): List<PdfBatchItem> {
        val done = items.filter { it.out != null && (it.status == PdfBatchItem.CHANGED || it.status == PdfBatchItem.UNCHANGED) }
        val groups = done.groupBy { it.relative.substringBeforeLast('/', "") }.toSortedMap(NaturalOrder)
        return groups.map { (folder, list) ->
            val name = folder.substringAfterLast('/').ifBlank { outDir.name.removeSuffix(" " + tr("(bearbeitet)")).ifBlank { tr("Zusammengeführt") } }
            val rel = (if (folder.contains('/')) folder.substringBeforeLast('/') + "/" else "") + name + ".pdf"
            progress(PdfBatchProgress(items.size, items.size, rel, tr("Zusammenführen")))
            val target = File(outDir, if (folder.isEmpty()) "$name (" + tr("zusammengeführt") + ").pdf" else rel)
            val notes = mutableListOf(tr("{0} PDFs zusammengeführt", list.size))
            runCatching {
                val files = list.sortedWith(compareBy(NaturalOrder) { it.relative }).map { File(it.out!!) }
                val tmp = File(work, "merge-${System.nanoTime()}.pdf")
                PdfTools.merge(context, files, tmp)
                if (steps.pdfA && PdfStage3.SUPPORTED) {
                    val problems = PdfStage3.toPdfA(tmp, name, File(work, "a.pdf"))
                    File(work, "a.pdf").renameTo(tmp).let { ok -> if (!ok) File(work, "a.pdf").copyTo(tmp, overwrite = true) }
                    notes += "PDF/A-2b" + if (problems.isNotEmpty()) " – " + problems.joinToString(" ") { tr(it) } else ""
                }
                target.parentFile?.mkdirs()
                tmp.copyTo(target, overwrite = true)
                // The single results of that folder are in the merged file; they do not stay beside it.
                files.forEach { it.delete() }
                PdfBatchItem(target.absolutePath, target.relativeTo(outDir).invariantSeparatorsPath, PdfBatchItem.CHANGED, target.absolutePath, notes, list.sumOf { it.sizeBefore }, target.length())
            }.getOrElse { e -> PdfBatchItem(target.absolutePath, rel, PdfBatchItem.FAILED, null, listOf(e.message ?: e.javaClass.simpleName)) }
        }.also {
            // Empty subfolders the single results left behind.
            outDir.walkBottomUp().filter { it.isDirectory && it != outDir && !it.name.startsWith(".") && it.listFiles().isNullOrEmpty() }.forEach { it.delete() }
        }
    }

    /**
     * Schwärzen like [PdfStage3.redact] (the pages with hits become pictures with the boxes burnt in), then the words of
     * those pages that are not under a box go back as invisible text – the page stays searchable, the blacked-out words
     * are gone. Words that contain one of [terms] stay out as well.
     */
    fun redactKeepingText(context: Context?, file: File, hits: List<PdfRedaction>, terms: List<String>, out: File) {
        val pages = hits.map { it.page }.toSet()
        val words = runCatching { PdfTools.words(context, file) }.getOrDefault(emptyList()).filter { it.page in pages }
        val lowTerms = terms.map { it.lowercase() }.filter { it.length >= 2 }
        val keep = words.filter { w ->
            val t = w.text.lowercase()
            lowTerms.none { t.contains(it) || (it.contains(' ') && it.split(' ').any { part -> part.length >= 2 && t.contains(part) }) } &&
                hits.none { h -> h.page == w.page && overlaps(h.box, w.box) }
        }
        val redacted = File(out.parentFile, out.name + ".geschwaerzt.pdf")
        PdfStage3.redact(file, hits, redacted)
        if (keep.isEmpty()) {
            if (!redacted.renameTo(out)) { redacted.copyTo(out, overwrite = true); redacted.delete() }
            return
        }
        val layer = keep.groupBy { it.page }.map { (p, ws) -> OcrPage(p, listOf(OcrLine(ws.map { OcrWord(it.text, it.box) }))) }
        PdfExtras.addTextLayer(context, redacted, layer, out)
        redacted.delete()
    }

    private fun overlaps(a: PdfBox, b: PdfBox): Boolean = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    fun size(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** „scan2“ before „scan10“. */
    object NaturalOrder : Comparator<String> {
        private val parts = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val pa = parts.findAll(a.lowercase()).map { it.value }.toList()
            val pb = parts.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())).takeIf { it != 0 } ?: x.compareTo(y) else x.compareTo(y)
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
