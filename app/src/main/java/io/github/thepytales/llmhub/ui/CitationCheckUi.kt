package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.docs.DocCitations
import io.github.thepytales.llmhub.research.CitationCheck
import io.github.thepytales.llmhub.research.CitationStyle

/** The works of a document's reference list: those the app inserted (marker), otherwise read from the text. */
fun citationCheckOf(c: AppContainer, markdown: String): CitationCheck.Report {
    val cited = DocCitations.cited(markdown)
    val refs = cited.ids.mapNotNull { c.literature.get(it) }
    if (refs.isEmpty() || refs.size < cited.ids.size) return CitationCheck.check(markdown)
    val choice = cited.choice
    val numbered = choice?.csl?.numeric ?: (choice?.style == CitationStyle.IEEE)
    // The marker lists the works in the order of the list – the numbers of numbered styles refer to it.
    return CitationCheck.check(markdown, refs, numbered)
}

/** Dokument › Zitate prüfen (AP-138 b): sources without entry, entries never cited, quotations without page. */
@Composable
fun CitationCheckDialog(c: AppContainer, markdown: String, onDismiss: () -> Unit) {
    val report = remember(markdown) { citationCheckOf(c, markdown) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Zitate prüfen")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("{0} Belege im Text, {1} Einträge im Literaturverzeichnis, {2} wörtliche Zitate.", report.citations.size, report.entries, report.quotes))
                if (report.ok) {
                    StatusPill(tr("Alles passt zusammen"), PillTone.SUCCESS)
                    if (report.entries == 0) HintText(tr("Kein Literaturverzeichnis gefunden – eine Überschrift „Literatur“ oder „Literaturverzeichnis“ mit den Einträgen darunter, oder Belege über „Literatur einfügen“."))
                }
                CitationCheck.Kind.entries.forEach { kind ->
                    val list = report.findings.filter { it.kind == kind }
                    if (list.isEmpty()) return@forEach
                    Text(tr(kind.label) + " (${list.size})", style = MaterialTheme.typography.titleSmall,
                        color = if (kind == CitationCheck.Kind.NOT_CITED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    list.take(60).forEach { f ->
                        Column {
                            Text(f.text, style = MaterialTheme.typography.bodyMedium)
                            if (f.context.isNotBlank() && f.context != f.text) HintText(f.context)
                        }
                    }
                }
                HintText(tr("Die Prüfung liest Belege wie „(Müller, 2020, S. 12)“, „Müller (2020)“ und „[3]“. Wörtliche Zitate ab vier Wörtern brauchen eine Seite."))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(report.text())); Notices.info(tr("Kopiert")) }) { Text(tr("Bericht kopieren")) } },
    )
}

/** Literatur › Sammlung: „Volltext suchen“ / „Volltext öffnen“ for one work (AP-138 c). */
@Composable
fun FullTextRow(c: AppContainer, r: io.github.thepytales.llmhub.research.Reference) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember(r.id) { androidx.compose.runtime.mutableStateOf(false) }
    val file = r.fullTextPath.takeIf { it.isNotBlank() }?.let { java.io.File(c.drive.root, it) }?.takeIf { it.isFile }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (file != null) {
            StatusPill(tr("Volltext im Kana-Ordner"), PillTone.SUCCESS)
            TextButton(onClick = { openDocument(context, file) }) { Text(tr("Volltext öffnen")) }
        } else if (r.doi.isNotBlank() || r.title.length >= 8) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { fetchFullTexts(c, listOf(r)); busy = false }
            }) { Text(tr("Volltext suchen")) }
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** Looks for free full texts of [refs], stores them and tells the user what was found. */
suspend fun fetchFullTexts(c: AppContainer, refs: List<io.github.thepytales.llmhub.research.Reference>, onProgress: (Int) -> Unit = {}): Int {
    val sources = c.researchTools.sources(null)
    var stored = 0
    var pages = 0
    refs.forEachIndexed { i, r ->
        onProgress(i)
        val out = runCatching { io.github.thepytales.llmhub.research.FullTexts.fetch(sources, c.llm.http, r, c.drive.root) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (refs.size == 1) Notices.error(e.message ?: tr("Suche nicht möglich"))
            return@forEachIndexed
        }
        when (out) {
            is io.github.thepytales.llmhub.research.FullTexts.Outcome.Stored -> {
                stored++
                c.literature.update(r.id) { it.copy(fullTextPath = out.relative, openAccessUrl = it.openAccessUrl.ifBlank { out.from.pdfUrl }) }
                if (refs.size == 1) Notices.info(tr("Volltext gespeichert: Dateien › {0} ({1})", out.relative, io.github.thepytales.llmhub.research.FullTexts.describe(out.from)))
            }
            is io.github.thepytales.llmhub.research.FullTexts.Outcome.PageOnly -> {
                pages++
                c.literature.update(r.id) { it.copy(openAccessUrl = it.openAccessUrl.ifBlank { out.url }) }
                if (refs.size == 1) Notices.info(tr("Frei lesbar, aber kein PDF zum Herunterladen – „Volltext“ öffnet die Seite."))
            }
            io.github.thepytales.llmhub.research.FullTexts.Outcome.NotFound -> if (refs.size == 1) Notices.info(tr("Keine freie Fassung gefunden – über die SLUB ist das Werk oft mit dem TU-Login zugänglich."))
        }
    }
    if (refs.size > 1) Notices.info(tr("{0} von {1} Volltexten gespeichert, {2} nur als Seite frei lesbar.", stored, refs.size, pages))
    return stored
}

