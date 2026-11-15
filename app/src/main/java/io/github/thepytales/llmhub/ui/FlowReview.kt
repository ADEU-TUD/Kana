package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.docs.DocCompare
import io.github.thepytales.llmhub.docs.LiveDoc
import io.github.thepytales.llmhub.docs.LocalSpelling
import io.github.thepytales.llmhub.docs.MarkdownDocx
import io.github.thepytales.llmhub.docs.OfficeImport
import io.github.thepytales.llmhub.docs.Thesaurus
import io.github.thepytales.llmhub.docs.text.CharFormat
import io.github.thepytales.llmhub.docs.text.ParaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/*
 * Überprüfen (AP-126 e) in the Fließtext-Editor: Rechtschreibung beim Tippen (wavy lines, suggestions in the Rechtsklick-
 * Menü), Thesaurus, Vorlesen and Zwei Fassungen vergleichen.
 */

/** Rechtschreibung beim Tippen: the spell checker of the device (Windows, Android); tests put in their own. */
object FlowSpell {
    @Volatile
    var check: suspend (text: String, lang: String) -> List<LocalSpelling.Error> = { t, l -> LocalSpelling.check(t, l) }

    /** Words „Alle ignorieren“ took out (for this session, as in Word). */
    val ignored: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * The misspelled words of [text] ([lang]) as ranges – without ignored words, code and links (they are no prose).
     * A pause after typing comes before it (the caller waits).
     */
    suspend fun errors(text: String, chars: List<CharFormat>, kinds: (Int) -> ParaKind, lang: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val found = runCatching { check(text, lang) }.getOrDefault(emptyList())
        return found.mapNotNull { e ->
            val r = e.start until (e.start + e.length).coerceAtMost(text.length)
            if (r.isEmpty() || e.word in ignored) return@mapNotNull null
            val f = chars.getOrNull(r.first)
            if (f != null && (f.code || f.link != null) || kinds(r.first) == ParaKind.CODE) null else r
        }
    }
}

/** The wavy red line under each misspelled word of the field (Word's look). */
internal fun DrawScope.drawSpellLines(layout: TextLayoutResult, errors: List<IntRange>, shown: (Int) -> Int) {
    if (errors.isEmpty()) return
    val n = layout.layoutInput.text.length
    val red = Color(0xFFD32F2F)
    val wave = 2.dp.toPx()
    val step = 2.5.dp.toPx()
    for (r in errors) {
        val a = shown(r.first).coerceIn(0, n)
        val b = shown(r.last + 1).coerceIn(a, n)
        if (b <= a) continue
        val line = layout.getLineForOffset(a)
        val x1 = layout.getHorizontalPosition(a, true)
        val x2 = if (layout.getLineForOffset(b) == line) layout.getHorizontalPosition(b, true) else layout.getLineRight(line)
        val y = layout.getLineBaseline(line) + 2.dp.toPx()
        val path = Path()
        var x = minOf(x1, x2)
        val end = maxOf(x1, x2)
        path.moveTo(x, y)
        var up = true
        while (x < end) {
            x = minOf(x + step, end)
            path.lineTo(x, if (up) y + wave else y)
            up = !up
        }
        drawPath(path, red, style = Stroke(1.dp.toPx()))
    }
}

