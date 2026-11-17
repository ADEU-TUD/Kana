package io.github.thepytales.llmhub.creative

import androidx.compose.ui.geometry.Rect
import io.github.thepytales.llmhub.data.DesignElement
import io.github.thepytales.llmhub.data.ElementKind
import io.github.thepytales.llmhub.docs.SlideElement
import androidx.compose.ui.text.drawText

/**
 * The operations of the shared canvas (AP-50) as pure functions on the elements of a page: aligning and distributing,
 * groups and layers. Erstellen › Gestalten ([DesignEditorState]) and the slides ([SlideCanvas]) both use them, so an
 * element behaves the same in both.
 */
object CanvasOps {
    /**
     * Gleiche Abstände (AP-127): how far to move [b] along x ([horizontal]) or y so that its gap to the neighbour on one
     * side equals the gap on the other side, or the gap between the neighbour and the next one (PowerPoint's equal
     * spacing). Null when no such gap is within [snap] mm.
     */
    fun equalGap(b: androidx.compose.ui.geometry.Rect, others: List<androidx.compose.ui.geometry.Rect>, horizontal: Boolean, snap: Float): Float? {
        val row = others.filter { o -> if (horizontal) o.top < b.bottom && o.bottom > b.top else o.left < b.right && o.right > b.left }
        fun lo(r: androidx.compose.ui.geometry.Rect) = if (horizontal) r.left else r.top
        fun hi(r: androidx.compose.ui.geometry.Rect) = if (horizontal) r.right else r.bottom
        val before = row.filter { hi(it) <= lo(b) + snap }.maxByOrNull { hi(it) }
        val after = row.filter { lo(it) >= hi(b) - snap }.minByOrNull { lo(it) }
        val options = mutableListOf<Float>()
        if (before != null && after != null) options += ((lo(after) - hi(b)) - (lo(b) - hi(before))) / 2f
        if (before != null) row.filter { hi(it) <= lo(before) }.maxByOrNull { hi(it) }?.let { prev -> options += (lo(before) - hi(prev)) - (lo(b) - hi(before)) }
        if (after != null) row.filter { lo(it) >= hi(after) }.minByOrNull { lo(it) }?.let { next -> options += (hi(b) + (lo(next) - hi(after))).let { lo(after) - it } }
        return options.filter { kotlin.math.abs(it) <= snap }.minByOrNull { kotlin.math.abs(it) }
    }

    /** The gaps of [b] to its neighbours in the same row (or column), and the equal ones next to them, to show. */
    fun gaps(b: androidx.compose.ui.geometry.Rect, others: List<androidx.compose.ui.geometry.Rect>, horizontal: Boolean): List<io.github.thepytales.llmhub.creative.Spacing> {
        val row = others.filter { o -> if (horizontal) o.top < b.bottom && o.bottom > b.top else o.left < b.right && o.right > b.left }
        fun lo(r: androidx.compose.ui.geometry.Rect) = if (horizontal) r.left else r.top
        fun hi(r: androidx.compose.ui.geometry.Rect) = if (horizontal) r.right else r.bottom
        fun mid(a: androidx.compose.ui.geometry.Rect, c: androidx.compose.ui.geometry.Rect) =
            if (horizontal) (maxOf(a.top, c.top) + minOf(a.bottom, c.bottom)) / 2 else (maxOf(a.left, c.left) + minOf(a.right, c.right)) / 2
        val before = row.filter { hi(it) <= lo(b) + 0.01f }.maxByOrNull { hi(it) }
        val after = row.filter { lo(it) >= hi(b) - 0.01f }.minByOrNull { lo(it) }
        val out = mutableListOf<io.github.thepytales.llmhub.creative.Spacing>()
        val gapB = before?.let { lo(b) - hi(it) }
        val gapA = after?.let { lo(it) - hi(b) }
        fun same(x: Float?, y: Float?) = x != null && y != null && kotlin.math.abs(x - y) < 0.5f
        // Shown when two gaps are equal: these are the moments PowerPoint draws its measures.
        val prev = before?.let { bf -> row.filter { hi(it) <= lo(bf) }.maxByOrNull { hi(it) } }
        val next = after?.let { af -> row.filter { lo(it) >= hi(af) }.minByOrNull { lo(it) } }
        val prevGap = if (before != null && prev != null) lo(before) - hi(prev) else null
        val nextGap = if (after != null && next != null) lo(next) - hi(after) else null
        if (same(gapB, gapA) || same(gapB, prevGap) || same(gapA, nextGap)) {
            if (before != null && gapB!! > 0f) out += io.github.thepytales.llmhub.creative.Spacing(horizontal, hi(before), lo(b), mid(before, b))
            if (after != null && gapA!! > 0f) out += io.github.thepytales.llmhub.creative.Spacing(horizontal, hi(b), lo(after), mid(b, after))
            if (same(gapB, prevGap) && prev != null) out += io.github.thepytales.llmhub.creative.Spacing(horizontal, hi(prev), lo(before!!), mid(prev, before))
            if (same(gapA, nextGap) && next != null) out += io.github.thepytales.llmhub.creative.Spacing(horizontal, hi(after!!), lo(next), mid(after, next))
        }
        return out
    }

