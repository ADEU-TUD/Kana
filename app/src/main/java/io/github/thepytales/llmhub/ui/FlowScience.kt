package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.docs.DocFields
import io.github.thepytales.llmhub.docs.DocMarkup

/*
 * Wissenschaft im Dokument (AP-126 c) in the Fließtext-Editor: Beschriftungen mit Nummer, Querverweise, Lesezeichen und
 * nummerierte Überschriften. What is stored is described in docs/DocFields.
 */

/** „Abbildung 3“ for the numbered picture [seg] (its place among the numbered pictures), or null. */
internal fun FlowState.figureLabel(seg: FlowSeg.Raw): String? {
    if (DocMarkup.image(seg.markdown)?.nr == null) return null
    var n = 0
    for (s in segs) {
        if (s is FlowSeg.Raw && DocMarkup.image(s.markdown)?.nr != null) n++
        if (s === seg || s.id == seg.id) return DocFields.figureWord(language) + " " + n
    }
    return null
}

/** „Tabelle 2“ for the table caption [seg], or null. */
internal fun FlowState.tableLabel(seg: FlowSeg.Raw): String? {
    if (DocFields.tableCaption(seg.markdown) == null) return null
    var n = 0
    for (s in segs) {
        if (s is FlowSeg.Raw && DocFields.tableCaption(s.markdown) != null) n++
        if (s === seg || s.id == seg.id) return DocFields.tableWord(language) + " " + n
    }
    return null
}

/** The ids the document uses already (for new ones that are unique). */
internal fun FlowState.takenIds(): Set<String> = DocFields.entries(markdown(), language).map { it.id }.toSet()

/** The caption line before table [table], if it has one. */
internal fun FlowState.captionOf(table: FlowSeg.Raw): FlowSeg.Raw? {
    val i = segs.indexOfFirst { it.id == table.id }
    return (segs.getOrNull(i - 1) as? FlowSeg.Raw)?.takeIf { DocFields.tableCaption(it.markdown) != null }
}

/** Sets the caption of table [table] ([text]; null: none) – a line of its own before it, numbered. */
internal fun FlowState.setTableCaption(table: FlowSeg.Raw, text: String?) {
    val old = captionOf(table)
    if (text == null) { old?.let { setRaw(it, "") }; return }
    if (old != null) {
        val cap = DocFields.tableCaption(old.markdown)!!
        setRaw(old, DocFields.tableCaptionLine(cap.copy(text = text)))
        return
    }
    val id = DocFields.unique("tab-" + DocFields.slug(text.ifBlank { "tabelle" }), takenIds())
    checkpoint()
    val i = segs.indexOfFirst { it.id == table.id }.coerceAtLeast(0)
    segs.add(i, FlowSeg.Raw(newId(), DocFields.tableCaptionLine(DocFields.TableCaption(text, id))))
    changed()
}

/** The picture or table a caption goes to: the selected picture, else the first picture or table after the cursor. */
internal fun FlowState.captionTarget(table: Boolean): FlowSeg.Raw? {
    if (!table) picked?.let { id -> (segs.firstOrNull { it.id == id } as? FlowSeg.Raw)?.takeIf { DocMarkup.image(it.markdown) != null }?.let { return it } }
    val from = active?.let { a -> segs.indexOfFirst { it.id == a.id } } ?: 0
    val after = segs.drop(from).filterIsInstance<FlowSeg.Raw>()
    return if (table) after.firstOrNull { it.isTable } else after.firstOrNull { DocMarkup.image(it.markdown) != null }
}

/** Beschriftung einfügen (like Word): [text] under the picture with „Abbildung n:“, or above the table with „Tabelle n:“. */
internal fun FlowState.addCaption(table: Boolean, text: String): Boolean {
    val target = captionTarget(table) ?: return false
    if (table) { setTableCaption(target, text); return true }
    val img = DocMarkup.image(target.markdown) ?: return false
    val nr = img.nr ?: DocFields.unique("abb-" + DocFields.slug(text.ifBlank { img.alt.ifBlank { "abbildung" } }), takenIds())
    setRaw(target, img.copy(alt = text.ifBlank { img.alt }, nr = nr).markdown())
    return true
}

/** Turns the number of picture [seg] on or off. */
internal fun FlowState.toggleFigureNumber(seg: FlowSeg.Raw) {
    val img = DocMarkup.image(seg.markdown) ?: return
    val nr = if (img.nr != null) null else DocFields.unique("abb-" + DocFields.slug(img.alt.ifBlank { "abbildung" }), takenIds())
    setRaw(seg, img.copy(nr = nr).markdown())
}

