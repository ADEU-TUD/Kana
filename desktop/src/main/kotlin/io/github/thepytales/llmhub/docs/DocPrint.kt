package io.github.thepytales.llmhub.docs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.printing.PDFPageable
import java.awt.print.PrinterJob
import java.io.File

/**
 * Drucken (AP-126 b, Windows/macOS/Linux): the finished PDF of the document goes to the system's print dialog (printer,
 * copies, pages, duplex) and from there to the printer – the pages look exactly like the PDF export.
 */
object DocPrint {
    /** For tests: stands in for the system's print dialog (true: print). */
    @Volatile
    internal var dialog: ((PrinterJob, java.awt.print.Pageable) -> Boolean)? = null

    /** Shows the print dialog for [pdf] and prints; false when it was cancelled. Fails when there is no printer. */
    suspend fun print(context: Context, pdf: File, title: String): Boolean = withContext(Dispatchers.IO) {
        Loader.loadPDF(pdf).use { doc ->
            val job = PrinterJob.getPrinterJob()
            job.jobName = title
            val pages = PDFPageable(doc)
            job.setPageable(pages)
            val ok = dialog?.invoke(job, pages) ?: job.printDialog()
            if (ok) job.print()
            ok
        }
    }
}
