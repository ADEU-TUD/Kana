package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.docs.DNode
import io.github.thepytales.llmhub.docs.DEdge
import io.github.thepytales.llmhub.docs.Diagram
import io.github.thepytales.llmhub.docs.Diagrams
import io.github.thepytales.llmhub.docs.DocBlocks
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.LiveDoc
import io.github.thepytales.llmhub.docs.text.ParaKind
import io.github.thepytales.llmhub.docs.text.RichText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Docs-Komfort (AP-126 f) in the Fließtext-Editor: Bausteine (eigene und mitgelieferte) und das @-Menü, Textfeld und
 * einfache Formen, Steuerzeichen anzeigen, Fokusmodus, Diagramm aus einer Tabelle, Emoji.
 */

// ── Bausteine ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** A Baustein: a piece of document (Markdown) to put in by name. */
data class Snippet(val name: String, val markdown: String, val builtIn: Boolean = false)

object Snippets {
    /** Like Google Docs' Bausteine: a meeting note and an e-mail draft, and a Briefschluss of the Verwaltung. */
    fun builtIn(today: String = DocMarkup.today()): List<Snippet> = listOf(
        Snippet(
            "Besprechungsnotiz",
            "## Besprechung am $today\n\n**Teilnehmende:** …\n\n**Tagesordnung**\n\n1. Punkt\n\n**Beschlüsse**\n\n- Beschluss\n\n**Aufgaben**\n\n- [ ] Aufgabe (wer, bis wann)",
            builtIn = true,
        ),
        Snippet("E-Mail-Entwurf", "**An:** …\n\n**Cc:** …\n\n**Betreff:** …\n\nSehr geehrte Damen und Herren,\n\n…\n\nMit freundlichen Grüßen", builtIn = true),
        Snippet("Briefschluss", "Mit freundlichen Grüßen\n\n\n\n(Name)  \n(Funktion)", builtIn = true),
    )

    /** The own ones from the settings („Name⇥Markdown“, line breaks of the Markdown as ⏎). */
    fun own(stored: String): List<Snippet> = stored.lines().mapNotNull { l ->
        val name = l.substringBefore('\t', "").trim()
        val md = l.substringAfter('\t', "").replace("⏎", "\n")
        if (name.isEmpty() || md.isBlank()) null else Snippet(name, md)
    }

    /** [list] as stored in the settings. */
    fun store(list: List<Snippet>): String = list.filter { !it.builtIn }.joinToString("\n") { it.name.replace("\t", " ").replace("\n", " ") + "\t" + it.markdown.replace("\n", "⏎") }

    fun all(c: AppContainer): List<Snippet> = own(c.settings.current.docSnippets) + builtIn()

    fun save(c: AppContainer, s: Snippet) {
        val list = own(c.settings.current.docSnippets).filter { it.name != s.name } + s
        c.settings.update { it.copy(docSnippets = store(list)) }
    }

    fun delete(c: AppContainer, name: String) {
        c.settings.update { it.copy(docSnippets = store(own(it.docSnippets).filter { s -> s.name != name })) }
    }
}

/** Puts the Markdown of a Baustein at the cursor (its paragraphs with their formats; tables and blocks as blocks). */
internal fun FlowState.insertMarkdown(markdown: String) {
    // A Baustein starts a paragraph of its own (not in the middle of the one at the cursor).
    active?.let { t ->
        val r = t.rich
        val at = t.selection.min.coerceIn(0, r.text.length)
        if (r.paraText(r.paraAt(at)).isNotBlank()) {
            t.selection = TextRange(r.paraRange(r.paraAt(at)).last + 1)
            insertText("\n")
            t.rich = t.rich.withParas(t.rich.paras.mapIndexed { i, q -> if (i == t.rich.paraAt(t.selection.min)) io.github.thepytales.llmhub.docs.text.ParaFormat() else q })
        }
    }
    val blocks = DocBlocks.split(markdown)
    if (blocks.all { RichText.isFlowBlock(it) }) {
        val t = active ?: return
        paste(t, RichText.ofMarkdown(markdown))
        return
    }
    // Mixed: the running text in pieces, blocks of their own between them.
    var run = mutableListOf<String>()
    fun flush() {
        if (run.isEmpty()) return
        active?.let { paste(it, RichText.ofMarkdown(run.joinToString("\n\n"))) }
        run = mutableListOf()
    }
    for (b in blocks) if (RichText.isFlowBlock(b)) run += b else { flush(); insertBlock(b) }
    flush()
}

