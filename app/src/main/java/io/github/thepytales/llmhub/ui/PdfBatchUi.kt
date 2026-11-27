package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.ModelCatalog
import io.github.thepytales.llmhub.docs.PdfBatch
import io.github.thepytales.llmhub.docs.PdfBatchItem
import io.github.thepytales.llmhub.docs.PdfBatchProgress
import io.github.thepytales.llmhub.docs.PdfBatchReport
import io.github.thepytales.llmhub.docs.PdfBatchSteps
import io.github.thepytales.llmhub.docs.PdfOcr
import io.github.thepytales.llmhub.docs.PdfPlatform
import io.github.thepytales.llmhub.docs.PdfStage3
import io.github.thepytales.llmhub.docs.PdfStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The PDF-Stapel of the werkzeugkasten (AP-137 a): what is chosen (kept while the screen is closed) and the run that
 * goes on in the background – also while the person works in another tool. Kana's action „pdf.batch“ uses the same.
 */
object PdfBatchRun {
    data class State(
        val inputs: List<Pair<File, String>>,
        val steps: PdfBatchSteps,
        val outDir: File,
        val progress: PdfBatchProgress,
        val report: PdfBatchReport? = null,
        val cancelling: Boolean = false,
    )

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cancel = false
    private var job: Job? = null

    /** For tests: replaces the Texterkennung of the device. */
    @Volatile internal var ocrForTest: PdfBatch.Ocr? = null

    // What is chosen in the area *Stapel*.
    val inputs = mutableStateListOf<Pair<File, String>>()
    val roots = mutableStateListOf<File>()
    var steps by mutableStateOf(PdfBatchSteps(ocr = true, shrink = true, pdfA = PdfStage3.SUPPORTED))
    var outDir by mutableStateOf<File?>(null)
    var modelAgreed by mutableStateOf(false)

    val running: Boolean get() = state.value?.let { it.report == null } == true

    /** The Texterkennung for [steps]: on the device, or the vision model the person agreed to; null if there is none. */
    fun ocrFor(c: AppContainer, steps: PdfBatchSteps): PdfBatch.Ocr? {
        ocrForTest?.let { return it }
        if (!steps.ocr) return null
        val model = steps.ocrModel
        if (model == null && PdfPlatform.localOcr == null) return null
        return PdfBatch.Ocr { f, pages, work, progress -> PdfOcr.recognize(c, c.appContext, f, pages, model, work, progress) }
    }

    /** Where results go when nothing else was chosen. */
    fun defaultOut(c: AppContainer, roots: List<File>): File =
        roots.takeIf { PdfBatchPlatform.PATHS && it.isNotEmpty() && it.all { r -> !r.absolutePath.contains("pdf-work") && !r.absolutePath.contains("pdf-stapel") } }
            ?.let { PdfBatch.defaultOut(it) }
            ?: File(c.drive.created, tr("PDF-Stapel") + " " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.GERMANY).format(Date()))

    /** Starts a Stapel; false while one is running. [onDone] hears the report. */
    fun start(c: AppContainer, inputs: List<Pair<File, String>>, steps: PdfBatchSteps, outDir: File, onDone: (PdfBatchReport) -> Unit = {}): Boolean {
        synchronized(this) {
            if (running) return false
            cancel = false
            state.value = State(inputs, steps, outDir, PdfBatchProgress(0, inputs.size))
        }
        job = scope.launch {
            val report = runCatching {
                PdfBatch.run(c.appContext, inputs, steps, outDir, ocrFor(c, steps), progress = { p -> state.update { it?.copy(progress = p) } }, cancelled = { cancel })
            }.getOrElse { e ->
                PdfBatchReport(inputs.map { PdfBatchItem(it.first.absolutePath, it.second, PdfBatchItem.FAILED, notes = listOf(e.message ?: e.javaClass.simpleName)) }, steps, outDir.absolutePath)
            }
            state.update { it?.copy(report = report, cancelling = false) }
            runCatching { c.drive.changed() }
            Notices.info(tr("PDF-Stapel fertig") + ": " + report.summary())
            onDone(report)
        }
        return true
    }

    fun cancel() {
        cancel = true
        state.update { it?.copy(cancelling = true) }
    }

    /** Back to choosing (the report stays in „Bericht.txt“). */
    fun clear() {
        if (!running) state.value = null
    }

    suspend fun join() { job?.join() }
}

