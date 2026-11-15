package io.github.thepytales.llmhub.docs

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Drucken (AP-126 b): a finished PDF of the document goes to the system's print dialog – on Android the print service
 * (printers, „Als PDF speichern“). The Windows version of this file uses the printers of the PC.
 */
object DocPrint {
    /** Opens the print dialog for [pdf]; false when the device has no print service. */
    suspend fun print(context: Context, pdf: File, title: String): Boolean = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return@withContext false
        manager.print(title, PdfAdapter(pdf, title), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        true
    }

    /** Hands the finished PDF to the print service as it is (the pages are already laid out). */
    private class PdfAdapter(private val pdf: File, private val title: String) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { callback.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(DocumentWriter.safeName(title) + ".pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback.onLayoutFinished(info, old != new)
        }

        override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            try {
                FileInputStream(pdf).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                if (cancel?.isCanceled == true) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