/** The selection as Markdown (for „Markierung als Baustein speichern“). */
internal fun FlowState.selectionMarkdown(): String? {
    val t = active ?: return null
    val sel = t.selection
    if (sel.collapsed) return null
    return RichText.toBlocks(t.rich.slice(sel.min, sel.max)).joinToString("\n\n").takeIf { it.isNotBlank() }
}

// ── The @-Menü ───────────────────────────────────────────────────────────────────────────────────────────────────

/** An entry of the @-Menü: what it says and what it does (the „@…“ typed is gone before). */
internal class AtEntry(val label: String, val group: String, val act: (FlowState) -> Unit)

/** Where the @-Menü is open: the field and the offset of the „@“. */
internal data class AtMenu(val seg: FlowSeg.Text, val at: Int)

/** The entries of the @-Menü (like Google Docs): dates, Bausteine, a table, headings, people, documents, more. */
internal fun atEntries(c: AppContainer, doc: LiveDoc?): List<AtEntry> {
    val day = java.time.LocalDate.now()
    val fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
    return buildList {
        add(AtEntry(tr("Heute ({0})", day.format(fmt)), tr("Datum")) { it.insertText(day.format(fmt)) })
        add(AtEntry(tr("Morgen ({0})", day.plusDays(1).format(fmt)), tr("Datum")) { it.insertText(day.plusDays(1).format(fmt)) })
        add(AtEntry(tr("Gestern ({0})", day.minusDays(1).format(fmt)), tr("Datum")) { it.insertText(day.minusDays(1).format(fmt)) })
        Snippets.all(c).forEach { s -> add(AtEntry(s.name, tr("Baustein")) { it.insertMarkdown(s.markdown) }) }
        add(AtEntry(tr("Tabelle 3 × 3"), tr("Einfügen")) { it.insertBlock(DocBlocks.table(listOf(List(3) { k -> tr("Spalte {0}", k + 1) }) + List(2) { List(3) { "" } })) })
        add(AtEntry(tr("Überschrift 1"), tr("Format")) { it.paragraph { p -> p.copy(kind = ParaKind.H2) } })
        add(AtEntry(tr("Überschrift 2"), tr("Format")) { it.paragraph { p -> p.copy(kind = ParaKind.H3) } })
        add(AtEntry(tr("Checkliste"), tr("Format")) { it.paragraph { p -> p.copy(kind = ParaKind.CHECK) } })
        add(AtEntry(tr("Person …"), tr("Person")) { it.dialog = "person" })
        add(AtEntry(tr("Textfeld"), tr("Einfügen")) { it.insertTextBox() })
        add(AtEntry(tr("Inhaltsverzeichnis"), tr("Einfügen")) { it.insertBlock(DocMarkup.TOC) })
        add(AtEntry(tr("Trennlinie"), tr("Einfügen")) { it.insertBlock("---") })
        add(AtEntry(tr("Seitenumbruch"), tr("Einfügen")) { it.insertBlock(DocMarkup.PAGE_BREAK) })
        add(AtEntry(tr("Emoji …"), tr("Einfügen")) { it.dialog = "emoji" })
        c.docs.current.filter { it.id != doc?.id && it.file != null }.take(8).forEach { d ->
            add(AtEntry(d.title, tr("Datei")) { st -> st.insertLink(d.title, java.io.File(d.file!!).toURI().toString()) })
        }
    }
}

/** The entries that fit what was typed after the „@“. */
internal fun atFilter(entries: List<AtEntry>, query: String): List<AtEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return entries
    return entries.filter { it.label.lowercase().contains(q) || it.group.lowercase().startsWith(q) }
}

/** Text with a link at the cursor (Smart Chip for a file or a person). */
internal fun FlowState.insertLink(text: String, link: String) {
    val t = active ?: return
    checkpoint()
    val sel = t.selection
    val fmt = formatHere().stored
    t.rich = t.rich.replace(sel.min until sel.max, text, fmt.copy(link = link))
    t.selection = TextRange(sel.min + text.length)
    t.typing = fmt.copy(link = null)
    changed()
}