/**
 * Literatur › Sammlung › „Literatur-Wächter einrichten“ (AP-138 e): a stored search that runs once a week as a
 * scheduled task and puts new works into a list – only when the user sets it up, without notification.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LiteratureWatchDialog(c: AppContainer, list: String?, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = c.settings.current
    var query by remember { androidx.compose.runtime.mutableStateOf(list?.substringAfter(": ").orEmpty()) }
    var weekday by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var hour by remember { androidx.compose.runtime.mutableIntStateOf(7) }
    val sources = remember {
        androidx.compose.runtime.mutableStateListOf<io.github.thepytales.llmhub.research.LitSource>().apply {
            addAll(settings.literatureSources.split(',').mapNotNull { io.github.thepytales.llmhub.research.LitSource.parse(it) }
                .filter { it != io.github.thepytales.llmhub.research.LitSource.SLUB }.ifEmpty { listOf(io.github.thepytales.llmhub.research.LitSource.OPENALEX) })
        }
    }
    val days = listOf(1 to tr("Mo"), 2 to tr("Di"), 3 to tr("Mi"), 4 to tr("Do"), 5 to tr("Fr"), 6 to tr("Sa"), 7 to tr("So"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Literatur-Wächter")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("Sucht einmal pro Woche nach neuen Werken zu einer gespeicherten Suche und legt sie als Liste in die Sammlung. Kana meldet sich dabei nicht – das Ergebnis wartet in der Liste und unter Geplante Aufgaben."))
                OutlinedTextField(query, { query = it }, label = { Text(tr("Suche")) }, placeholder = { Text(tr("z.B. Klassenführung Berufseinstieg")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Datenbanken"), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    io.github.thepytales.llmhub.research.LitSource.entries.filter { it != io.github.thepytales.llmhub.research.LitSource.SLUB }.forEach { src ->
                        FilterChip(src in sources, { if (src in sources) sources.remove(src) else sources.add(src) }, label = { Text(src.label) })
                    }
                }
                Text(tr("Wochentag"), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { (d, l) -> FilterChip(weekday == d, { weekday = d }, label = { Text(l) }) }
                }
                ChoiceField(tr("Uhrzeit"), (5..22).map { it to "%02d:00".format(it) }, hour, { hour = it })
            }
        },
        confirmButton = {
            TextButton(enabled = query.isNotBlank() && sources.isNotEmpty(), onClick = {
                val t = io.github.thepytales.llmhub.research.LiteratureWatch.task(query, settings.defaultModel, sources.toList(), weekday = weekday, hour = hour)
                c.tasks.upsert(t.copy(nextRunAt = io.github.thepytales.llmhub.tasks.TaskScheduler.schedule(context, t)))
                io.github.thepytales.llmhub.tasks.TaskScheduler.runNow(context, t.id)
                Notices.info(tr("Literatur-Wächter gespeichert ({0}); die erste Suche läuft jetzt.", t.scheduleLabel()))
                onDismiss()
            }) { Text(tr("Speichern")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/**
 * ISBN/DOI per Foto (AP-138 f): the barcode on the back of a book is read on the device; otherwise the text of the
 * photo (title page, first page of an article) – with the text recognition of the PC or an image model this privacy
 * level allows (on the phone first a model on the device) – is searched for a DOI or ISBN.
 */
