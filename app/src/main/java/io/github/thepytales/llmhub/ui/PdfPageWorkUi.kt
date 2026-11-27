package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thepytales.llmhub.docs.PdfBookmarks
import io.github.thepytales.llmhub.docs.PdfOutlineEntry
import io.github.thepytales.llmhub.docs.PdfStamp
import io.github.thepytales.llmhub.docs.PdfTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2

// ── Wasserzeichen, Kopf- und Fußzeile (AP-137 b) ─────────────────────────────────────────────────────────────────────

/** What the dialog holds: one watermark and the six places of head and foot. */
internal data class StampDraft(
    val mark: String = "",
    val markColor: Int = 0x9E9E9E,
    val opacity: Float = 0.22f,
    val imagePath: String? = null,
    val edges: Map<PdfStamp.Place, String> = emptyMap(),
    val pages: String = "",
) {
    fun stamps(): List<PdfStamp> {
        val list = mutableListOf<PdfStamp>()
        if (imagePath != null) list += PdfStamp("", PdfStamp.Place.CENTER, imagePath = imagePath, opacity = opacity, pages = pages)
        else if (mark.isNotBlank()) list += PdfStamp(mark.trim(), PdfStamp.Place.DIAGONAL, opacity = opacity, color = markColor, pages = pages)
        edges.forEach { (place, text) -> if (text.isNotBlank()) list += PdfStamp(text.trim(), place, opacity = 1f, color = 0x404040, pages = pages, bold = false) }
        return list
    }

    companion object {
        fun of(stamps: List<PdfStamp>): StampDraft {
            val mark = stamps.firstOrNull { !it.place.edge }
            return StampDraft(
                mark = mark?.text.orEmpty(), markColor = mark?.color ?: 0x9E9E9E, opacity = mark?.opacity ?: 0.22f,
                imagePath = mark?.imagePath, edges = stamps.filter { it.place.edge }.associate { it.place to it.text },
                pages = stamps.firstOrNull()?.pages.orEmpty(),
            )
        }
    }
}

private val MARK_COLORS = listOf(0x9E9E9E to trLater("Grau"), 0xC62828 to trLater("Rot"), 0x1565C0 to trLater("Blau"))

/**
 * Wasserzeichen und Kopfzeile: „Entwurf“, „Vertraulich“, „Kopie“, eigener Text oder ein Bild über die Seite; Text in
 * den sechs Stellen von Kopf- und Fußzeile mit Platzhaltern ({n}, {N}, {datum}, {datei}); nur bestimmte Seiten. The
 * preview shows a page with all of it. [confirm] is the label of the main button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfStampDialog(initial: List<PdfStamp>, confirm: String, onDismiss: () -> Unit, onApply: (List<PdfStamp>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(StampDraft.of(initial)) }
    val presets = listOf(tr("ENTWURF") to tr("Entwurf"), tr("VERTRAULICH") to tr("Vertraulich"), tr("KOPIE") to tr("Kopie"))
    var custom by remember { mutableStateOf(d.mark.isNotBlank() && d.mark !in presets.map { it.first }) }
    val pickImage = rememberImageBytesPicker { bytes ->
        if (bytes == null) { Notices.error(tr("Das Bild ist nicht lesbar.")); return@rememberImageBytesPicker }
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                File(context.filesDir, "pdf-stempel").apply { mkdirs() }.let { dir -> File(dir, "bild-${System.currentTimeMillis()}." + if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "jpg" else "png").also { it.writeBytes(bytes) } }
            }
            d = d.copy(imagePath = f.absolutePath, mark = "")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Wasserzeichen und Kopfzeile")) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StampPreview(d, Modifier.align(Alignment.CenterHorizontally).width(150.dp))
                Text(tr("Wasserzeichen"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = d.mark.isBlank() && d.imagePath == null && !custom, onClick = { custom = false; d = d.copy(mark = "", imagePath = null) }, label = { Text(tr("Keins")) })
                    presets.forEachIndexed { k, (value, label) ->
                        FilterChip(selected = !custom && d.imagePath == null && d.mark == value, onClick = {
                            custom = false
                            d = d.copy(mark = value, imagePath = null, markColor = if (k == 1) 0xC62828 else d.markColor.takeIf { it != 0xC62828 } ?: 0x9E9E9E)
                        }, label = { Text(label) })
                    }
                    FilterChip(selected = custom && d.imagePath == null, onClick = { custom = true; d = d.copy(imagePath = null) }, label = { Text(tr("Eigener Text")) })
                    FilterChip(selected = d.imagePath != null, onClick = pickImage, label = { Text(tr("Bild …")) })
                }
                if (custom && d.imagePath == null) OutlinedTextField(d.mark, { d = d.copy(mark = it) }, label = { Text(tr("Text des Wasserzeichens")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (d.mark.isNotBlank() || d.imagePath != null) {
                    if (d.imagePath == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Farbe"), style = MaterialTheme.typography.bodySmall)
                        MARK_COLORS.forEach { (rgb, label) -> FilterChip(selected = d.markColor == rgb, onClick = { d = d.copy(markColor = rgb) }, label = { Text(tr(label)) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Deckkraft") + " ${(d.opacity * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                        Slider(d.opacity, { d = d.copy(opacity = it) }, valueRange = 0.08f..0.8f, modifier = Modifier.weight(1f))
                    }
                }
                Text(tr("Kopf- und Fußzeile"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip(onClick = { d = d.copy(edges = d.edges + (PdfStamp.Place.FOOTER_CENTER to tr("Seite {n} von {N}"))) }, label = { Text(tr("Seite x von y unten")) })
                    AssistChip(onClick = { d = d.copy(edges = d.edges + (PdfStamp.Place.HEADER_RIGHT to "{datum}")) }, label = { Text(tr("Datum oben rechts")) })
                    AssistChip(onClick = { d = d.copy(edges = d.edges + (PdfStamp.Place.HEADER_LEFT to "{datei}")) }, label = { Text(tr("Dateiname oben links")) })
                    AssistChip(onClick = { d = d.copy(edges = d.edges + (PdfStamp.Place.HEADER_CENTER to tr("Vertraulich"))) }, label = { Text(tr("„Vertraulich“ oben")) })
                }
                PdfStamp.Place.entries.filter { it.edge }.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { place ->
                            OutlinedTextField(
                                d.edges[place].orEmpty(), { v -> d = d.copy(edges = d.edges + (place to v)) },
                                label = { Text(tr(place.label), maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HintText(tr("Platzhalter: {n} Seite, {N} Seitenzahl, {datum} heute, {datei} Dateiname."))
                OutlinedTextField(d.pages, { d = d.copy(pages = it) }, label = { Text(tr("Nur auf Seiten (leer: alle), z. B. 2-")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(enabled = d.stamps().isNotEmpty(), onClick = { onApply(d.stamps()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** A page in A4 with the watermark and the texts of head and foot, as they will stand (page 1 of 3). */
