package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.ChatMessage
import io.github.thepytales.llmhub.data.VaultStore
import io.github.thepytales.llmhub.transcribe.Code
import io.github.thepytales.llmhub.transcribe.Codebook
import io.github.thepytales.llmhub.transcribe.InterviewAnalysis
import io.github.thepytales.llmhub.transcribe.RefiQda
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.transcribe.TranscriptAnon
import io.github.thepytales.llmhub.transcribe.TranscriptCoding
import io.github.thepytales.llmhub.transcribe.TranscriptExport
import io.github.thepytales.llmhub.transcribe.withCoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Schlüsselliste der Pseudonymisierung (AP-136 a): original → pseudonym, **only in the Tresor** – as an encrypted entry
 * „Zuordnungsliste – …“ that opens like a Tresor chat. Without an unlocked Tresor nothing is written (the copy is then
 * anonymous in effect: nobody can go back).
 */
object KeyList {
    /** Saves [mapping] of [title] in the Tresor; false when it is locked or not set up (then nothing is written). */
    fun save(c: AppContainer, title: String, mapping: List<Pair<String, String>>): Boolean {
        if (mapping.isEmpty() || c.vault.state.value != VaultStore.State.UNLOCKED) return false
        val entry = newVaultChat(c).copy(
            title = tr("Zuordnungsliste – {0}", title),
            messages = listOf(ChatMessage(role = "assistant", content = TranscriptAnon.mappingTable(title, mapping))),
        )
        c.vault.save(entry)
        return true
    }
}

/**
 * Pseudonymisieren (AP-136 a): what would be replaced – found by Kana Namensschutz (where the model is there) and the
 * rules, speakers with real names, the own list – with how often it occurs; each can be left out, missing words added.
 * Then a pseudonymized copy is made (original unchanged, codings kept, no audio: the voice would give the person away)
 * and the key list goes into the Tresor.
 */
