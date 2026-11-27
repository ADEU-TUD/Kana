package io.github.thepytales.llmhub.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Choosing the PDFs of a Stapel (AP-137 a). Android has no plain paths: the chosen files are copied into the app and
 * the results go to Kana's folder „Erstellt“. The Windows version of this file hands over the files themselves.
 */
object PdfBatchPlatform {
    /** Whether the files are the originals (results next to them, folders to choose). */
    const val PATHS = false
}

@Composable
fun rememberPdfFilesPick(onFiles: (List<File>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onFiles)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val dir = File(context.cacheDir, "pdf-stapel/eingang-${System.currentTimeMillis()}").apply { mkdirs() }
            val files = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri ->
                    runCatching {
                        var name = "PDF ${i + 1}.pdf"
                        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
                        }
                        val out = File(dir, name.replace('/', '_'))
                        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                        out
                    }.getOrNull()
                }
            }
            current(files)
        }
    }
    return { launcher.launch(arrayOf("application/pdf")) }
}