    /** [target] with the look of [from] (Format übertragen, AP-127): colours, line, corners, transparency, text and picture look. */
    fun withFormat(target: DesignElement, from: DesignElement): DesignElement {
        var t = target.copy(
            fill = from.fill, fill2 = from.fill2, gradientAngle = from.gradientAngle, stroke = from.stroke,
            strokeWidth = from.strokeWidth, radius = from.radius, opacity = from.opacity,
        )
        if (target.isText && from.isText || target.kind == from.kind) t = t.copy(
            fontSize = from.fontSize, bold = from.bold, medium = from.medium, italic = from.italic, color = from.color,
            align = from.align, valign = from.valign, lineHeight = from.lineHeight, padding = from.padding,
        )
        if (target.kind == ElementKind.IMAGE && from.kind == ElementKind.IMAGE) t = t.copy(
            brightness = from.brightness, contrast = from.contrast, saturation = from.saturation, grayscale = from.grayscale, blur = from.blur, mask = from.mask,
        )
        return t
    }

    /**
     * [elements] with [ids] aligned: one element (or one group) to the page of [pageW] × [pageH] mm, several to their
     * common bounds. [mode]: left, hcenter, right, top, vcenter, bottom; distributeH/distributeV spread three or more
     * evenly (a group counts as one block).
     */
    fun aligned(elements: List<DesignElement>, ids: Set<String>, mode: String, pageW: Float, pageH: Float): List<DesignElement> {
        val moving = elements.filter { it.id in ids && !it.locked }
        if (moving.isEmpty()) return elements
        // Blocks: a group moves as one, other elements alone.
        val blocks = moving.groupBy { it.group.ifEmpty { "#" + it.id } }.values.toList()
        val boundsOf = blocks.associateWith { DesignGeometry.bounds(it)!! }
        val area = if (blocks.size == 1) Rect(0f, 0f, pageW, pageH) else DesignGeometry.bounds(moving) ?: return elements
        val shift = HashMap<String, Pair<Float, Float>>()
        if (mode == "distributeH" || mode == "distributeV") {
            if (blocks.size < 3) return elements
            val horizontal = mode == "distributeH"
            val sorted = blocks.sortedBy { if (horizontal) boundsOf.getValue(it).left else boundsOf.getValue(it).top }
            val total = sorted.sumOf { (if (horizontal) boundsOf.getValue(it).width else boundsOf.getValue(it).height).toDouble() }.toFloat()
            val gap = ((if (horizontal) area.width else area.height) - total) / (sorted.size - 1)
            var pos = if (horizontal) area.left else area.top
            sorted.forEach { b ->
                val r = boundsOf.getValue(b)
                val d = pos - (if (horizontal) r.left else r.top)
                b.forEach { e -> shift[e.id] = if (horizontal) d to 0f else 0f to d }
                pos += (if (horizontal) r.width else r.height) + gap
            }
        } else {
            blocks.forEach { b ->
                val r = boundsOf.getValue(b)
                val d = when (mode) {
                    "left" -> area.left - r.left to 0f
                    "hcenter" -> area.center.x - r.center.x to 0f
                    "right" -> area.right - r.right to 0f
                    "top" -> 0f to area.top - r.top
                    "vcenter" -> 0f to area.center.y - r.center.y
                    "bottom" -> 0f to area.bottom - r.bottom
                    else -> 0f to 0f
                }
                b.forEach { e -> shift[e.id] = d }
            }
        }
        return elements.map { e -> shift[e.id]?.let { (dx, dy) -> e.copy(x = e.x + dx, y = e.y + dy) } ?: e }
    }