// ── Thesaurus ───────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * *Extras › Thesaurus …* and *Synonyme …* im Rechtsklick (Umschalt+F7 like Word): the synonyms of the word at the cursor,
 * a click puts one in its place. The first time the thesaurus is loaded onto the device (OpenThesaurus).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThesaurusDialog(c: AppContainer, state: FlowState, onDismiss: () -> Unit) {
    val dir = c.appContext.filesDir
    val t = state.active
    val at = remember { t?.let { wordAround(it.rich.text, it.selection) } }
    var query by remember { mutableStateOf(at?.let { r -> t!!.rich.text.substring(r.first, r.last + 1) }.orEmpty()) }
    var ready by remember { mutableStateOf(Thesaurus.installed(dir)) }
    var loading by remember { mutableStateOf<Double?>(null) }
    var index by remember { mutableStateOf<Thesaurus.Index?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(ready) {
        if (ready) index = withContext(Dispatchers.IO) { runCatching { Thesaurus.index(dir) }.getOrNull() }
    }
    val groups = remember(index, query) { index?.lookup(query).orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Thesaurus")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready) {
                    Text(tr("Für Synonyme lädt die App einmalig den deutschen Thesaurus von OpenThesaurus (etwa 1,6 MB) auf dieses Gerät. Danach bleibt beim Nachschlagen jedes Wort hier."))
                    loading?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                    return@Column
                }
                OutlinedTextField(query, { query = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Wort")) }, modifier = Modifier.fillMaxWidth())
                if (index == null) Text(tr("Wird gelesen …"))
                else if (groups.isEmpty()) Text(tr("Keine Synonyme gefunden."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEach { g ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.forEach { e ->
                            AssistChip(onClick = {
                                onDismiss()
                                if (t != null && at != null) state.replaceWord(t, at, e.word) else state.insertText(e.word)
                            }, label = { Text(e.shown) })
                        }
                    }
                }
                HintText(tr("Quelle: {0}", Thesaurus.SOURCE))
            }
        },
        confirmButton = {
            if (!ready) TextButton(enabled = loading == null, onClick = {
                loading = 0.0
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Thesaurus.download(dir) { p -> loading = p } } }
                    loading = null
                    ok.onSuccess { ready = true }.onFailure { Notices.error(tr("Der Thesaurus ließ sich nicht laden: {0}", it.message ?: "")) }
                }
            }) { Text(tr("Thesaurus laden")) }
            else TextButton(onClick = onDismiss) { Text(tr("Schließen")) }
        },
        dismissButton = { if (!ready) TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** The word at [sel] (or the selected word) in [text]. */
internal fun wordAround(text: String, sel: TextRange): IntRange? {
    if (!sel.collapsed) return (sel.min until sel.max).takeIf { r -> sel.max - sel.min <= 60 && text.substring(r.first, r.last + 1).all { it.isLetter() || it == '-' } }
    var a = sel.start.coerceIn(0, text.length)
    var b = a
    while (a > 0 && text[a - 1].isLetter()) a--
    while (b < text.length && text[b].isLetter()) b++
    return if (b - a >= 2) a until b else null
}

/** [range] of [t] becomes [word] in the format of its first letter (a capital stays a capital). */
internal fun FlowState.replaceWord(t: FlowSeg.Text, range: IntRange, word: String) {
    val old = t.rich.text.substring(range.first, range.last + 1)
    val fitted = if (old.firstOrNull()?.isUpperCase() == true && word.firstOrNull()?.isLowerCase() == true) word.replaceFirstChar { it.uppercase() } else word
    checkpoint()
    t.rich = t.rich.replace(range, fitted, t.rich.chars.getOrNull(range.first)?.stored ?: CharFormat.PLAIN)
    t.selection = TextRange(range.first + fitted.length)
    changed()
}

// ── Vorlesen ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** What *Vorlesen* reads: the selection, else the text from the paragraph at the cursor to the end of the document. */
internal fun FlowState.readAloudText(): String {
    val t = active
    if (t != null && !t.selection.collapsed) return t.rich.text.substring(t.selection.min, t.selection.max)
    val texts = segs.filterIsInstance<FlowSeg.Text>()
    val from = t?.let { texts.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    return texts.drop(from).mapIndexed { k, s ->
        if (k == 0 && t != null && s === t) s.rich.text.substring(s.rich.starts[s.rich.paraAt(s.selection.min)]) else s.rich.text
    }.joinToString("\n\n").trim()
}

/** *Extras › Vorlesen* (Strg+Alt+Leertaste like Word): with the voice of this device; again stops it. */
internal fun readAloud(c: AppContainer, state: FlowState) {
    if (c.speaker.playingId.value == READ_ID) { c.speaker.stop(); return }
    val text = state.readAloudText()
    if (text.isBlank()) { Notices.info(tr("Hier steht nichts zum Vorlesen.")); return }
    c.speaker.speak(READ_ID, text, deviceOnly = true)
}

internal const val READ_ID = "dokument-vorlesen"

// ── Zwei Fassungen vergleichen ─────────────────────────────────────────────────────────────────────────────────────

/**
 * *Extras › Fassungen vergleichen …* (like Word's *Vergleichen*): this document against an earlier version of it, another
 * document of the list or a file (Word, ODT, Markdown, Text) – the differences paragraph by paragraph, word by word, and
 * as Word file with tracked changes to send on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompareDialog(c: AppContainer, doc: LiveDoc, state: FlowState, onDismiss: () -> Unit) {
    var other by remember { mutableStateOf<Pair<String, String>?>(null) }
    val pick = rememberMediaImport(anyFile = true) { name, _, input ->
        val bytes = withContext(Dispatchers.IO) { input.use { it.readBytes() } }
        val imported = withContext(Dispatchers.IO) { runCatching { OfficeImport.read(name, bytes) }.getOrNull() }
        val md = imported?.tabs?.joinToString("\n\n") { it.second }
        if (md.isNullOrBlank()) Notices.error(tr("Aus dieser Datei ließ sich kein Text lesen.")) else other = name to md
    }
    val now = remember { state.markdown() }
    val changes = remember(other) { other?.let { DocCompare.diff(it.second, now) }?.filter { it.kind != DocCompare.Kind.SAME } }
    val save = rememberFileExport("application/vnd.openxmlformats-officedocument.wordprocessingml.document", onDone = { ok -> if (ok) Notices.info(tr("Gespeichert.")) }) { out ->
        val o = other ?: return@rememberFileExport
        val f = java.io.File.createTempFile("vergleich", ".docx")
        try {
            withContext(Dispatchers.IO) {
                MarkdownDocx.writeTracked(f, doc.title, o.second, now, author = tr("Vergleich"))
                f.inputStream().use { it.copyTo(out) }
            }
        } finally { f.delete() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Fassungen vergleichen")) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (other == null) {
                    Text(tr("Womit soll dieses Dokument verglichen werden?"))
                    if (doc.versions.isNotEmpty()) {
                        Text(tr("Frühere Fassung"), style = MaterialTheme.typography.titleSmall)
                        doc.versions.sortedByDescending { it.createdAt }.take(8).forEach { v ->
                            val label = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(v.createdAt)) + " · " + v.author
                            TextButton(onClick = { other = label to v.tabs.joinToString("\n\n") { it.markdown } }) { Text(label) }
                        }
                    }
                    val others = c.docs.current.filter { it.id != doc.id && it.kind == io.github.thepytales.llmhub.docs.DocKind.DOC.name }.take(12)
                    if (others.isNotEmpty()) {
                        Text(tr("Anderes Dokument"), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            others.forEach { d -> AssistChip(onClick = { other = d.title to d.tabs.joinToString("\n\n") { it.markdown } }, label = { Text(d.title) }) }
                        }
                    }
                    TextButton(onClick = pick) { Text(tr("Datei wählen (Word, ODT, Markdown, Text) …")) }
                    return@Column
                }
                val list = changes.orEmpty()
                Text(tr("Verglichen mit: {0}", other!!.first), style = MaterialTheme.typography.titleSmall)
                Text(if (list.isEmpty()) tr("Keine Unterschiede im Text.") else tr("{0} Stellen unterscheiden sich.", list.size))
                list.take(200).forEach { ch ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(8.dp)) {
                        Text(
                            when (ch.kind) { DocCompare.Kind.ADDED -> tr("Neu"); DocCompare.Kind.REMOVED -> tr("Entfernt"); else -> tr("Geändert") },
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                        )
                        WordDiff(ch.old, ch.new)
                    }
                }
            }
        },
        confirmButton = {
            if (other != null) TextButton(onClick = { save(doc.title + " – " + tr("Vergleich") + ".docx") }) { Text(tr("Als Word mit Änderungen speichern …")) }
            else TextButton(onClick = onDismiss) { Text(tr("Schließen")) }
        },
        dismissButton = { if (other != null) TextButton(onClick = { other = null }) { Text(tr("Andere Fassung")) } },
    )
}

/** Rechtsklick: a misspelled word everywhere in this session left alone (Word's „Alle ignorieren“). */
internal fun FlowState.ignoreWord(word: String) {
    FlowSpell.ignored += word
    spellRound++
}