/** Removes what was typed from the „@“ to the cursor (the menu took it). */
internal fun FlowState.dropAt(m: AtMenu) {
    val t = m.seg
    val end = t.selection.start.coerceIn(m.at, t.rich.text.length)
    if (t.rich.text.getOrNull(m.at) != '@') return
    checkpoint()
    t.rich = t.rich.cut(m.at, end)
    t.selection = TextRange(m.at)
    changed()
}

/** The open @-Menü: the entries for what was typed after „@“; a click (or Enter on the first) runs one. */
@Composable
internal fun AtMenuPopup(c: AppContainer, doc: LiveDoc?, state: FlowState, m: AtMenu, onDone: () -> Unit) {
    val text = m.seg.rich.text
    val query = text.substring((m.at + 1).coerceAtMost(text.length), m.seg.selection.start.coerceIn(m.at + 1, text.length).coerceAtLeast((m.at + 1).coerceAtMost(text.length)))
    val all = remember(doc?.id) { atEntries(c, doc) }
    val shown = atFilter(all, query).take(10)
    androidx.compose.material3.DropdownMenu(true, onDismissRequest = onDone, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        if (shown.isEmpty()) Text(tr("Nichts gefunden"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        shown.forEach { e ->
            MenuRow(e.label, e.group, true) {
                onDone()
                state.dropAt(m)
                e.act(state)
            }
        }
    }
}

// ── Textfeld und Formen ─────────────────────────────────────────────────────────────────────────────────────────

/** *Einfügen › Textfeld*: a framed paragraph of its own (in Word and PDF a box around the text). */
internal fun FlowState.insertTextBox() {
    val t = active ?: return
    checkpoint()
    val r = t.rich
    val end = r.paraRange(r.paraAt(t.selection.max)).last + 1
    val label = tr("Text im Textfeld")
    // On a line of its own after the paragraph at the cursor, framed – the text marked to type over it.
    var nr = r.replace(end until end, "\n" + label, io.github.thepytales.llmhub.docs.text.CharFormat.PLAIN)
    val np = nr.paraAt(end + 1)
    nr = nr.withParas(nr.paras.mapIndexed { i, q -> if (i == np) io.github.thepytales.llmhub.docs.text.ParaFormat(border = true) else q })
    t.rich = nr
    t.selection = TextRange(end + 1, end + 1 + label.length)
    changed()
}

/** Shapes to choose: id (diagram shape), name. */
internal val SHAPES = listOf("box" to "Rechteck", "round" to "Abgerundet", "circle" to "Kreis", "diamond" to "Raute", "note" to "Notiz", "arrow" to "Pfeil (zwei Kästen)")

/** A simple shape as diagram (Word and PDF draw it as a picture, the diagram editor edits it). */
internal fun shapeBlock(shape: String, text: String, second: String = ""): String {
    val d = if (shape == "arrow") Diagram(kind = "flow", direction = "LR", nodes = listOf(DNode("a", text), DNode("b", second.ifBlank { "…" })), edges = listOf(DEdge("a", "b")))
    else Diagram(kind = "flow", direction = "LR", nodes = listOf(DNode("a", text, shape = shape)))
    return Diagrams.block(d)
}

// ── Diagramm aus Daten ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * *Einfügen › Diagramm aus Tabelle*: a chart of [rows] (first row: names of the series, first column: categories) –
 * null when there are no numbers.
 */
internal fun chartFromTable(rows: List<List<String>>, type: String, title: String): String? {
    if (rows.size < 2) return null
    val head = rows.first()
    val body = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
    fun num(s: String) = s.trim().replace(".", "").replace(',', '.').replace("%", "").replace("€", "").trim().toDoubleOrNull()
    val cols = (1 until head.size).filter { c -> body.any { num(it.getOrElse(c) { "" }) != null } }
    if (cols.isEmpty()) return null
    val o = JsonObject(
        mapOf(
            "type" to JsonPrimitive(type),
            "title" to JsonPrimitive(title),
            "x_label" to JsonPrimitive(head.firstOrNull().orEmpty()),
            "labels" to JsonArray(body.map { JsonPrimitive(it.firstOrNull().orEmpty()) }),
            "series" to JsonArray(cols.map { c -> JsonObject(mapOf("name" to JsonPrimitive(head[c]), "values" to JsonArray(body.map { JsonPrimitive(num(it.getOrElse(c) { "" }) ?: 0.0) }))) }),
        ),
    )
    return "```chart\n$o\n```"
}

/** The tables of the document (blocks), for *Diagramm aus Tabelle*. */
internal fun FlowState.tables(): List<FlowSeg.Raw> = segs.filterIsInstance<FlowSeg.Raw>().filter { it.isTable }

// ── Steuerzeichen und Fokusmodus ─────────────────────────────────────────────────────────────────────────────────

/** *Ansicht › Steuerzeichen anzeigen* (¶ like Word): ¶ at each paragraph end, · for spaces, → for tabs. */
internal fun DrawScope.drawMarks(layout: TextLayoutResult, r: RichText, shown: (Int) -> Int, measurer: TextMeasurer, style: TextStyle) {
    val n = layout.layoutInput.text.length
    val pilcrow = measurer.measure("¶", style)
    val dot = measurer.measure("·", style)
    val arrow = measurer.measure("→", style)
    for (i in r.paras.indices) {
        val range = r.paraRange(i)
        val end = shown(range.last + 1).coerceIn(0, n)
        val line = layout.getLineForOffset(end)
        val x = if (range.isEmpty()) layout.getHorizontalPosition(end, true) else layout.getHorizontalPosition(end, true)
        drawText(pilcrow, topLeft = Offset(x + 1f, layout.getLineBaseline(line) - pilcrow.firstBaseline))
        for (o in range) {
            val ch = r.text[o]
            if (ch != ' ' && ch != '\t') continue
            val s = shown(o).coerceIn(0, n - 1)
            val box = layout.getBoundingBox(s)
            val m = if (ch == ' ') dot else arrow
            val l = layout.getLineForOffset(s)
            drawText(m, topLeft = Offset(box.left + (box.width - m.size.width) / 2f, layout.getLineBaseline(l) - m.firstBaseline))
        }
    }
}

/** Fokusmodus: everything but the paragraph at the cursor ([keep], null: all) in a faint colour. */
internal fun dimmed(text: AnnotatedString, keep: IntRange?, color: Color): AnnotatedString {
    if (text.isEmpty()) return text
    val b = AnnotatedString.Builder(text)
    val dim = SpanStyle(color = color)
    if (keep == null) b.addStyle(dim, 0, text.length)
    else {
        if (keep.first > 0) b.addStyle(dim, 0, keep.first.coerceAtMost(text.length))
        if (keep.last + 1 < text.length) b.addStyle(dim, (keep.last + 1).coerceAtLeast(0), text.length)
    }
    return b.toAnnotatedString()
}

// ── Emoji ────────────────────────────────────────────────────────────────────────────────────────────────────────

internal val EMOJIS = listOf(
    "😀", "😊", "🙂", "😉", "😍", "🤔", "😅", "😢", "😮", "👍", "👎", "👏", "🙏", "💪", "👋", "✌️",
    "✅", "❌", "⚠️", "❗", "❓", "💡", "📌", "📎", "📅", "📝", "📄", "📊", "📈", "📚", "🎓", "🏫",
    "⭐", "🔥", "🎉", "🚀", "⏰", "☕", "🍀", "❤️", "💙", "💚", "➡️", "⬅️", "⬆️", "⬇️", "🔍", "🔒",
)

// ── The dialogs ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The dialogs of Docs-Komfort, as [FlowState.dialog] asks for them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowComfortDialogs(c: AppContainer, doc: LiveDoc, state: FlowState) {
    val close = { state.dialog = null }
    when (state.dialog) {
        "baustein" -> {
            var version by remember { mutableStateOf(0) }
            val list = remember(version) { Snippets.all(c) }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(tr("Bausteine")) },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEach { s ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    s.name + if (s.builtIn) " · " + tr("mitgeliefert") else "",
                                    modifier = Modifier.weight(1f).clickable { close(); state.insertMarkdown(s.markdown) }.padding(vertical = 8.dp),
                                )
                                if (!s.builtIn) TextButton(onClick = { Snippets.delete(c, s.name); version++ }) { Text(tr("Löschen")) }
                            }
                        }
                        HintText(tr("Eigene Bausteine: Text markieren, dann Einfügen › Markierung als Baustein speichern. Im Text auch mit @ und dem Namen."))
                    }
                },
                confirmButton = { TextButton(onClick = close) { Text(tr("Schließen")) } },
            )
        }
        "baustein-speichern" -> {
            val md = remember { state.selectionMarkdown() }
            if (md == null) {
                AlertDialog(onDismissRequest = close, title = { Text(tr("Baustein speichern")) }, text = { Text(tr("Markiere zuerst den Text, der zum Baustein werden soll.")) }, confirmButton = { TextButton(onClick = close) { Text(tr("OK")) } })
            } else TextAskDialog(tr("Markierung als Baustein speichern"), tr("Name des Bausteins"), "", onDismiss = close) { name ->
                close()
                if (name.isNotBlank()) { Snippets.save(c, Snippet(name.trim(), md)); Notices.info(tr("Baustein „{0}“ gespeichert – Einfügen › Baustein oder @{0}.", name.trim())) }
            }
        }
        "person" -> {
            var name by remember { mutableStateOf("") }
            var mail by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(tr("Person einfügen")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Name")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(mail, { mail = it.trim() }, singleLine = true, label = { Text(tr("E-Mail (freiwillig)")) }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        close()
                        if (mail.contains('@')) state.insertLink(name.trim(), "mailto:$mail") else state.insertText(name.trim())
                    }) { Text(tr("Einfügen")) }
                },
                dismissButton = { TextButton(onClick = close) { Text(tr("Abbrechen")) } },
            )
        }
        "emoji" -> AlertDialog(
            onDismissRequest = close,
            title = { Text(tr("Emoji einfügen")) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EMOJIS.forEach { e ->
                        Surface(shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable { close(); state.insertText(e) }) {
                            Text(e, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(tr("Schließen")) } },
        )
        "form" -> {
            var shape by remember { mutableStateOf("box") }
            var text by remember { mutableStateOf(tr("Text")) }
            var second by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(tr("Form einfügen")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SHAPES.forEach { (id, label) -> FilterChip(selected = shape == id, onClick = { shape = id }, label = { Text(tr(label)) }) }
                        }
                        OutlinedTextField(text, { text = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Text in der Form")) }, modifier = Modifier.fillMaxWidth())
                        if (shape == "arrow") OutlinedTextField(second, { second = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Text im zweiten Kasten")) }, modifier = Modifier.fillMaxWidth())
                        HintText(tr("Die Form steht als Zeichnung im Dokument; ✎ öffnet sie im Diagramm-Editor."))
                    }
                },
                confirmButton = { TextButton(onClick = { close(); state.insertBlock(shapeBlock(shape, text.trim().ifBlank { " " }, second.trim())) }) { Text(tr("Einfügen")) } },
                dismissButton = { TextButton(onClick = close) { Text(tr("Abbrechen")) } },
            )
        }
        "diagramm-daten" -> {
            val tables = remember { state.tables() }
            var pick by remember { mutableStateOf(0) }
            var type by remember { mutableStateOf("bar") }
            var title by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(tr("Diagramm aus Tabelle")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 520.dp)) {
                        if (tables.isEmpty()) { Text(tr("Das Dokument hat noch keine Tabelle. Füge eine ein (erste Zeile: Namen der Reihen, erste Spalte: Kategorien)."));  return@Column }
                        tables.forEachIndexed { i, t ->
                            val head = DocBlocks.tableRows(t.markdown).firstOrNull().orEmpty().joinToString(" · ")
                            FilterChip(selected = pick == i, onClick = { pick = i }, label = { Text(tr("Tabelle {0}: {1}", i + 1, head.take(50))) })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("bar" to "Säulen", "hbar" to "Balken", "line" to "Linien", "pie" to "Kreis").forEach { (id, l) -> FilterChip(selected = type == id, onClick = { type = id }, label = { Text(tr(l)) }) }
                        }
                        OutlinedTextField(title, { title = it.replace("\n", " ") }, singleLine = true, label = { Text(tr("Titel")) }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = tables.isNotEmpty(), onClick = {
                        close()
                        val block = tables.getOrNull(pick)?.let { chartFromTable(DocBlocks.tableRows(it.markdown), type, title.trim()) }
                        if (block == null) Notices.info(tr("In der Tabelle stehen keine Zahlen.")) else state.insertBlock(block)
                    }) { Text(tr("Einfügen")) }
                },
                dismissButton = { TextButton(onClick = close) { Text(tr("Abbrechen")) } },
            )
        }
        else -> {}
    }
}

/** The style of the Steuerzeichen (grey, small, in the text's size). */
internal fun marksStyle(base: TextStyle, color: Color) = base.copy(color = color, fontWeight = FontWeight.Normal)