@Composable
private fun StampPreview(d: StampDraft, modifier: Modifier = Modifier) {
    val stamps = d.stamps()
    BoxWithConstraints(modifier.aspectRatio(0.707f).background(Color.White, RoundedCornerShape(2.dp)).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(2.dp))) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val scale = maxWidth.value / 595f
        // A few grey lines stand for the text of the page.
        Column(Modifier.fillMaxSize().padding(horizontal = (maxWidth.value * 0.12f).dp, vertical = (maxHeight.value * 0.12f).dp), verticalArrangement = Arrangement.spacedBy((maxHeight.value * 0.025f).dp)) {
            repeat(9) { k -> Box(Modifier.fillMaxWidth(if (k % 3 == 2) 0.6f else 1f).height(3.dp).background(Color(0xFFE0E0E0))) }
        }
        stamps.forEach { s ->
            val text = s.resolve(1, 3, PdfStamp.today(), "Beispiel")
            val color = Color(0xFF000000 or s.color.toLong()).copy(alpha = s.opacity.coerceIn(0.05f, 1f))
            when {
                s.imagePath != null -> Box(Modifier.align(Alignment.Center).fillMaxWidth(0.45f).aspectRatio(1.6f).alpha(s.opacity).background(Color(0xFF90A4AE), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    Text(tr("Bild"), color = Color.White, fontSize = 10.sp)
                }
                !s.place.edge -> Text(
                    text, color = color, fontWeight = FontWeight.Bold, maxLines = 1,
                    fontSize = (maxWidth.value * 0.72f * 1.414f / (text.length * 0.68f).coerceAtLeast(1f)).coerceIn(8f, maxWidth.value * 0.2f).sp,
                    modifier = Modifier.align(Alignment.Center).rotate(if (s.place == PdfStamp.Place.DIAGONAL) -Math.toDegrees(atan2(h, w).toDouble()).toFloat() else 0f),
                )
                else -> {
                    val align = when (s.place) {
                        PdfStamp.Place.HEADER_LEFT -> Alignment.TopStart
                        PdfStamp.Place.HEADER_CENTER -> Alignment.TopCenter
                        PdfStamp.Place.HEADER_RIGHT -> Alignment.TopEnd
                        PdfStamp.Place.FOOTER_LEFT -> Alignment.BottomStart
                        PdfStamp.Place.FOOTER_CENTER -> Alignment.BottomCenter
                        else -> Alignment.BottomEnd
                    }
                    Text(text, color = color, fontSize = (9f * scale).coerceAtLeast(6f).sp, maxLines = 1, modifier = Modifier.align(align).padding(horizontal = (36 * scale).dp, vertical = (22 * scale).dp))
                }
            }
        }
    }
}