/** Querverweis: [entry]'s label at the cursor, linked to it („Abbildung 3“ → #abb-…); it follows the numbers. */
internal fun FlowState.insertCrossRef(entry: DocFields.Entry) {
    val t = active ?: return
    checkpoint()
    val sel = t.selection
    t.rich = t.rich.replace(sel.min until sel.max, entry.label, formatHere().stored.copy(link = "#" + entry.id))
    t.selection = TextRange(sel.min + entry.label.length)
    // The text after the reference is not part of the link.
    t.typing = formatHere().stored.copy(link = null)
    changed()
}

/** Lesezeichen: the paragraph at the cursor becomes the target „#lz-[name]“ (null: none). */
internal fun FlowState.setBookmark(name: String?) {
    val id = name?.let { DocFields.slug(it) }
    paragraph { it.copy(bookmark = id) }
}

/** The Lesezeichen of the paragraph at the cursor. */
internal fun FlowState.bookmarkHere(): String? = paraHere().bookmark

/**
 * The cross references in the text follow the numbers (AP-126 c): a link to „#abb-…“ whose text was written by a
 * Querverweis („Abbildung 3“) takes the current label. Returns whether something changed (no step of its own).
 */
internal fun FlowState.refreshCrossRefs(labels: Map<String, String>): Boolean {
    var any = false
    for (t in segs.filterIsInstance<FlowSeg.Text>()) {
        var r = t.rich
        var i = 0
        var changed = false
        while (i < r.text.length) {
            val link = r.chars[i].link
            if (link == null || !link.startsWith("#")) { i++; continue }
            var e = i
            while (e < r.text.length && r.chars[e].link == link) e++
            val label = labels[link.substring(1)]
            val now = r.text.substring(i, e)
            if (label != null && label != now && DocFields.isGeneratedRef(now)) {
                r = r.replace(i until e, label, r.chars[i].stored)
                changed = true
                e = i + label.length
            }
            i = e
        }
        if (changed) {
            val sel = t.selection
            t.rich = r
            t.selection = TextRange(sel.start.coerceAtMost(r.text.length), sel.end.coerceAtMost(r.text.length))
            any = true
        }
    }
    if (any) changed()
    return any
}

/** Numbers of the headings (AP-126 c, when the document numbers them): text field id and paragraph → „1.2“. */
internal fun FlowState.headingNumbers(): Map<Pair<Long, Int>, String> {
    val at = mutableListOf<Pair<Long, Int>>()
    val levels = mutableListOf<Int>()
    for (t in segs.filterIsInstance<FlowSeg.Text>()) {
        t.rich.paras.forEachIndexed { p, f -> if (f.kind.heading) { at += t.id to p; levels += f.kind.level } }
    }
    val out = HashMap<Pair<Long, Int>, String>()
    DocFields.headingNumbers(levels).forEachIndexed { k, no -> if (no != null) out[at[k]] = no }
    return out
}

/** The caption above a table (`<!-- tabelle: … -->`): „Tabelle 2: Kosten“, a click edits it. */
@Composable
internal fun TableCaptionLine(state: FlowState, seg: FlowSeg.Raw) {
    val cap = DocFields.tableCaption(seg.markdown) ?: return
    var edit by remember { mutableStateOf(false) }
    val label = state.tableLabel(seg) ?: DocFields.tableWord(state.language)
    Row(Modifier.fillMaxWidth().clickable { edit = true }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label + (if (cap.text.isNotBlank()) ": " + cap.text else ""),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { edit = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Outlined.Edit, contentDescription = tr("Beschriftung bearbeiten"), modifier = Modifier.size(14.dp)) }
        IconButton(onClick = { state.setRaw(seg, "") }, modifier = Modifier.size(24.dp)) { Icon(Icons.Outlined.Delete, contentDescription = tr("Beschriftung löschen"), modifier = Modifier.size(14.dp)) }
    }
    if (edit) TextAskDialog(tr("Beschriftung der Tabelle"), label + ":", cap.text, onDismiss = { edit = false }) { text ->
        edit = false
        state.setRaw(seg, DocFields.tableCaptionLine(cap.copy(text = text)))
    }
}

