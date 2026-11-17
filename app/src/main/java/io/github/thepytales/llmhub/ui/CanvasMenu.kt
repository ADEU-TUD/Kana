package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** What the Rechtsklick-Menü of the canvas (AP-127) can do. */
enum class CanvasAction {
    CUT, COPY, PASTE, DUPLICATE, DELETE, GROUP, UNGROUP, FRONT, FORWARD, BACKWARD, BACK,
    ALIGN_LEFT, ALIGN_HCENTER, ALIGN_RIGHT, ALIGN_TOP, ALIGN_VCENTER, ALIGN_BOTTOM, DISTRIBUTE_H, DISTRIBUTE_V,
    COPY_FORMAT, PASTE_FORMAT, LOCK, UNLOCK, SELECT_ALL;

    /** The mode of [io.github.thepytales.llmhub.creative.CanvasOps.aligned] for the Ausrichten entries. */
    val alignMode: String? get() = when (this) {
        ALIGN_LEFT -> "left"; ALIGN_HCENTER -> "hcenter"; ALIGN_RIGHT -> "right"
        ALIGN_TOP -> "top"; ALIGN_VCENTER -> "vcenter"; ALIGN_BOTTOM -> "bottom"
        DISTRIBUTE_H -> "distributeH"; DISTRIBUTE_V -> "distributeV"
        else -> null
    }
}

/** What is selected when the menu opens: it shows only what fits (like PowerPoint and Canva). */
data class CanvasMenuContext(
    val selected: Int,
    val grouped: Boolean,
    val locked: Boolean,
    val canPaste: Boolean,
    val canPasteFormat: Boolean,
)

/**
 * Rechtsklick-Menü of Gestalten and the slides (AP-127), in plain words with the keys: on an element the clipboard,
 * Duplizieren, Löschen, Gruppieren, Ebenen, Ausrichten and Format übertragen; on an empty spot Einfügen and Alles
 * auswählen. [at] is where the right click was (pixels in the canvas).
 */
@Composable
fun CanvasContextMenu(at: Offset?, ctx: CanvasMenuContext, onDismiss: () -> Unit, canLock: Boolean = true, act: (CanvasAction) -> Unit) {
    if (at == null) return
    var align by remember(at) { mutableStateOf(false) }
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            @Composable
            fun item(label: String, keys: String, a: CanvasAction, enabled: Boolean = true) =
                MenuRow(tr(label), if (keys.isEmpty()) "" else tr(keys), enabled) { onDismiss(); act(a) }
            if (align) {
                MenuRow("‹ " + tr("Zurück"), "", true) { align = false }
                HorizontalDivider()
                item("Links ausrichten", "", CanvasAction.ALIGN_LEFT)
                item("Horizontal zentrieren", "", CanvasAction.ALIGN_HCENTER)
                item("Rechts ausrichten", "", CanvasAction.ALIGN_RIGHT)
                item("Oben ausrichten", "", CanvasAction.ALIGN_TOP)
                item("Vertikal zentrieren", "", CanvasAction.ALIGN_VCENTER)
                item("Unten ausrichten", "", CanvasAction.ALIGN_BOTTOM)
                HorizontalDivider()
                item("Waagrecht verteilen", "", CanvasAction.DISTRIBUTE_H, enabled = ctx.selected >= 3)
                item("Senkrecht verteilen", "", CanvasAction.DISTRIBUTE_V, enabled = ctx.selected >= 3)
                return@DropdownMenu
            }
            if (ctx.selected == 0) {
                item("Einfügen", "Strg+V", CanvasAction.PASTE, enabled = ctx.canPaste)
                item("Alles auswählen", "Strg+A", CanvasAction.SELECT_ALL)
                return@DropdownMenu
            }
            item("Ausschneiden", "Strg+X", CanvasAction.CUT, enabled = !ctx.locked)
            item("Kopieren", "Strg+C", CanvasAction.COPY)
            item("Einfügen", "Strg+V", CanvasAction.PASTE, enabled = ctx.canPaste)
            item("Duplizieren", "Strg+D", CanvasAction.DUPLICATE)
            item("Löschen", "Entf", CanvasAction.DELETE, enabled = !ctx.locked)
            HorizontalDivider()
            if (ctx.grouped) item("Gruppierung aufheben", "Strg+Umschalt+G", CanvasAction.UNGROUP)
            else item("Gruppieren", "Strg+G", CanvasAction.GROUP, enabled = ctx.selected >= 2)
            item("In den Vordergrund", "", CanvasAction.FRONT)
            item("Eine Ebene nach vorne", "", CanvasAction.FORWARD)
            item("Eine Ebene nach hinten", "", CanvasAction.BACKWARD)
            item("In den Hintergrund", "", CanvasAction.BACK)
            MenuRow(tr("Ausrichten") + " ›", "", true) { align = true }
            HorizontalDivider()
            item("Format übertragen", "Strg+Umschalt+C", CanvasAction.COPY_FORMAT, enabled = ctx.selected == 1)
            item("Format einfügen", "Strg+Umschalt+V", CanvasAction.PASTE_FORMAT, enabled = ctx.canPasteFormat)
            if (canLock) { if (ctx.locked) item("Entsperren", "", CanvasAction.UNLOCK) else item("Sperren", "", CanvasAction.LOCK) }
        }
    }
}

/** What the menu shows for the selection of [this]. */
fun io.github.thepytales.llmhub.creative.DesignEditorState.menuContext(canPaste: Boolean) = CanvasMenuContext(
    selected = selection.size,
    grouped = selected.any { it.group.isNotEmpty() },
    locked = selected.isNotEmpty() && selected.all { it.locked },
    canPaste = canPaste,
    canPasteFormat = formatClip != null && selected.any { !it.locked && it.id != formatClip?.id },
)