    /** [ids] with all members of their groups. */
    fun withGroups(elements: List<DesignElement>, ids: Set<String>): Set<String> {
        val groups = elements.filter { it.id in ids && it.group.isNotEmpty() }.map { it.group }.toSet()
        return if (groups.isEmpty()) ids else ids + elements.filter { it.group in groups }.map { it.id }
    }

    /** [ids] grouped under [groupId] (at least two elements, otherwise unchanged). */
    fun grouped(elements: List<DesignElement>, ids: Set<String>, groupId: String): List<DesignElement> =
        if (elements.count { it.id in ids } < 2) elements else elements.map { if (it.id in ids) it.copy(group = groupId) else it }

    fun ungrouped(elements: List<DesignElement>, ids: Set<String>): List<DesignElement> = elements.map { if (it.id in ids) it.copy(group = "") else it }

    /** Layers: [ids] one step (or [all] the way) to the front ([up]) or back. */
    fun reordered(elements: List<DesignElement>, ids: Set<String>, up: Boolean, all: Boolean): List<DesignElement> {
        val moving = elements.filter { it.id in ids }
        if (moving.isEmpty()) return elements
        val rest = elements.filter { it.id !in ids }
        if (all) return if (up) rest + moving else moving + rest
        val list = elements.toMutableList()
        val indices = if (up) list.indices.reversed() else list.indices
        for (i in indices) {
            if (list[i].id !in ids) continue
            val j = if (up) i + 1 else i - 1
            if (j in list.indices && list[j].id !in ids) { val t = list[j]; list[j] = list[i]; list[i] = t }
        }
        return list
    }
}

/**
 * The free elements of a slide (text boxes, pictures, shapes in cm, AP-16) as elements of the shared canvas (mm), so
 * the slides use the same operations as Gestalten ([CanvasOps]).
 */
object SlideCanvas {
    /** Size of a 16:9 slide in mm (33,867 × 19,05 cm). */
    const val W = 338.67f
    const val H = 190.5f

    fun toDesign(list: List<SlideElement>): List<DesignElement> = list.mapIndexed { i, s -> toDesign(s, "s$i") }

    /**
     * [s] as an element of the canvas. Lines and arrows of a slide are drawn inside their box (PowerPoint's horizontal
     * line and block arrow), so on the canvas they are boxes as well: they move, turn and resize at the frame.
     */
    fun toDesign(s: SlideElement, id: String): DesignElement {
        val kind = when (s.kind) {
            "bild" -> ElementKind.IMAGE
            "form" -> if (s.shape == "ellipse") ElementKind.ELLIPSE else ElementKind.RECT
            "tudshape" -> ElementKind.TUD_SHAPE
            "symbol" -> ElementKind.ICON
            "qr" -> ElementKind.QR
            "tabelle" -> ElementKind.TABLE
            else -> ElementKind.TEXT
        }
        return DesignElement(
            id = id, kind = kind, x = (s.x * 10).toFloat(), y = (s.y * 10).toFloat(), w = (s.w * 10).toFloat(), h = (s.h * 10).toFloat(),
            rotation = s.rotation.toFloat(), fill = s.fill, color = s.color, text = s.text, src = s.src, fontSize = s.size.toFloat(),
            bold = s.bold, align = when (s.align) { "mitte" -> "center"; "rechts" -> "right"; else -> "left" },
            radius = if (s.shape == "abgerundet") 3f else 0f, group = s.group, fill2 = s.fill2,
            icon = s.icon, flipX = s.flipX, rich = s.rich, rows = s.rows, header = s.header, fitMode = "contain",
        )
    }

    /** The slide's free elements as one page of the canvas (16:9, in mm), for [DesignEditorState]. */
    fun design(list: List<SlideElement>): io.github.thepytales.llmhub.data.Design = io.github.thepytales.llmhub.data.Design(
        id = "folie", title = "Folie", format = "folie", width = W, height = H,
        pages = listOf(io.github.thepytales.llmhub.data.DesignPage(id = "folie", elements = toDesign(list))),
    )