suspend fun identifiersOnPhoto(c: AppContainer, photo: java.io.File): Pair<List<String>, List<String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val raster = io.github.thepytales.llmhub.docs.ScanImages.read(photo, 2000)
    io.github.thepytales.llmhub.research.Barcodes.isbn(raster)?.let { return@withContext emptyList<String>() to listOf(it) }
    val png = io.github.thepytales.llmhub.docs.ScanImages.png(io.github.thepytales.llmhub.docs.ScanImages.read(photo, 1600))
    val text = io.github.thepytales.llmhub.docs.PdfPlatform.localOcr?.let { ocr ->
        val f = java.io.File.createTempFile("isbn-doi", ".png").apply { writeBytes(png) }
        try { ocr.recognize(listOf(f)).firstOrNull()?.text } finally { f.delete() }
    } ?: io.github.thepytales.llmhub.docs.PdfOcr.visionModel(c)?.let { model ->
        val url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
        val prompt = "Lies alle DOIs (beginnen mit 10.) und ISBNs auf diesem Foto ab. Gib nur diese Kennungen aus, eine pro Zeile, ohne Erklärung."
        runCatching {
            c.llm.streamChat(model, listOf(io.github.thepytales.llmhub.data.ChatMessage(role = "user", content = prompt, images = listOf(url))), tools = null,
                thinking = io.github.thepytales.llmhub.net.ThinkingLevel.OFF) {}.content
        }.getOrNull()
    } ?: throw IllegalStateException(tr("Kein Barcode erkannt, und für DOIs fehlt eine Texterkennung (ein Bildmodell unter Einstellungen › Modelle)."))
    io.github.thepytales.llmhub.research.Barcodes.identifiers(text)
}

/** The details of the work behind a DOI or ISBN (Crossref, Deutsche Nationalbibliothek, then the completion). */
suspend fun lookupIdentifier(c: AppContainer, doi: String?, isbn: String?): io.github.thepytales.llmhub.research.Reference? {
    val s = c.researchTools.sources(null)
    doi?.let { d -> runCatching { s.crossrefDoi(d) }.getOrNull()?.let { return it } }
    isbn?.let { i ->
        runCatching { s.dnbIsbn(i) }.getOrNull()?.let { return it.copy(isbn = it.isbn.ifBlank { i }) }
        val probe = io.github.thepytales.llmhub.research.Reference(type = io.github.thepytales.llmhub.research.RefType.BOOK, isbn = i)
        return runCatching { io.github.thepytales.llmhub.research.Bibliography.complete(s, listOf(probe)).first().ref }.getOrDefault(probe)
    }
    return doi?.let { io.github.thepytales.llmhub.research.Reference(doi = it) }
}

/** Literatur › Sammlung › „ISBN oder DOI fotografieren“: photo (or picture), recognition, details to check, save. */
@Composable
fun IdentifierPhotoDialog(c: AppContainer, list: String?, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    var found by remember { androidx.compose.runtime.mutableStateOf<io.github.thepytales.llmhub.research.Reference?>(null) }
    var typed by remember { androidx.compose.runtime.mutableStateOf("") }
    fun resolve(dois: List<String>, isbns: List<String>) {
        if (dois.isEmpty() && isbns.isEmpty()) { Notices.info(tr("Keine ISBN und keine DOI erkannt.")); busy = false; return }
        scope.launch {
            val ref = runCatching { lookupIdentifier(c, dois.firstOrNull(), isbns.firstOrNull()) }.getOrNull()
            busy = false
            if (ref == null) Notices.info(tr("Zu {0} nichts gefunden.", (dois + isbns).first())) else found = ref
        }
    }
    val sources = rememberScanSources { files ->
        val f = files.firstOrNull() ?: return@rememberScanSources
        busy = true
        scope.launch {
            runCatching { identifiersOnPhoto(c, f) }
                .onSuccess { (dois, isbns) -> resolve(dois, isbns) }
                .onFailure { Notices.error(it.message ?: tr("Das Foto ließ sich nicht lesen.")); busy = false }
        }
    }
    found?.let { r ->
        ReferenceEditor(r, onDismiss = { found = null }) { edited ->
            c.literature.addAll(listOf(edited), list)
            Notices.info(tr("In die Sammlung übernommen: {0}", edited.short().take(80)))
            found = null
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("ISBN oder DOI fotografieren")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("Den Barcode auf der Buchrückseite fotografieren (ISBN, wird auf dem Gerät gelesen) – oder die Titelseite bzw. die erste Seite eines Artikels mit der DOI."))
                sources.forEach { s -> OutlinedButton(enabled = !busy, onClick = s.start, modifier = Modifier.fillMaxWidth()) { Text(s.label) } }
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                OutlinedTextField(typed, { typed = it }, label = { Text(tr("Oder ISBN/DOI eingeben")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = typed.isNotBlank() && !busy, onClick = {
                busy = true
                val (dois, isbns) = io.github.thepytales.llmhub.research.Barcodes.identifiers(typed)
                resolve(dois, isbns.ifEmpty { typed.filter { it.isDigit() || it == 'X' }.takeIf { it.length == 10 || it.length == 13 }?.let { listOf(it) }.orEmpty() })
            }) { Text(tr("Suchen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}