// ── Lesezeichen (AP-137 d) ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * Lesezeichen bearbeiten: anlegen (auf der Seite [currentPage], 0-based), umbenennen, Seite ändern, verschachteln
 * (Einrücken/Ausrücken), verschieben, löschen und aus den Überschriften erzeugen. [onApply] writes them into the PDF.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfBookmarksDialog(file: File, pages: Int, currentPage: Int, onDismiss: () -> Unit, onApply: (List<PdfOutlineEntry>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<PdfOutlineEntry>() }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(file) {
        val found = withContext(Dispatchers.IO) { runCatching { PdfTools.outline(context, file) }.getOrDefault(emptyList()) }
        list.clear(); list.addAll(found.map { it.copy(page = it.page.coerceAtLeast(1)) }); loaded = true
    }
    fun set(next: List<PdfOutlineEntry>, sel: Int?) { list.clear(); list.addAll(next); selected = sel?.takeIf { it in next.indices } }
    val state = rememberLazyListState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Lesezeichen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        val at = selected?.let { PdfBookmarks.subtreeEnd(list, it) } ?: list.size
                        val level = selected?.let { list[it].level } ?: 0
                        set(list.toList().subList(0, at) + PdfOutlineEntry(tr("Neues Lesezeichen"), currentPage + 1, level) + list.toList().subList(at, list.size), at)
                        scope.launch { state.animateScrollToItem(at) }
                    }) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text(tr("Neu auf Seite {0}", currentPage + 1)) }
                    OutlinedButton(enabled = !working, onClick = {
                        working = true
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { runCatching { PdfTools.textRuns(context, file).let { (runs, sizes) -> PdfBookmarks.fromHeadings(runs, sizes) } }.getOrDefault(emptyList()) }
                            working = false
                            if (found.isEmpty()) Notices.info(tr("Keine Überschriften gefunden – bei Scans zuerst die Texterkennung nutzen."))
                            else { set(found, null); Notices.info(tr("{0} Überschriften gefunden – prüfen und übernehmen.", found.size)) }
                        }
                    }) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text(tr("Aus Überschriften erzeugen")) }
                }
                if (loaded && list.isEmpty()) HintText(tr("Noch keine Lesezeichen. Sie erscheinen in jedem PDF-Programm als Inhaltsverzeichnis an der Seite."))
                LazyColumn(state = state, modifier = Modifier.heightIn(max = 380.dp).fillMaxWidth()) {
                    itemsIndexed(list) { i, e ->
                        val sel = selected == i
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { selected = if (sel) null else i }
                                .padding(start = (e.level * 18).dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(e.title, { v -> list[i] = e.copy(title = v) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(tr("Titel")) })
                            Spacer(Modifier.size(6.dp))
                            OutlinedTextField(
                                e.page.toString(), { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { p -> list[i] = e.copy(page = p.coerceIn(1, pages)) } },
                                singleLine = true, modifier = Modifier.width(76.dp), label = { Text(tr("Seite")) },
                            )
                            if (sel) {
                                IconButton(onClick = { set(PdfBookmarks.outdent(list, i), i) }, enabled = e.level > 0) { Icon(Icons.AutoMirrored.Outlined.FormatIndentDecrease, tr("Ausrücken")) }
                                IconButton(onClick = { set(PdfBookmarks.indent(list, i), i) }, enabled = i > 0 && e.level <= list[i - 1].level) { Icon(Icons.AutoMirrored.Outlined.FormatIndentIncrease, tr("Einrücken (Unterpunkt)")) }
                                IconButton(onClick = { val next = PdfBookmarks.move(list, i, up = true); set(next, next.indexOf(e)) }) { Icon(Icons.Outlined.ArrowUpward, tr("Nach oben")) }
                                IconButton(onClick = { val next = PdfBookmarks.move(list, i, up = false); set(next, next.indexOf(e)) }) { Icon(Icons.Outlined.ArrowDownward, tr("Nach unten")) }
                                IconButton(onClick = { set(PdfBookmarks.delete(list, i), null) }) { Icon(Icons.Outlined.Delete, tr("Löschen")) }
                            }
                        }
                    }
                }
                if (list.isNotEmpty()) HintText(tr("Klick auf ein Lesezeichen zeigt Einrücken, Verschieben und Löschen."))
            }
        },
        confirmButton = { Button(enabled = loaded, onClick = { onApply(PdfBookmarks.normalized(list.toList(), pages)) }) { Text(tr("Übernehmen")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}
