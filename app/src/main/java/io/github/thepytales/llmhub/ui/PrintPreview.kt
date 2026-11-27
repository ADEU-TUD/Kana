package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.docs.DocPrint
import io.github.thepytales.llmhub.docs.PrintPilot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Strg+P überall (AP-135 d, Analyse Q-12): every editor says what it prints – a title and the PDF of what is open –,
 * Strg+P shows it as pages first (Vorschau), then *Drucken …* (the system's dialog: printer, copies, pages, duplex) or
 * the *Druck-Lotse* (AP-97: check before printing, booklet, several pages per sheet, handout, duplex, print shop).
 */
class Printable(val title: String, val make: suspend (android.content.Context) -> File)

/** Registers what Strg+P prints in this editor (call inside [ToolCommandsArea]); [make] runs off the main thread. */
@Composable
fun PrintableHere(title: String, make: suspend (android.content.Context) -> File) {
    val tool = LocalToolCommands.current ?: return
    val latest = rememberUpdatedState(make)
    SideEffect { tool.printable = Printable(title) { ctx -> latest.value(ctx) } }
    DisposableEffect(tool) { onDispose { tool.printable = null } }
}

/** Strg+P where an editor is open: the preview of [p]; elsewhere a hint where printing works. */
object PrintRequest {
    /** Strg+P was pressed (AppShortcuts → AppRoot): the open editor's [Printable] or a hint. */
    fun printableOrHint(): Printable? = EditorCommands.current?.printable.also {
        if (it == null) Notices.info(
            if (EditorCommands.current != null) tr("Hier gibt es nichts zu drucken.")
            else tr("Strg+P druckt in Dokument, Tabelle, Folien, Gestalten, PDF und Transkript."),
        )
    }
}

/** Druckvorschau (AP-135 d): the pages of the PDF, then *Drucken …* or *Druck-Lotse …*. */
@Composable
fun PrintPreviewDialog(p: Printable, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdf by produceState<Result<File>?>(null, p) {
        value = withContext(Dispatchers.IO) { runCatching { p.make(context) } }
    }
    val pages by produceState<Pair<Int, List<ImageBitmap>>?>(null, pdf) {
        val f = pdf?.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val n = PdfRender.pages(f)
            n to (0 until minOf(n, 6)).mapNotNull { PdfRender.page(f, it, 220) }
        }
    }
    var printing by remember { mutableStateOf(false) }
    var pilot by remember { mutableStateOf(false) }
    val file = pdf?.getOrNull()
    if (pilot && file != null) {
        PrintDialog(file, onDismiss = { pilot = false }, onDone = { pilot = false; onDismiss() })
        return
    }
    AlertDialog(
        onDismissRequest = { if (!printing) onDismiss() },
        title = { Text(tr("Drucken: „{0}“", p.title)) },
        text = {
            Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    pdf == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(tr("Wird für den Druck vorbereitet …"))
                    }
                    pdf?.isFailure == true -> Text(tr("Drucken nicht möglich: {0}", pdf?.exceptionOrNull()?.message ?: ""), color = MaterialTheme.colorScheme.error)
                    else -> {
                        val shown = pages
                        if (shown == null) CircularProgressIndicator(Modifier.size(20.dp)) else {
                            Text(tr(if (shown.first == 1) "1 Seite" else "{0} Seiten", shown.first), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("print.preview"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                shown.second.forEach { img ->
                                    Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                        Image(img, contentDescription = null, modifier = Modifier.height(260.dp))
                                    }
                                }
                                if (shown.first > shown.second.size) Text(tr("… und {0} weitere", shown.first - shown.second.size), Modifier.align(Alignment.CenterVertically))
                            }
                        }
                        if (PrintPilot.AVAILABLE) HintText(tr("Broschüre, mehrere Seiten pro Blatt, Handzettel oder Druckerei: über den Druck-Lotsen. Er prüft auch vorher, ob Schriften fehlen oder Bilder zu grob sind."))
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = file != null && !printing, onClick = {
                val f = file ?: return@Button
                printing = true
                scope.launch {
                    val r = runCatching { DocPrint.print(context, f, p.title) }
                    printing = false
                    r.onFailure { Notices.error(tr("Drucken nicht möglich: {0}", it.message ?: it.javaClass.simpleName)) }
                        .onSuccess { if (it) onDismiss() }
                }
            }) { Text(tr("Drucken …")) }
        },
        dismissButton = {
            Row {
                if (PrintPilot.AVAILABLE) TextButton(enabled = file != null && !printing, onClick = { pilot = true }) { Text(tr("Druck-Lotse …")) }
                TextButton(enabled = !printing, onClick = onDismiss) { Text(tr("Schließen")) }
            }
        },
    )
}
