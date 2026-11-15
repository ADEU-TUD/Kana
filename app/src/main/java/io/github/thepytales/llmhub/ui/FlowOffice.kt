package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.docs.DocMarkup
import io.github.thepytales.llmhub.docs.text.ListStyles
import io.github.thepytales.llmhub.docs.text.ParaKind
import io.github.thepytales.llmhub.docs.text.RichText
import io.github.thepytales.llmhub.docs.text.StyleSheet
import io.github.thepytales.llmhub.docs.text.Styles
import kotlin.math.roundToInt

/*
 * Absatz und Seite (AP-126 d) in the Fließtext-Editor: the dialogs *Absatz …*, *Listen-Optionen …*, *Rahmen und
 * Schattierung …*, *Seitenlayout …* and *Spalten …*, and how the editor shows what they set (space before and after,
 * frames and backgrounds, pages in their paper and colour with the watermark). What is stored: docs/text/RichText
 * (paragraph marker) and docs/DocMarkup (setting lines).
 */

// ── Space before and after on the sheet ─────────────────────────────────────────────────────────────────────────

/**
 * Abstand vor/nach (AP-126 d) on the screen: the room (pt) to add before paragraph i of [r] – its own space before and
 * the space after the paragraph before, as far as they go beyond what the Formatvorlage has (the editor shows that as the
 * empty line between paragraphs). Keys are the paragraph starts; the first paragraph of a field has none.
 */
internal fun paraGapsPt(r: RichText, sheet: StyleSheet): Map<Int, Float> {
    if (r.paras.none { it.before != null || it.after != null }) return emptyMap()
    val out = HashMap<Int, Float>()
    for (i in 1 until r.paras.size) {
        val p = r.paras[i]
        val prev = r.paras[i - 1]
        if (p.soft) continue
        val before = p.before?.let { (it - sheet.of(p.kind).before).coerceAtLeast(0) } ?: 0
        val after = prev.after?.let { (it - sheet.of(prev.kind).after).coerceAtLeast(0) } ?: 0
        val pt = (before + after).toFloat()
        if (pt > 0f) out[r.starts[i]] = pt
    }
    return out
}

/**
 * The page breaks of a text field with the room of [gaps] (offset → px): a gap at a page break adds to its room (the
 * page plan counted it before the line), a gap elsewhere is room of its own before the paragraph.
 */
internal fun withGaps(breaks: List<PageBreakAt>, gaps: Map<Int, Float>, text: String): List<PageBreakAt> {
    if (gaps.isEmpty()) return breaks
    val at = breaks.associateBy { it.offset }
    val out = ArrayList<PageBreakAt>(breaks.size + gaps.size)
    for (b in breaks) out += gaps[b.offset]?.let { g -> b.copy(height = b.height + g, gap = g) } ?: b
    for ((o, g) in gaps) if (o !in at && o in 1 until text.length) out += PageBreakAt.at(text, o, g).copy(gap = g)
    out.sortBy { it.offset }
    return out
}

// ── Frames and backgrounds ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Rahmen und Schattierung (AP-126 d): a box behind each run of framed or shaded paragraphs of the field – the background
 * across the width of the text, a thin line around it; paragraphs with the same frame and colour share one box.
 */
internal fun DrawScope.drawParaBoxes(layout: TextLayoutResult, r: RichText, shown: (Int) -> Int, line: Color) {
    if (r.paras.none { it.boxed }) return
    val n = layout.layoutInput.text.length
    val pad = 3.dp.toPx()
    var i = 0
    while (i < r.paras.size) {
        val p = r.paras[i]
        if (!p.boxed) { i++; continue }
        var j = i
        while (j + 1 < r.paras.size && r.paras[j + 1].border == p.border && r.paras[j + 1].shade == p.shade) j++
        val top = layout.getLineTop(layout.getLineForOffset(shown(r.starts[i]).coerceIn(0, n)))
        val endOff = shown(r.paraRange(j).last + 1).coerceIn(0, n)
        val bottom = layout.getLineBottom(layout.getLineForOffset(endOff))
        val rect = Size(size.width + 2 * pad, bottom - top + 2 * pad)
        val origin = Offset(-pad, top - pad)
        p.shade?.let { drawRect(Color(("FF" + it).toLong(16)), origin, rect) }
        if (p.border) drawRect(line, origin, rect, style = Stroke(1.dp.toPx()))
        i = j + 1
    }
}