/** Runs an entry of the Rechtsklick-Menü on the canvas; [paste] is the screen's own Einfügen (clipboard of the system too). */
fun io.github.thepytales.llmhub.creative.DesignEditorState.perform(a: CanvasAction, paste: () -> Unit) {
    when (a) {
        CanvasAction.CUT -> { copy(); delete() }
        CanvasAction.COPY -> copy()
        CanvasAction.PASTE -> paste()
        CanvasAction.DUPLICATE -> duplicate()
        CanvasAction.DELETE -> delete()
        CanvasAction.GROUP -> group()
        CanvasAction.UNGROUP -> ungroup()
        CanvasAction.FRONT -> reorder(up = true, all = true)
        CanvasAction.FORWARD -> reorder(up = true, all = false)
        CanvasAction.BACKWARD -> reorder(up = false, all = false)
        CanvasAction.BACK -> reorder(up = false, all = true)
        CanvasAction.COPY_FORMAT -> copyFormat()
        CanvasAction.PASTE_FORMAT -> pasteFormat()
        CanvasAction.LOCK -> updateSelected { it.copy(locked = true) }
        CanvasAction.UNLOCK -> updateSelected { it.copy(locked = false) }
        CanvasAction.SELECT_ALL -> selection = current.elements.filter { !it.locked }.map { it.id }.toSet()
        else -> a.alignMode?.let(::align)
    }
}

/** Clipboard and Format übertragen of the slides' free elements (AP-127), kept while the app runs. */
internal object SlideClipboard {
    var items: List<io.github.thepytales.llmhub.docs.SlideElement> = emptyList()
    var format: io.github.thepytales.llmhub.docs.SlideElement? = null
}

/** Copies of [items] with groups of their own (a copy never joins the group of its original). */
internal fun slideCopies(items: List<io.github.thepytales.llmhub.docs.SlideElement>, dx: Double): List<io.github.thepytales.llmhub.docs.SlideElement> {
    val groups = items.map { it.group }.filter { it.isNotEmpty() }.distinct().associateWith { io.github.thepytales.llmhub.data.newDesignId() }
    return items.map { it.copy(x = it.x + dx, y = it.y + dx, group = groups[it.group].orEmpty()) }
}

/**
 * An entry of the Rechtsklick-Menü on a slide (AP-127): the new elements and selection, or null when nothing on the
 * slide changes (Kopieren, Format übertragen).
 */
internal fun slideAction(
    a: CanvasAction,
    list: List<io.github.thepytales.llmhub.docs.SlideElement>,
    sel: Set<Int>,
): Pair<List<io.github.thepytales.llmhub.docs.SlideElement>, Set<Int>>? {
    val chosen = sel.sorted().mapNotNull { list.getOrNull(it) }
    fun appended(copies: List<io.github.thepytales.llmhub.docs.SlideElement>) = (list + copies) to (list.size until list.size + copies.size).toSet()
    fun reselect(next: List<io.github.thepytales.llmhub.docs.SlideElement>) = next to next.indices.filter { next[it] in chosen }.toSet()
    return when (a) {
        CanvasAction.COPY -> { SlideClipboard.items = chosen; null }
        CanvasAction.CUT -> { SlideClipboard.items = chosen; list.filterIndexed { i, _ -> i !in sel } to emptySet() }
        CanvasAction.DELETE -> list.filterIndexed { i, _ -> i !in sel } to emptySet()
        CanvasAction.PASTE -> SlideClipboard.items.takeIf { it.isNotEmpty() }?.let { appended(slideCopies(it, 0.5)) }
        CanvasAction.DUPLICATE -> chosen.takeIf { it.isNotEmpty() }?.let { appended(slideCopies(it, 0.5)) }
        CanvasAction.GROUP -> io.github.thepytales.llmhub.creative.SlideCanvas.group(list, sel, io.github.thepytales.llmhub.data.newDesignId()) to sel
        CanvasAction.UNGROUP -> io.github.thepytales.llmhub.creative.SlideCanvas.group(list, sel, "", apart = true) to sel
        CanvasAction.FRONT -> reselect(io.github.thepytales.llmhub.creative.SlideCanvas.reorder(list, sel, up = true, all = true))
        CanvasAction.FORWARD -> reselect(io.github.thepytales.llmhub.creative.SlideCanvas.reorder(list, sel, up = true, all = false))
        CanvasAction.BACKWARD -> reselect(io.github.thepytales.llmhub.creative.SlideCanvas.reorder(list, sel, up = false, all = false))
        CanvasAction.BACK -> reselect(io.github.thepytales.llmhub.creative.SlideCanvas.reorder(list, sel, up = false, all = true))
        CanvasAction.COPY_FORMAT -> { SlideClipboard.format = chosen.singleOrNull(); null }
        CanvasAction.PASTE_FORMAT -> SlideClipboard.format?.let { f ->
            list.mapIndexed { i, e -> if (i in sel) e.copy(fill = f.fill, color = f.color, size = f.size, bold = f.bold, align = f.align) else e } to sel
        }
        CanvasAction.SELECT_ALL -> list to list.indices.toSet()
        CanvasAction.LOCK, CanvasAction.UNLOCK -> null
        else -> a.alignMode?.let { io.github.thepytales.llmhub.creative.SlideCanvas.align(list, sel, it) to sel }
    }
}