    /**
     * [design] (from [toDesign]) back onto the slide elements [old]: position, size, rotation, group, order and the text
     * typed on the canvas.
     */
    fun fromDesign(old: List<SlideElement>, design: List<DesignElement>): List<SlideElement> = design.mapNotNull { e ->
        val i = e.id.removePrefix("s").toIntOrNull() ?: return@mapNotNull null
        val s = old.getOrNull(i) ?: return@mapNotNull null
        fun cm(v: Float) = Math.round(v * 10.0) / 100.0
        s.copy(x = cm(e.x), y = cm(e.y), w = cm(e.w), h = cm(e.h), rotation = e.rotation.toDouble(), group = e.group, fill2 = e.fill2, text = e.text, rich = e.rich, rows = e.rows)
    }

    /** Indices of the canvas ids "s0", "s1" … and back. */
    fun indices(ids: Set<String>): Set<Int> = ids.mapNotNull { it.removePrefix("s").toIntOrNull() }.toSet()

    fun ids(indices: Set<Int>): Set<String> = indices.map { "s$it" }.toSet()

    /** The slide elements with [indices] (and their groups) grouped under [groupId], or taken apart with [apart]. */
    fun group(list: List<SlideElement>, indices: Set<Int>, groupId: String, apart: Boolean = false): List<SlideElement> {
        val d = toDesign(list)
        val ids = CanvasOps.withGroups(d, ids(indices))
        return fromDesign(list, if (apart) CanvasOps.ungrouped(d, ids) else CanvasOps.grouped(d, ids, groupId))
    }

    /**
     * The slide elements with [indices] aligned like in Gestalten: to the slide when it is one element or one group,
     * otherwise to their common bounds; distributeH/distributeV spread three or more evenly.
     */
    fun align(list: List<SlideElement>, indices: Set<Int>, mode: String): List<SlideElement> {
        val d = toDesign(list)
        val ids = CanvasOps.withGroups(d, ids(indices))
        return fromDesign(list, CanvasOps.aligned(d, ids, mode, W, H))
    }

    /** Layers of the slide elements like in Gestalten. */
    fun reorder(list: List<SlideElement>, indices: Set<Int>, up: Boolean, all: Boolean): List<SlideElement> {
        val d = toDesign(list)
        return fromDesign(list, CanvasOps.reordered(d, CanvasOps.withGroups(d, indices.map { "s$it" }.toSet()), up, all))
    }

    /** Indices of the elements in the group of element [index] (itself if it is in none). */
    fun groupOf(list: List<SlideElement>, index: Int): Set<Int> {
        val g = list.getOrNull(index)?.group.orEmpty()
        return if (g.isEmpty()) setOf(index) else list.indices.filter { list[it].group == g }.toSet()
    }
}

/**
 * Draws the gaps of [CanvasOps.gaps] while moving (AP-127): a line with ends and its size in mm, like PowerPoint's
 * intelligente Hilfslinien. [scale] = pixels per mm.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpacings(
    spacings: List<Spacing>,
    scale: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    color: androidx.compose.ui.graphics.Color,
) {
    val style = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
    spacings.forEach { s ->
        val a = if (s.horizontal) androidx.compose.ui.geometry.Offset(s.from * scale, s.across * scale) else androidx.compose.ui.geometry.Offset(s.across * scale, s.from * scale)
        val b = if (s.horizontal) androidx.compose.ui.geometry.Offset(s.to * scale, s.across * scale) else androidx.compose.ui.geometry.Offset(s.across * scale, s.to * scale)
        drawLine(color, a, b, 1.2f)
        val tick = if (s.horizontal) androidx.compose.ui.geometry.Offset(0f, 4f) else androidx.compose.ui.geometry.Offset(4f, 0f)
        drawLine(color, a - tick, a + tick, 1.2f)
        drawLine(color, b - tick, b + tick, 1.2f)
        val text = measurer.measure(s.label, style)
        val mid = (a + b) / 2f
        val box = androidx.compose.ui.geometry.Size(text.size.width + 8f, text.size.height + 2f)
        val at = mid - androidx.compose.ui.geometry.Offset(box.width / 2, if (s.horizontal) box.height + 3f else box.height / 2)
        drawRoundRect(color, at, box, androidx.compose.ui.geometry.CornerRadius(3f))
        drawText(text, topLeft = at + androidx.compose.ui.geometry.Offset(4f, 1f))
    }
}