// ── Lists ──────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The paragraphs (of the active field) of the list the cursor is in: from its first item to its last. */
internal fun FlowState.listAround(): IntRange? {
    val t = active ?: return null
    val r = t.rich
    val at = r.paraAt(t.selection.min)
    if (!r.paras[at].kind.list) return null
    var a = at
    while (a > 0 && r.paras[a - 1].kind.list) a--
    var b = at
    while (b + 1 < r.paras.size && r.paras[b + 1].kind.list) b++
    return a..b
}

/** Listen-Optionen for the list at the cursor: [form] for all its items, [start]/[goOn] for the item at the cursor's list start. */
internal fun FlowState.setListOptions(form: String?, start: Int?, goOn: Boolean) {
    val t = active ?: return
    val range = listAround() ?: return
    val first = range.first
    checkpoint()
    t.rich = t.rich.withParas(t.rich.paras.mapIndexed { i, p ->
        if (i !in range) p
        else {
            val fits = form == null || if (p.kind == ParaKind.NUMBERED) ListStyles.isNumberForm(form) else ListStyles.isBulletForm(form)
            p.copy(
                listFormat = if (fits) form else p.listFormat,
                listStart = if (i == first) start else null,
                listContinue = i == first && goOn,
            )
        }
    })
    changed()
}

// ── Dialogs ─────────────────────────────────────────────────────────────────────────────────────────────────────────