/** A small dialog with one line of text. */
@Composable
internal fun TextAskDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it.replace("\n", " ") }, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone(text.trim()) }),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text(tr("Übernehmen")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** *Einfügen › Beschriftung …* (like Word): Abbildung or Tabelle, and the text after the number. */
@Composable
internal fun CaptionDialog(state: FlowState, onDismiss: () -> Unit) {
    val picture = state.captionTarget(false)
    var table by remember { mutableStateOf(picture == null && state.captionTarget(true) != null) }
    var text by remember { mutableStateOf(picture?.let { DocMarkup.image(it.markdown)?.alt }.orEmpty()) }
    val target = state.captionTarget(table)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Beschriftung einfügen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !table, onClick = { table = false }, label = { Text(DocFields.figureWord(state.language)) })
                    FilterChip(selected = table, onClick = { table = true }, label = { Text(DocFields.tableWord(state.language)) })
                }
                OutlinedTextField(text, { text = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Beschriftung")) }, modifier = Modifier.fillMaxWidth())
                HintText(
                    if (target == null) (if (table) tr("Nach dem Cursor steht keine Tabelle.") else tr("Nach dem Cursor steht kein Bild."))
                    else if (table) tr("Steht über der nächsten Tabelle, nummeriert; das Tabellenverzeichnis und Querverweise führen sie auf.")
                    else tr("Steht unter dem gewählten bzw. nächsten Bild, nummeriert; das Abbildungsverzeichnis und Querverweise führen es auf."),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = target != null, onClick = { onDismiss(); if (!state.addCaption(table, text.trim())) Notices.info(tr("Kein passendes Bild bzw. keine Tabelle gefunden.")) }) { Text(tr("Einfügen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** *Einfügen › Querverweis …* (like Word): the numbered pictures and tables, the headings and Lesezeichen to point to. */
@Composable
internal fun CrossRefDialog(state: FlowState, onDismiss: () -> Unit) {
    val entries = remember { DocFields.entries(state.markdown(), state.language).filter { it.kind != DocFields.Kind.HEADING || it.level > 1 || it.number.isNotEmpty() } }
    var kind by remember { mutableStateOf(entries.firstOrNull()?.kind ?: DocFields.Kind.FIGURE) }
    val kinds = listOf(
        DocFields.Kind.FIGURE to DocFields.figureWord(state.language), DocFields.Kind.TABLE to DocFields.tableWord(state.language),
        DocFields.Kind.HEADING to tr("Überschrift"), DocFields.Kind.BOOKMARK to tr("Lesezeichen"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Querverweis einfügen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { (k, label) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) }) }
                }
                val list = entries.filter { it.kind == kind }
                if (list.isEmpty()) HintText(
                    when (kind) {
                        DocFields.Kind.FIGURE -> tr("Noch keine Abbildung mit Nummer – Rechtsklick auf ein Bild › Mit Nummer beschriften.")
                        DocFields.Kind.TABLE -> tr("Noch keine Tabelle mit Beschriftung – Einfügen › Beschriftung …")
                        DocFields.Kind.HEADING -> tr("Noch keine Überschriften.")
                        DocFields.Kind.BOOKMARK -> tr("Noch keine Lesezeichen – Einfügen › Lesezeichen …")
                    },
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    list.forEach { e ->
                        Row(Modifier.fillMaxWidth().clickable { onDismiss(); state.insertCrossRef(e) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                            Text(
                                if (kind == DocFields.Kind.HEADING) (if (e.number.isNotEmpty()) e.number + "  " else "") + e.text else e.label + (if (e.kind != DocFields.Kind.BOOKMARK && e.text.isNotBlank()) ": " + e.text else ""),
                                modifier = Modifier.padding(start = if (kind == DocFields.Kind.HEADING) ((e.level - 2).coerceAtLeast(0) * 14).dp else 0.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HintText(tr("Der Text des Verweises („Abbildung 3“) folgt den Nummern, auch in Word und PDF."))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
    )
}

/** *Einfügen › Lesezeichen …*: names the paragraph at the cursor as a target of Querverweise. */
@Composable
internal fun BookmarkDialog(state: FlowState, onDismiss: () -> Unit) {
    val now = state.bookmarkHere()
    var name by remember { mutableStateOf(now.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Lesezeichen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("Der Absatz am Cursor wird zum Ziel für Querverweise und Links (in Word eine Textmarke)."))
                OutlinedTextField(name, { name = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Name")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDismiss(); state.setBookmark(name) }) { Text(tr("Übernehmen")) } },
        dismissButton = {
            Row {
                if (now != null) TextButton(onClick = { onDismiss(); state.setBookmark(null) }) { Text(tr("Entfernen")) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) }
            }
        },
    )
}