/**
 * Bereich *Stapel* (AP-137 a): many PDFs, open tabs or a folder with subfolders – Texterkennung, Schwärzen nach Liste,
 * Wasserzeichen und Kopfzeile, Verkleinern, PDF/A, Zusammenführen je Ordner – with progress, *Abbrechen* and a report
 * (what changed, what was left out and why). The originals stay; the results go into a new folder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfBatchArea(c: AppContainer, openTabs: List<Pair<File, String>>, onOpen: (List<File>) -> Unit) {
    val run by PdfBatchRun.state.collectAsStateWithLifecycle()
    val r = run
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("pdf.stapel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            r == null -> BatchSetup(c, openTabs)
            r.report == null -> BatchProgressCard(r)
            else -> BatchReportCard(r.report, onOpen)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchSetup(c: AppContainer, openTabs: List<Pair<File, String>>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var collecting by remember { mutableStateOf(false) }
    var stampsOpen by remember { mutableStateOf(false) }
    val steps = PdfBatchRun.steps
    fun add(found: List<Pair<File, String>>) {
        val known = PdfBatchRun.inputs.map { it.first.absolutePath }.toSet()
        PdfBatchRun.inputs.addAll(found.filter { it.first.absolutePath !in known })
    }
    val pickFiles = rememberPdfFilesPick { files -> PdfBatchRun.roots.addAll(files); add(PdfBatch.collect(files)) }
    val pickFolder = rememberProjectFolderPicker { path ->
        val dir = File(path)
        collecting = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { PdfBatch.collect(listOf(dir)) }
            collecting = false
            PdfBatchRun.roots.add(dir)
            add(found)
            if (found.isEmpty()) Notices.info(tr("In „{0}“ liegen keine PDFs.", dir.name))
        }
    }
    val importTerms = rememberFileImport(arrayOf("text/plain", "text/csv", "text/comma-separated-values")) { input ->
        val text = withContext(Dispatchers.IO) { input.use { it.readBytes().decodeToString() } }
        val terms = text.split('\n', '\r', ';', ',', '\t').map { it.trim().trim('"') }.filter { it.length >= 2 }
        PdfBatchRun.steps = PdfBatchRun.steps.copy(redactTerms = (PdfBatchRun.steps.redactTerms + terms).distinct())
        Notices.info(tr("{0} Begriffe geladen.", terms.size))
    }
    val local = PdfPlatform.localOcr != null || PdfBatchRun.ocrForTest != null
    val model = remember { if (local) null else PdfOcr.visionModel(c) }
    val outDir = PdfBatchRun.outDir ?: PdfBatchRun.defaultOut(c, PdfBatchRun.roots)

    // 1. Dateien
    SettingsCard(tr("PDFs für den Stapel")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (FolderAccess.SUPPORTED) Button(onClick = { pickFolder(tr("Ordner mit PDFs wählen")) }) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Ordner wählen …")) }
            OutlinedButton(onClick = pickFiles) { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("PDFs wählen …")) }
            if (openTabs.isNotEmpty()) OutlinedButton(onClick = { add(openTabs) }) { Text(tr("Offene PDFs ({0})", openTabs.size)) }
            if (PdfBatchRun.inputs.isNotEmpty()) TextButton(onClick = { PdfBatchRun.inputs.clear(); PdfBatchRun.roots.clear(); PdfBatchRun.outDir = null }) { Text(tr("Liste leeren")) }
            if (collecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        val inputs = PdfBatchRun.inputs
        if (inputs.isEmpty()) SettingsHint(tr("Einen Ordner wählen (mit allen Unterordnern) oder einzelne PDFs. Die Originale bleiben, wie sie sind."))
        else {
            Text(tr("{0} PDFs", inputs.size) + " · " + PdfBatch.size(inputs.sumOf { it.first.length() }), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                inputs.take(200).forEach { (f, rel) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { PdfBatchRun.inputs.removeAll { it.first == f } }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Remove, tr("Aus dem Stapel nehmen"), Modifier.size(16.dp)) }
                    }
                }
                if (inputs.size > 200) HintText(tr("… und {0} weitere", inputs.size - 200))
            }
        }
    }

    // 2. Schritte
    SettingsCard(tr("Was mit jedem PDF geschieht")) {
        SwitchRow(
            tr("Texterkennung"),
            when {
                local -> tr("Nur Seiten ohne Text (Scans): danach durchsuchbar und kopierbar. Auf diesem PC, nichts verlässt ihn.")
                model != null -> tr("Nur Seiten ohne Text. Hier mit {0}: die Seitenbilder gehen an den Server.", ModelCatalog.info(model).displayName)
                else -> tr("Hier gibt es keine Texterkennung (kein Bildmodell erlaubt).")
            },
            steps.ocr && (local || model != null),
            spot = "pdf.stapel.ocr",
        ) { on -> PdfBatchRun.steps = steps.copy(ocr = on, ocrModel = if (local) null else model) }
        if (steps.ocr && !local && model != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(PdfBatchRun.modelAgreed, { PdfBatchRun.modelAgreed = it })
            Text(tr("Ja, die Seitenbilder der Scans dürfen an {0} gehen.", ModelCatalog.info(model).displayName), style = MaterialTheme.typography.bodySmall)
        }
        if (PdfStage3.SUPPORTED) {
            SwitchRow(tr("Schwärzen nach Liste"), tr("Jeder Begriff wird überall geschwärzt, der Text darunter entfernt; die übrigen Wörter bleiben durchsuchbar."), steps.redact || steps.redactTerms.isNotEmpty(), spot = "pdf.stapel.schwaerzen") { on ->
                PdfBatchRun.steps = steps.copy(redactTerms = if (on) steps.redactTerms.ifEmpty { listOf("") } else emptyList())
            }
            if (steps.redactTerms.isNotEmpty()) {
                OutlinedTextField(
                    steps.redactTerms.joinToString("\n"), { v -> PdfBatchRun.steps = PdfBatchRun.steps.copy(redactTerms = v.split('\n').ifEmpty { listOf("") }) },
                    label = { Text(tr("Begriffe, einer pro Zeile (Namen, Matrikelnummern …)")) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("pdf.stapel.begriffe"),
                )
                TextButton(onClick = importTerms) { Text(tr("Liste aus Datei laden (.txt, .csv) …")) }
            }
        }
        SwitchRow(tr("Wasserzeichen und Kopfzeile"), steps.stamps.takeIf { it.isNotEmpty() }?.joinToString(" · ") { s -> (s.text.ifBlank { tr("Bild") }) + " (" + tr(s.place.label) + ")" } ?: tr("„Entwurf“, „Vertraulich“, Datum, „Seite x von y“ …"), steps.stamps.isNotEmpty(), spot = "pdf.stapel.wasserzeichen") { on ->
            if (on) stampsOpen = true else PdfBatchRun.steps = steps.copy(stamps = emptyList())
        }
        if (steps.stamps.isNotEmpty()) TextButton(onClick = { stampsOpen = true }) { Text(tr("Ändern …")) }
        if (PdfStage3.SHRINK) SwitchRow(tr("Verkleinern"), tr("Große Bilder kleiner rechnen – nur behalten, wenn die Datei kleiner wird."), steps.shrink, spot = "pdf.stapel.verkleinern") { PdfBatchRun.steps = steps.copy(shrink = it) }
        if (PdfStage3.SUPPORTED) SwitchRow(tr("Als PDF/A speichern"), tr("PDF/A-2b für die Langzeitarchivierung, als letzter Schritt."), steps.pdfA, spot = "pdf.stapel.pdfa") { PdfBatchRun.steps = steps.copy(pdfA = it) }
        SwitchRow(tr("Zusammenführen je Ordner"), tr("Die Ergebnisse jedes Unterordners werden ein PDF mit dem Namen des Ordners."), steps.mergePerFolder, spot = "pdf.stapel.zusammenfuehren") { PdfBatchRun.steps = steps.copy(mergePerFolder = it) }
    }

    // 3. Ziel
    SettingsCard(tr("Ergebnisse")) {
        Text(outDir.absolutePath, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        SettingsHint(tr("Ein neuer Ordner mit denselben Unterordnern und einem Bericht; die Originale werden nicht verändert."))
        if (FolderAccess.SUPPORTED) TextButton(onClick = {
            scope.launch { FolderAccess.pick(tr("Ordner für die Ergebnisse wählen"))?.let { PdfBatchRun.outDir = File(it) } }
        }) { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Anderen Ordner wählen …")) }
    }

    val clean = steps.copy(redactTerms = steps.redactTerms.map { it.trim() }.filter { it.isNotEmpty() })
    val needsConsent = clean.ocr && clean.ocrModel != null && !PdfBatchRun.modelAgreed
    val ready = PdfBatchRun.inputs.isNotEmpty() && clean.any && !needsConsent
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(enabled = ready, onClick = {
            if (!PdfBatchRun.start(c, PdfBatchRun.inputs.toList(), clean, outDir)) Notices.info(tr("Ein Stapel läuft schon."))
        }, modifier = Modifier.testTag("pdf.stapel.start")) { Text(tr("Stapel starten ({0} PDFs)", PdfBatchRun.inputs.size)) }
        if (clean.any) HintText(clean.labels().joinToString(" → "))
        else HintText(tr("Mindestens einen Schritt einschalten."))
    }
    if (stampsOpen) PdfStampDialog(PdfBatchRun.steps.stamps.ifEmpty { listOf(PdfStamp.ENTWURF) }, tr("Übernehmen"), onDismiss = { stampsOpen = false }) { list ->
        stampsOpen = false
        PdfBatchRun.steps = PdfBatchRun.steps.copy(stamps = list)
    }
}

@Composable
private fun BatchProgressCard(r: PdfBatchRun.State) {
    val p = r.progress
    SettingsCard(tr("Stapel läuft")) {
        LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().testTag("pdf.stapel.fortschritt"))
        Text(tr("{0} von {1} PDFs", p.done.coerceAtMost(p.total), p.total), style = MaterialTheme.typography.bodyMedium)
        if (p.current.isNotEmpty()) HintText(p.current + if (p.step.isNotEmpty()) " – " + p.step else "")
        SettingsHint(tr("Läuft im Hintergrund weiter, auch wenn du ein anderes Werkzeug öffnest."))
        if (r.cancelling) HintText(tr("Wird nach diesem Schritt angehalten …"))
        else OutlinedButton(onClick = { PdfBatchRun.cancel() }) { Text(tr("Abbrechen")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchReportCard(report: PdfBatchReport, onOpen: (List<File>) -> Unit) {
    val copied = remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    SettingsCard(tr(if (report.cancelled) "Stapel abgebrochen" else "Stapel fertig")) {
        Text(tr("{0} PDFs", report.items.size) + ": " + report.summary(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pdf.stapel.bericht"))
        Text(report.outDir, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (FolderAccess.SUPPORTED) Button(onClick = { FolderAccess.open(report.outDir) }) { Text(tr("Ordner öffnen")) }
            val results = (report.merged + report.items).mapNotNull { it.out?.let(::File) }.filter { it.isFile }
            if (results.isNotEmpty()) OutlinedButton(onClick = { onOpen(results.take(10)) }) { Text(if (results.size > 10) tr("Die ersten 10 öffnen") else tr("Im Werkzeugkasten öffnen")) }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report.text())); copied.value = true }) { Text(tr(if (copied.value) "Bericht kopiert" else "Bericht kopieren")) }
            TextButton(onClick = { PdfBatchRun.clear() }) { Text(tr("Neuer Stapel")) }
        }
    }
    val groups = listOf(
        tr("Zusammengeführt") to report.merged,
        tr("Geändert") to report.items.filter { it.status == PdfBatchItem.CHANGED },
        tr("Übersprungen") to report.items.filter { it.status == PdfBatchItem.SKIPPED },
        tr("Fehler") to report.items.filter { it.status == PdfBatchItem.FAILED },
        tr("Unverändert") to report.items.filter { it.status == PdfBatchItem.UNCHANGED },
        tr("Nicht bearbeitet (abgebrochen)") to report.items.filter { it.status == PdfBatchItem.CANCELLED },
    ).filter { it.second.isNotEmpty() }
    groups.forEach { (title, list) ->
        SettingsCard("$title (${list.size})") {
            list.take(300).forEach { item ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (icon, tint) = when (item.status) {
                        PdfBatchItem.CHANGED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                        PdfBatchItem.SKIPPED -> Icons.Outlined.Block to MaterialTheme.colorScheme.onSurfaceVariant
                        PdfBatchItem.FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                        else -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, null, Modifier.size(18.dp).padding(top = 2.dp), tint = tint)
                    Column(Modifier.widthIn(min = 0.dp).weight(1f)) {
                        Text(item.relative, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.notes.isNotEmpty()) Text(item.notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (list.size > 300) HintText(tr("… und {0} weitere – alle stehen im Bericht.", list.size - 300))
        }
    }
}