@Composable
fun PseudonymizeDialog(c: AppContainer, t: Transcript, onDone: (Transcript) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var extra by remember { mutableStateOf(listOf<String>()) }
    var adding by remember { mutableStateOf("") }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val vault by c.vault.state.collectAsState()
    var pin by remember { mutableStateOf("") }
    val own = remember { c.settings.current.pseudonymTerms.lines().map { it.trim() }.filter { it.length >= 2 } }
    val findings by produceState<List<TranscriptAnon.Finding>?>(null, t, extra) {
        value = withContext(Dispatchers.Default) {
            runCatching { TranscriptAnon.findings(t, own, extra, detect = { text -> c.privacy.detect(text) }) }
                .getOrElse { TranscriptAnon.findings(t, own, extra) }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Pseudonymisieren")) },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("Namen, Orte, Einrichtungen und Kontaktdaten werden im ganzen Transkript durch dieselben Pseudonyme ersetzt – in einer Kopie. Das Original, die Codierungen und Zeiten bleiben; die Kopie hat keinen Ton (die Stimme würde die Person verraten)."))
                val list = findings
                if (list == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("Kana sucht Namen, Orte und Einrichtungen …"), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(tr("Gefunden – zum Prüfen (abwählen, was bleiben soll):"), style = MaterialTheme.typography.titleSmall)
                    if (list.isEmpty()) HintText(tr("Nichts gefunden. Fehlt etwas, unten eintragen."))
                    list.forEach { f ->
                        val on = f.value !in skipped
                        Row(
                            Modifier.fillMaxWidth().clickable { skipped = if (on) skipped + f.value else skipped - f.value }.testTag("fund:" + f.value),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, { skipped = if (on) skipped + f.value else skipped - f.value })
                            Text(f.value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("→ " + f.pseudonym, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(tr("{0}×", f.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(adding, { adding = it }, label = { Text(tr("Auch ersetzen (Name, Ort …)")) }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(enabled = adding.isNotBlank(), onClick = { extra = extra + adding.trim(); adding = "" }) { Text(tr("Hinzufügen")) }
                    }
                }
                // Die Schlüsselliste nur im Tresor.
                Text(tr("Schlüsselliste"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                when (vault) {
                    VaultStore.State.UNLOCKED -> HintText(tr("Kommt verschlüsselt in den Tresor („Zuordnungsliste – …“), nirgends sonst hin."))
                    VaultStore.State.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text(tr("PIN des Tresors")) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = pin.length >= 4, onClick = {
                            when (c.vault.unlock(pin)) {
                                is VaultStore.Unlock.Ok -> pin = ""
                                else -> Notices.error(tr("PIN falsch oder Tresor gesperrt."))
                            }
                        }) { Text(tr("Öffnen")) }
                    }
                    VaultStore.State.NOT_SET_UP -> HintText(tr("Kein Tresor eingerichtet: Es wird keine Schlüsselliste gespeichert – zurück zu den echten Namen geht es dann nicht. Den Tresor richtest du unter Werkzeuge › Tresor ein."))
                }
            }
        },
        confirmButton = {
            Button(enabled = findings != null && !busy, onClick = {
                busy = true
                scope.launch {
                    val mapping = findings.orEmpty().filter { it.value !in skipped }.map { it.value to it.pseudonym }
                    val result = withContext(Dispatchers.Default) { TranscriptAnon.apply(t, mapping) }
                    val copy = result.transcript.copy(id = UUID.randomUUID().toString().replace("-", "").take(24), audio = null)
                    runCatching { c.transcription.save(copy) }
                        .onSuccess {
                            val kept = KeyList.save(c, t.title, mapping)
                            Notices.info(
                                tr("Pseudonymisierte Kopie gespeichert: „{0}“", copy.title) + " – " +
                                    tr(if (kept) "Schlüsselliste im Tresor." else if (mapping.isEmpty()) "nichts ersetzt." else "ohne Schlüsselliste (Tresor nicht offen)."),
                            )
                            onDone(copy)
                        }
                        .onFailure { Notices.error(tr("Das hat nicht geklappt") + ": " + it.message) }
                    busy = false
                }
            }) { Text(tr("Kopie pseudonymisieren")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/**
 * Interviews auswerten (AP-136 b): the coded interviews of a project (or all) side by side – how often each code occurs
 * in which interview, a click shows every place of it in all interviews (with memo; a click opens the interview there).
 * Export of all as one REFI-QDA project with codes and memos – pseudonymized on request, the key lists into the
 * Tresor – or as table; a project or scheme from MAXQDA comes back with its codings ([RefiQda.read]).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InterviewAnalysisSheet(c: AppContainer, folder: String?, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val summaries by c.transcription.transcripts.collectAsState()
    val books by c.codebooks.items.collectAsState()
    val folders by c.folders.items.collectAsState()
    var reload by remember { mutableStateOf(0) }
    val scopeIds = remember(summaries, folder) { summaries.filter { folder == null || it.folder == folder }.map { it.id } }
    val loaded by produceState<List<Transcript>?>(null, scopeIds, reload) {
        value = scopeIds.mapNotNull { id -> runCatching { c.transcription.load(id) }.getOrNull() }
    }
    // In the order they were recorded (Interview 1, 2, 3 …).
    val items = remember(loaded) { loaded.orEmpty().sortedWith(compareBy({ it.created }, { it.title.lowercase() })).map { it to TranscriptCoding.of(it) } }
    val usedBooks = remember(items, books) {
        items.mapNotNull { it.second.codebookId }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.mapNotNull { e -> books.firstOrNull { it.id == e.key } }
    }
    var bookId by remember { mutableStateOf<String?>(null) }
    val book = books.firstOrNull { it.id == bookId } ?: usedBooks.firstOrNull()
    val coded = items.filter { (_, coding) -> book != null && coding.codebookId == book.id && coding.codings.isNotEmpty() }
    var retrieval by remember { mutableStateOf<Code?>(null) }
    var pseudo by remember { mutableStateOf(true) }
    var exportKind by remember { mutableStateOf("qdpx") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val title = folder?.let { id -> folders.firstOrNull { it.id == id }?.name } ?: tr("Alle Transkripte")
    val export = rememberFileExport(if (exportKind == "qdpx") "application/zip" else "text/csv", onDone = { ok -> if (ok) Notices.info(tr("Exportiert")) else Notices.error(tr("Export fehlgeschlagen")) }) { out ->
        val list = coded.ifEmpty { items }
        val prepared = if (!pseudo) list else list.map { (t, _) ->
            val own = c.settings.current.pseudonymTerms.lines().map { it.trim() }.filter { it.length >= 2 }
            val found = TranscriptAnon.findings(t, own, detect = { text -> c.privacy.detect(text) })
            val r = TranscriptAnon.apply(t, found.map { it.value to it.pseudonym })
            KeyList.save(c, t.title, r.mapping)
            r.transcript to TranscriptCoding.of(r.transcript)
        }
        if (exportKind == "qdpx") RefiQda.writeProject(out, title, prepared, book)
        else out.write(("\uFEFF" + RefiQda.csv(prepared, book)).toByteArray(Charsets.UTF_8))
    }
    val import = rememberMediaImport(anyFile = true) { name, _, input ->
        val bytes = withContext(Dispatchers.IO) { input.readBytes() }
        val report = withContext(Dispatchers.Default) { importRefi(c, bytes, name, loaded.orEmpty()) }
        Notices.info(report)
        reload++
    }
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).testTag("auswertung"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Interviews auswerten"), style = MaterialTheme.typography.titleLarge)
            if (loaded == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("Transkripte werden gelesen …"))
                }
                return@Column
            }
            HintText(tr("{0}: {1} Transkripte, davon {2} codiert. Codieren im Transkript unter Mehr › Codieren …", title, items.size, coded.size))
            if (books.isNotEmpty()) ChoiceField(tr("Kategoriensystem"), books.map { it.id as String? to tr("{0} ({1} Codes)", it.name, it.codes.size) }, book?.id, { bookId = it })
            if (book != null && coded.isNotEmpty()) {
                val counts = InterviewAnalysis.counts(coded, book)
                // Code × Interview: a row per code, a column per interview, the sum at the end.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Code"), Modifier.width(200.dp), style = MaterialTheme.typography.labelMedium)
                            coded.forEach { (t, _) -> Text(t.title.take(14), Modifier.width(92.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(tr("Summe"), Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        book.codes.forEach { code ->
                            val row = counts[code.id].orEmpty()
                            Row(Modifier.clickable { retrieval = code }.padding(vertical = 4.dp).testTag("code:" + code.name), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.width(200.dp).padding(start = if (code.parentId != null) 14.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(codeColor(code.color)))
                                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                                    Text(code.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                row.forEach { n -> Text(if (n == 0) "–" else "$n", Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium) }
                                Text("${row.sum()}", Modifier.width(60.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(InterviewAnalysis.table(coded, book)))
                    Notices.info(tr("Tabelle kopiert"))
                }) { Text(tr("Als Tabelle kopieren")) }
            } else HintText(tr("Noch keine codierten Interviews mit diesem Kategoriensystem."))
            androidx.compose.material3.HorizontalDivider()
            Text(tr("Für MAXQDA, ATLAS.ti, NVivo"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().clickable { pseudo = !pseudo }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pseudo, { pseudo = it })
                Text(tr("Pseudonymisiert exportieren (Schlüssellisten in den Tresor)"), style = MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = items.isNotEmpty(), onClick = { exportKind = "qdpx"; export(io.github.thepytales.llmhub.docs.DocumentWriter.safeName(title) + ".qdpx") }) { Text(tr("Alle als REFI-QDA-Projekt (.qdpx)")) }
                OutlinedButton(enabled = items.isNotEmpty(), onClick = { exportKind = "csv"; export(io.github.thepytales.llmhub.docs.DocumentWriter.safeName(title) + " – Codierungen.csv") }) { Text(tr("Codierungen als Tabelle (CSV)")) }
                OutlinedButton(onClick = import) { Text(tr("Aus MAXQDA übernehmen (.qdpx, .qdc) …")) }
            }
            HintText(tr("Ein Projekt mit allen Interviews, dem Kategoriensystem, den Codierungen je Codierer:in und den Memos. Zurück kommen Codes und Codierungen über REFI-QDA: Interviews mit gleichem Namen bekommen ihre Codierungen, das Kategoriensystem wird ergänzt."))
            androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
        }
    }
    retrieval?.let { code ->
        val hits = if (book == null) emptyList() else InterviewAnalysis.hits(coded, book, code.id)
        AlertDialog(
            onDismissRequest = { retrieval = null },
            title = { Text(tr("Code „{0}“ · {1} Stellen in {2} Interviews", code.name, hits.size, hits.map { it.transcript.id }.distinct().size)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (code.description.isNotBlank()) HintText(tr("Definition: {0}", code.description))
                    hits.forEach { h ->
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                // Opens the interview at this place (the player goes there, AP-135 b).
                                io.github.thepytales.llmhub.data.RecentWork.resume(io.github.thepytales.llmhub.data.RecentWork.Entry(io.github.thepytales.llmhub.data.RecentWork.TRANSCRIPT, h.transcript.id, h.transcript.title, "time:" + h.segment.start))
                                retrieval = null
                                onDismiss()
                                onOpen(h.transcript.id)
                            },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                HintText(h.transcript.title + " · " + TranscriptExport.clock(h.segment.start) + (if (h.transcript.diarized) " · " + h.transcript.speakerName(h.segment.speaker) else "") + " · " + h.coding.coder)
                                Text(h.coding.quote(h.segment), style = MaterialTheme.typography.bodySmall)
                                h.coding.memo?.takeIf { it.isNotBlank() }?.let { HintText(tr("Memo: {0}", it)) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(hits.joinToString("\n\n") { h -> "${h.transcript.title}, ${TranscriptExport.clock(h.segment.start)}: " + h.coding.quote(h.segment) }))
                    Notices.info(tr("Stellen kopiert"))
                }) { Text(tr("Kopieren")) }
            },
            dismissButton = { TextButton(onClick = { retrieval = null }) { Text(tr("Schließen")) } },
        )
    }
}

/**
 * Takes a REFI-QDA project or scheme from MAXQDA & Co. (AP-136 b): the scheme is merged into the one of the same name
 * (or added), each source finds its interview by name and gets its codings. Returns what happened, in one sentence.
 */
internal suspend fun importRefi(c: AppContainer, bytes: ByteArray, fileName: String, transcripts: List<Transcript>): String {
    val imported = runCatching { RefiQda.read(bytes, fileName) }.getOrElse { return tr("„{0}“ ließ sich nicht lesen: {1}", fileName, it.message ?: "") }
    val incoming = imported.codebook ?: return tr("In „{0}“ ist kein Kategoriensystem.", fileName)
    val existing = c.codebooks.items.value.firstOrNull { it.name.equals(incoming.name, true) }
    val (book, ids) = existing?.merge(incoming) ?: (incoming to incoming.codes.associate { it.id to it.id })
    c.codebooks.upsert(book)
    var placed = 0
    var missed = 0
    var interviews = 0
    imported.sources.forEach { src ->
        val t = InterviewAnalysis.matchSource(src.name, transcripts) ?: run { missed += src.selections.sumOf { it.codeIds.size }; return@forEach }
        val remapped = src.copy(selections = src.selections.map { s -> s.copy(codeIds = s.codeIds.mapNotNull { ids[it] }) })
        val r = RefiQda.place(t, remapped, TranscriptCoding.of(t).copy(codebookId = book.id))
        c.transcription.save(t.withCoding(r.coding))
        placed += r.placed
        missed += r.missed
        interviews++
    }
    return if (imported.sources.isEmpty()) tr("Kategoriensystem „{0}“ übernommen ({1} Codes).", book.name, book.codes.size)
    else tr("„{0}“: {1} Codierungen in {2} Interviews übernommen", book.name, placed, interviews) + if (missed > 0) tr(", {0} ohne passende Stelle.", missed) else "."
}
