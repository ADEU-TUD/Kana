package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.github.thepytales.llmhub.AppContainer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Nichts geht verloren (AP-134): at the start, work that was never saved – so far the working copies of the
 * PDF-Werkzeugkasten; the other editors save while one works – is offered again: with a preview, *Wiederherstellen*
 * (opens it where it was) or *Verwerfen*. *Später* asks again at the next start.
 */
@Composable
fun RecoveryPrompt(c: AppContainer, onOpen: (String) -> Unit) {
    val context = c.appContext
    var items by remember { mutableStateOf<List<PdfJournalEntry>?>(null) }
    var later by remember { mutableStateOf(false) }
    // On the main thread: it reads the open tabs (state of the interface) and a small file.
    LaunchedEffect(Unit) { items = runCatching { PdfWork.recoverable(context) }.getOrDefault(emptyList()) }
    val list = items ?: return
    if (list.isEmpty() || later) return

    fun restore(e: PdfJournalEntry) {
        PdfWork.restore(context, e)
        items = list - e
        onOpen("pdf")
    }

    AlertDialog(
        onDismissRequest = { later = true },
        icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
        title = { Text(tr("Ungespeicherte Arbeit wiederherstellen?")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Beim letzten Mal blieb etwas ungespeichert – etwa weil Kana abgestürzt ist oder geschlossen wurde."))
                list.forEach { e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val preview by produceState<ImageBitmap?>(null, e.file) {
                            value = withContext(Dispatchers.IO) { runCatching { PdfRender.page(File(e.file), 0, 160) }.getOrNull() }
                        }
                        preview?.let { Image(it, contentDescription = tr("Vorschau"), modifier = Modifier.size(width = 60.dp, height = 80.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("PDF-Werkzeugkasten · {0} Änderung(en) nicht gespeichert", e.history.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { restore(e) }) { Text(tr("Wiederherstellen")) }
                                TextButton(onClick = { PdfWork.discard(context, e); items = list - e }) { Text(tr("Verwerfen")) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (list.size > 1) Button(onClick = { list.forEach { PdfWork.restore(context, it) }; items = emptyList(); onOpen("pdf") }) { Text(tr("Alle wiederherstellen")) }
        },
        dismissButton = { TextButton(onClick = { later = true }) { Text(tr("Später")) } },
    )
}
