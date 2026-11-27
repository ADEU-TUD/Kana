package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
    var query by remember { androidx.compose.runtime.mutableStateOf(list?.removePrefix(tr("Wächter: ")).orEmpty()) }
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
