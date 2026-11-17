package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.docs.DocKind
import io.github.thepytales.llmhub.docs.DocTab
import io.github.thepytales.llmhub.docs.SheetEngine
import io.github.thepytales.llmhub.docs.SlideDeck

/**
 * Verlauf der Schritte (AP-134 b): the last changes of an editor with names („Geschrieben: …“, „Zelle B3 geändert“,
 * „Clip gelöscht“), newest first. A click goes back to the state right after that step – as many times Rückgängig – so
 * Wiederholen goes on from there. The names come from comparing two states, so no editor has to name its steps.
 */
data class HistoryStep(val label: String, val at: Long)

object StepNames {
    /** A text for the list: one line, at most [max] characters. */
    fun short(s: String, max: Int = 28): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length > max) one.take(max - 1).trimEnd() + "…" else one
    }

    /** What changed between two texts: written, deleted or replaced, with the words. */
    fun text(old: String, new: String): String {
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val removed = old.substring(p, old.length - s)
        val added = new.substring(p, new.length - s)
        return when {
            removed.isEmpty() && added.isEmpty() -> tr("Formatiert")
            removed.isBlank() && added.isBlank() -> when {
                '\n' in added && '\n' !in removed -> tr("Absatz eingefügt")
                '\n' in removed && '\n' !in added -> tr("Absätze verbunden")
                else -> tr("Leerraum geändert")
            }
            removed.isEmpty() && added.isBlank() -> if ('\n' in added) tr("Absatz eingefügt") else tr("Leerzeichen eingefügt")
            added.isEmpty() && removed.isBlank() -> if ('\n' in removed) tr("Absätze verbunden") else tr("Leerzeichen gelöscht")
            removed.isEmpty() -> tr("Geschrieben: „{0}“", short(added))
            added.isEmpty() -> tr("Gelöscht: „{0}“", short(removed))
            else -> tr("Ersetzt: „{0}“ durch „{1}“", short(removed, 16), short(added, 16))
        }
    }

    /** Name of a block of its own in a document (Markdown). */
    fun blockKind(md: String): String {
        val t = md.trimStart()
        return when {
            t.startsWith("|") -> tr("Tabelle")
            t.startsWith("![") -> tr("Bild")
            t.startsWith("$$") -> tr("Formel")
            t.startsWith("```") -> tr("Codeblock")
            t.startsWith("<!--") -> tr("Seiteneinstellung")
            t.startsWith("---") || t.startsWith("***") -> tr("Trennlinie")
            else -> tr("Block")
        }
    }

    /** What changed between two states of the tabs of a document, table or presentation. */
    fun tabs(kind: DocKind, old: List<DocTab>, new: List<DocTab>): String {
        if (old.size < new.size) return if (kind == DocKind.SHEET) tr("Tabellenblatt hinzugefügt") else tr("Tab hinzugefügt")
        if (old.size > new.size) return if (kind == DocKind.SHEET) tr("Tabellenblatt gelöscht") else tr("Tab gelöscht")
        val i = old.indices.firstOrNull { old[it] != new[it] } ?: return tr("Änderung")
        val a = old[i]
        val b = new[i]
        if (a.id != b.id) return tr("Tabs umsortiert")
        if (a.title != b.title) return tr("Umbenannt in „{0}“", short(b.title))
        return when (kind) {
            DocKind.SHEET -> sheet(a.markdown, b.markdown)
            DocKind.SLIDES -> slides(a.markdown, b.markdown)
            else -> text(a.markdown, b.markdown)
        }
    }

    /** Cells, rows and columns of a sheet. */
    fun sheet(old: String, new: String): String {
        val a = runCatching { SheetEngine.grid(old) }.getOrDefault(emptyList())
        val b = runCatching { SheetEngine.grid(new) }.getOrDefault(emptyList())
        val colsA = a.maxOfOrNull { it.size } ?: 0
        val colsB = b.maxOfOrNull { it.size } ?: 0
        when {
            b.size > a.size -> return if (b.size - a.size == 1) tr("Zeile eingefügt") else tr("{0} Zeilen eingefügt", b.size - a.size)
            b.size < a.size -> return if (a.size - b.size == 1) tr("Zeile gelöscht") else tr("{0} Zeilen gelöscht", a.size - b.size)
            colsB > colsA -> return tr("Spalte eingefügt")
            colsB < colsA -> return tr("Spalte gelöscht")
        }
        val changed = a.indices.flatMap { r -> (0 until colsA).filter { c -> a[r].getOrElse(c) { "" } != b[r].getOrElse(c) { "" } }.map { c -> r to c } }
        // The head row is row 0 of the grid; the sheet shows it as row 1.
        return when {
            changed.isEmpty() -> tr("Formatiert")
            changed.size == 1 -> {
                val (r, c) = changed[0]
                val v = b[r].getOrElse(c) { "" }
                if (v.isEmpty()) tr("Zelle {0} geleert", SheetEngine.ref(r, c)) else tr("Zelle {0}: „{1}“", SheetEngine.ref(r, c), short(v, 20))
            }
            else -> tr("{0} Zellen geändert ({1} …)", changed.size, SheetEngine.ref(changed[0].first, changed[0].second))
        }
    }

    /** Slides of a presentation. */
    fun slides(old: String, new: String): String {
        val a = runCatching { SlideDeck.parse(old) }.getOrDefault(emptyList())
        val b = runCatching { SlideDeck.parse(new) }.getOrDefault(emptyList())
        if (b.size > a.size) return tr("Folie eingefügt")
        if (b.size < a.size) return tr("Folie gelöscht")
        val i = a.indices.firstOrNull { a[it] != b[it] } ?: return tr("Änderung")
        if (a.toSet() == b.toSet()) return tr("Folien umsortiert")
        val x = a[i]
        val y = b[i]
        val what = when {
            x.title != y.title -> tr("Titel")
            x.body != y.body || x.right != y.right -> tr("Text")
            x.notes != y.notes -> tr("Notizen")
            x.elements != y.elements -> tr("Elemente")
            x.image != y.image || x.imageAlt != y.imageAlt -> tr("Bild")
            x.layout != y.layout -> tr("Layout")
            else -> tr("Gestaltung")
        }
        return tr("Folie {0}: {1} geändert", i + 1, what)
    }

    /** „vor 2 Min.“ for the list. */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            at <= 0 -> ""
            s < 10 -> tr("gerade eben")
            s < 60 -> tr("vor {0} s", s)
            s < 3600 -> tr("vor {0} Min.", s / 60)
            else -> tr("vor {0} Std.", s / 3600)
        }
    }
}

/**
 * The Verlauf as a dialog: *Jetzt* on top, then the steps, at the bottom *Wie beim Öffnen*. [onJump] gets how many steps
 * to go back (0 = nothing).
 */
@Composable
fun HistoryDialog(steps: List<HistoryStep>, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
        title = { Text(tr("Verlauf")) },
        text = {
            Column(Modifier.widthIn(min = 300.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (steps.isEmpty()) {
                    Text(tr("Noch keine Schritte – jede Änderung erscheint hier."))
                } else {
                    Text(
                        tr("Ein Klick geht zurück bis direkt nach diesem Schritt; Wiederholen (Strg+Y) holt die Schritte danach zurück."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    steps.forEachIndexed { i, s -> HistoryRow(s.label, StepNames.ago(s.at), current = i == 0) { onJump(i); onDismiss() } }
                    HistoryRow(tr("Wie vor dem ersten Schritt"), "", current = false) { onJump(steps.size); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
    )
}

@Composable
private fun HistoryRow(label: String, ago: String, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (current) "●" else "○", color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
            if (current) Text(tr("Jetzt"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (ago.isNotEmpty()) Text(ago, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