/** A whole number from a field („12“, „1,5“ → 1.5 for cm), or null. */
private fun number(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

private fun cm(mm: Int): String = if (mm == 0) "0" else "%.2f".format(java.util.Locale.GERMANY, mm / 10f).trimEnd('0').trimEnd(',')

/**
 * *Format › Absatz …* (like Word's Absatz dialog): Einzug links, erste Zeile (Einzug or hängend) in cm, Abstand vor und
 * nach in pt, Zeilenabstand, mit nächstem zusammenhalten, Seitenumbruch oberhalb.
 */
@Composable
internal fun ParagraphDialog(state: FlowState, onDismiss: () -> Unit) {
    val p = remember { state.paraHere() }
    val def = state.sheet.of(Styles.base(p.kind))
    var left by remember { mutableStateOf(cm(p.left)) }
    var firstKind by remember { mutableStateOf(when { p.first > 0 -> 1; p.first < 0 -> 2; else -> 0 }) }
    var first by remember { mutableStateOf(cm(kotlin.math.abs(p.first))) }
    var before by remember { mutableStateOf((p.before ?: def.before).toString()) }
    var after by remember { mutableStateOf((p.after ?: def.after).toString()) }
    var spacing by remember { mutableStateOf(p.spacing) }
    var keep by remember { mutableStateOf(p.keep) }
    var pageBefore by remember { mutableStateOf(p.pageBefore) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Absatz")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Einzug"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(left, { left = it.take(5) }, singleLine = true, label = { Text(tr("Links (cm)")) }, modifier = Modifier.width(120.dp))
                    OutlinedTextField(first, { first = it.take(5) }, singleLine = true, enabled = firstKind != 0, label = { Text(tr("Um (cm)")) }, modifier = Modifier.width(120.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Erste Zeile: ohne", "Erste Zeile eingerückt", "Hängend").forEachIndexed { k, label ->
                        FilterChip(selected = firstKind == k, onClick = { firstKind = k; if (k != 0 && number(first) == 0f) first = "1,25" }, label = { Text(tr(label)) })
                    }
                }
                Text(tr("Abstand"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(before, { before = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true, label = { Text(tr("Vor (pt)")) }, modifier = Modifier.width(120.dp))
                    OutlinedTextField(after, { after = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true, label = { Text(tr("Nach (pt)")) }, modifier = Modifier.width(120.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = spacing == null, onClick = { spacing = null }, label = { Text(tr("Wie Formatvorlage")) })
                    listOf(1.0f to "Einfach", 1.15f to "1,15", 1.5f to "1,5", 2.0f to "Doppelt").forEach { (z, label) ->
                        FilterChip(selected = spacing == z, onClick = { spacing = z }, label = { Text(tr(label)) })
                    }
                }
                Text(tr("Zeilen- und Seitenumbruch"), style = MaterialTheme.typography.titleSmall)
                OfficeCheck(tr("Mit nächstem Absatz zusammenhalten"), keep) { keep = it }
                OfficeCheck(tr("Seitenumbruch oberhalb"), pageBefore) { pageBefore = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val l = ((number(left) ?: 0f) * 10).roundToInt().coerceIn(0, 150)
                val f = ((number(first) ?: 0f) * 10).roundToInt().coerceIn(0, 80).let { if (firstKind == 0) 0 else if (firstKind == 2) -it else it }
                val b = before.toIntOrNull()?.coerceIn(0, 200)
                val a = after.toIntOrNull()?.coerceIn(0, 200)
                state.paragraph { q ->
                    val d = state.sheet.of(Styles.base(q.kind))
                    q.copy(
                        left = l, first = f, spacing = spacing, keep = keep, pageBefore = pageBefore,
                        // The value of the Formatvorlage is no own setting (the paragraph follows it when it changes).
                        before = b?.takeIf { it != d.before }, after = a?.takeIf { it != d.after },
                    )
                }
            }) { Text(tr("Übernehmen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

@Composable
internal fun OfficeCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked, onChange)
        Text(label)
    }
}

/**
 * *Format › Listen-Optionen …* (like Word's „Nummerierung definieren“ and Docs' „Listenoptionen“): the form of the numbers
 * or the bullet for the list at the cursor, a new start at a number or going on from the list before.
 */
@Composable
internal fun ListOptionsDialog(state: FlowState, onDismiss: () -> Unit) {
    val range = remember { state.listAround() }
    val t = state.active
    if (range == null || t == null) {
        TextAskInfo(tr("Listen-Optionen"), tr("Der Cursor steht in keiner Liste. Setze ihn in eine Aufzählung oder nummerierte Liste."), onDismiss)
        return
    }
    val head = t.rich.paras[range.first]
    val numbered = head.kind == ParaKind.NUMBERED
    var form by remember { mutableStateOf(head.listFormat) }
    var mode by remember { mutableStateOf(when { head.listContinue -> 2; head.listStart != null -> 1; else -> 0 }) }
    var start by remember { mutableStateOf((head.listStart ?: 1).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Listen-Optionen")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (numbered) tr("Nummerierung") else tr("Aufzählungszeichen"), style = MaterialTheme.typography.titleSmall)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = form == null, onClick = { form = null }, label = { Text(tr("Standard")) })
                    (if (numbered) ListStyles.NUMBERS else ListStyles.BULLETS).forEach { (id, look) ->
                        FilterChip(selected = form == id, onClick = { form = id }, label = { Text(if (id == "m") tr("Mehrstufig: {0}", look) else look) })
                    }
                }
                if (numbered) {
                    Text(tr("Beginnen"), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text(tr("Bei 1")) })
                        FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text(tr("Neu beginnen bei …")) })
                        FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text(tr("Nummerierung fortsetzen")) })
                    }
                    if (mode == 1) OutlinedTextField(start, { start = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true, label = { Text(tr("Erste Nummer")) }, modifier = Modifier.width(140.dp))
                    HintText(tr("Mehrstufig zählt die Ebenen mit (1., 1.1., 1.1.1.); Tab und Umschalt+Tab wechseln die Ebene."))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                state.setListOptions(form, if (mode == 1) start.toIntOrNull()?.coerceAtLeast(0) ?: 1 else null, mode == 2)
            }) { Text(tr("Übernehmen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

@Composable
private fun TextAskInfo(title: String, text: String, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onDismiss) { Text(tr("OK")) } },
)

/** Soft background colours for paragraphs (like Word's Schattierung), rrggbb. */
internal val SHADES = listOf("f2f2f2", "e5f1ff", "e8f5e9", "fff8e1", "fdecea", "f3e5f5")

/** A row of colour fields to choose from, the first one „ohne“. */
@Composable
private fun ColorChoice(colors: List<String>, current: String?, onPick: (String?) -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = current == null, onClick = { onPick(null) }, label = { Text(tr("Ohne")) })
        colors.forEach { c ->
            Box(
                Modifier.size(32.dp).background(Color(("FF$c").toLong(16)), RoundedCornerShape(6.dp))
                    .border(if (current == c) 3.dp else 1.dp, if (current == c) MaterialTheme.colorScheme.primary else outline, RoundedCornerShape(6.dp))
                    .clickable { onPick(c) }
                    .semantics { contentDescription = tr("Farbe {0}", "#" + c.uppercase()) },
            )
        }
    }
}

/** *Format › Rahmen und Schattierung …*: a frame around the paragraphs at the cursor and their background colour. */
@Composable
internal fun BorderDialog(state: FlowState, onDismiss: () -> Unit) {
    val p = remember { state.paraHere() }
    var border by remember { mutableStateOf(p.border) }
    var shade by remember { mutableStateOf(p.shade) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Rahmen und Schattierung")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OfficeCheck(tr("Rahmen um den Absatz"), border) { border = it }
                Text(tr("Schattierung"), style = MaterialTheme.typography.titleSmall)
                ColorChoice(SHADES, shade) { shade = it }
                HintText(tr("Aufeinanderfolgende Absätze mit gleichem Rahmen stehen in einem Kasten – wie in Word."))
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); state.paragraph { it.copy(border = border, shade = shade) } }) { Text(tr("Übernehmen")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** *Format › Seitenlayout …*: Papierformat, Seitenfarbe, Wasserzeichen and Silbentrennung of the whole document. */
@Composable
internal fun PageLayoutDialog(state: FlowState, onDismiss: () -> Unit) {
    val now = remember { DocMarkup.layout(state.markdown()) }
    var paper by remember { mutableStateOf(now.paper) }
    var color by remember { mutableStateOf(now.color) }
    var mark by remember { mutableStateOf(now.watermark) }
    var hyph by remember { mutableStateOf(now.hyphenation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Seitenlayout")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Papierformat"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocMarkup.PAPERS.forEach { (id, label) -> FilterChip(selected = paper == id, onClick = { paper = id }, label = { Text(label) }) }
                }
                Text(tr("Seitenfarbe"), style = MaterialTheme.typography.titleSmall)
                ColorChoice(DocMarkup.PAGE_COLORS, color) { color = it }
                Text(tr("Wasserzeichen"), style = MaterialTheme.typography.titleSmall)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mark.isBlank(), onClick = { mark = "" }, label = { Text(tr("Ohne")) })
                    DocMarkup.WATERMARKS.forEach { w -> FilterChip(selected = mark == w, onClick = { mark = w }, label = { Text(tr(w)) }) }
                }
                OutlinedTextField(mark, { mark = it.replace("\n", " ").take(40) }, singleLine = true, label = { Text(tr("Eigener Text")) }, modifier = Modifier.fillMaxWidth())
                OfficeCheck(tr("Silbentrennung im Blocksatz"), hyph) { hyph = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                state.applyMarkdown(DocMarkup.withLayout(state.markdown(), DocMarkup.Layout(paper, color, mark.trim(), hyph)))
            }) { Text(tr("Übernehmen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** *Format › Spalten …*: the text from the cursor on in one, two or three columns (until the next change). */
@Composable
internal fun ColumnsDialog(state: FlowState, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf(2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Spalten")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Eine", 2 to "Zwei", 3 to "Drei").forEach { (k, label) -> FilterChip(selected = n == k, onClick = { n = k }, label = { Text(tr(label)) }) }
                }
                HintText(tr("Ab dem Cursor läuft der Text in so vielen Spalten – in der Seitenansicht als Markierung, im PDF und in Word als echte Spalten. „Eine“ beendet die Spalten wieder."))
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); state.insertBlock(DocMarkup.columnsMarker(n)) }) { Text(tr("Einfügen")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** What a marker line of the page says in the editor (Querformat, Spalten), or null for other blocks. */
internal fun pageMarkerLabel(markdown: String): String? {
    DocMarkup.orientation(markdown)?.let { return if (it) tr("Querformat ab hier") else tr("Hochformat ab hier") }
    DocMarkup.columns(markdown)?.let { return if (it <= 1) tr("Spalten enden hier") else tr("{0} Spalten ab hier", it) }
    return null
}

/** Watermark of the pages in the Seitenansicht: grey, turned, in the middle of the sheet. */
internal fun DrawScope.drawWatermark(text: String, top: Float, full: Float, measured: androidx.compose.ui.text.TextLayoutResult) {
    val cx = size.width / 2f
    val cy = top + full / 2f
    rotate(-40f, Offset(cx, cy)) {
        drawText(measured, topLeft = Offset(cx - measured.size.width / 2f, cy - measured.size.height / 2f))
    }
}

/** Keeps the import of the colour type for the page colour used in FlowPages. */
internal fun pageColor(layout: DocMarkup.Layout): Color? = layout.color?.let { Color(("FF$it").toLong(16)) }

/** For tests: the list labels the editor shows for [r] (paragraph → label). */
internal fun editorListLabels(r: RichText): Map<Int, String> {
    val counter = ListStyles.Counter()
    val out = HashMap<Int, String>()
    r.paras.forEachIndexed { i, p ->
        if (!p.kind.list) { counter.end(); return@forEachIndexed }
        if (p.kind == ParaKind.CHECK) { out[i] = "check"; return@forEachIndexed }
        counter.item(p.kind == ParaKind.NUMBERED, p.indent, p, editor = true)?.let { out[i] = it }
    }
    return out
}
