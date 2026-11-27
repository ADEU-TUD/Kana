package io.github.thepytales.llmhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.thepytales.llmhub.desktop.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Choosing the PDFs of a Stapel (AP-137 a) on this PC: the files themselves, the results next to them. */
object PdfBatchPlatform {
    /** Whether the files are the originals (results next to them, folders to choose). */
    const val PATHS = true
}

@Composable
fun rememberPdfFilesPick(onFiles: (List<File>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onFiles)
    return {
        scope.launch {
            val files = withContext(Dispatchers.IO) { DesktopActions.pickFiles(tr("PDFs für den Stapel wählen"), multiple = true, extensions = listOf("pdf")) }
            if (files.isNotEmpty()) current(files)
        }
    }
}
