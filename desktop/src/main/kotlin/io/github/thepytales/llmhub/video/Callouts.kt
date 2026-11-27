package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.data.Design
import io.github.thepytales.llmhub.data.DesignElement
import io.github.thepytales.llmhub.data.DesignFormat
import io.github.thepytales.llmhub.data.DesignPage
import io.github.thepytales.llmhub.data.ElementKind
import io.github.thepytales.llmhub.ui.trLater
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Hervorhebungen für Unterrichtsvideos (AP-129 e): a frame, a circle, an arrow, a highlighter or a spot on the picture
 * – „hier klicken“ in a screencast, the part of an apparatus that matters – with an optional short label. They are text
 * overlays of another style ([TextOverlay.style] = "rahmen" …) on the title track: placed by [TextOverlay.x] … (shares
 * of the frame), faded in and out like the titles, drawn with the renderer of „Erstellen“ into a transparent picture
 * that ffmpeg lays over the film. The titles in the TUD design ([VideoTitles.design]) are not touched.
 */
object Callouts {
    const val FRAME = "rahmen"
    const val CIRCLE = "kreis"
    const val ARROW = "pfeil"
    const val MARKER = "markierung"
    const val SPOT = "spot"

    val STYLES = listOf(
        FRAME to trLater("Rahmen"), CIRCLE to trLater("Kreis"), ARROW to trLater("Pfeil"), MARKER to trLater("Markierung"), SPOT to trLater("Spot"),
    )

    /** Colours offered: red (default), yellow, blue, green, white. */
    val COLORS = listOf("#E53935" to trLater("Rot"), "#FFD43B" to trLater("Gelb"), "#2F7DE1" to trLater("Blau"), "#2E9D4F" to trLater("Grün"), "#FFFFFF" to trLater("Weiß"))

    fun isCallout(style: String) = STYLES.any { it.first == style }

    fun label(style: String): String? = STYLES.firstOrNull { it.first == style }?.second

    /** Colour of [o]: its own, else yellow for the highlighter and red for the rest. */
    fun colorOf(o: TextOverlay): String = o.color.ifBlank { if (o.style == MARKER) "#FFD43B" else COLORS.first().first }

    /** A new callout of [style] at [at] for 4 s in the middle of the picture (an arrow points at the middle). */
    fun create(style: String, at: Double, text: String = ""): TextOverlay = when (style) {
        ARROW -> TextOverlay(style = ARROW, text = text, startSec = at, durationSec = 4.0, x = 0.5f, y = 0.5f, w = 0.16f, h = 0.2f)
        MARKER -> TextOverlay(style = MARKER, text = text, startSec = at, durationSec = 4.0, x = 0.5f, y = 0.5f, w = 0.4f, h = 0.08f)
        else -> TextOverlay(style = style, text = text, startSec = at, durationSec = 4.0, x = 0.5f, y = 0.5f, w = 0.3f, h = 0.22f)
    }

    /** „oben links“, „rechts“, „Mitte“ … → the centre of a callout (for Kana). */
    fun place(where: String?): Pair<Float, Float> {
        val w = where.orEmpty().lowercase()
        val x = when { "links" in w || "left" in w -> 0.22f; "rechts" in w || "right" in w -> 0.78f; else -> 0.5f }
        val y = when { "oben" in w || "top" in w -> 0.25f; "unten" in w || "bottom" in w -> 0.75f; else -> 0.5f }
        return x to y
    }

    private const val MM = DesignFormat.MM_PER_PX

    /** Whether text on [hex] should be dark (yellow, white). */
    private fun light(hex: String): Boolean {
        val v = hex.removePrefix("#").toLongOrNull(16) ?: return false
        val r = (v shr 16 and 0xff); val g = (v shr 8 and 0xff); val b = (v and 0xff)
        return 0.299 * r + 0.587 * g + 0.114 * b > 160
    }

    /** The picture of callout [o] for a [width]×[height] frame (transparent around it). */
    fun design(o: TextOverlay, width: Int, height: Int): Design {
        val s = minOf(width, height) / 1080f
        val fw = width * MM
        val fh = height * MM
        fun px(v: Float) = v * s * MM
        val color = colorOf(o)
        val cx = o.x.coerceIn(0f, 1f) * fw
        val cy = o.y.coerceIn(0f, 1f) * fh
        val bw = (o.w.coerceIn(0.02f, 1f)) * fw
        val bh = (o.h.coerceIn(0.02f, 1f)) * fh
        val stroke = px(9f)
        val els = mutableListOf<DesignElement>()
        // The box the label goes next to.
        var top = cy - bh / 2
        var bottom = cy + bh / 2
        when (o.style) {
            FRAME -> els += DesignElement(kind = ElementKind.RECT, x = cx - bw / 2, y = cy - bh / 2, w = bw, h = bh, stroke = color, strokeWidth = stroke, radius = px(14f))
            CIRCLE -> els += DesignElement(kind = ElementKind.ELLIPSE, x = cx - bw / 2, y = cy - bh / 2, w = bw, h = bh, stroke = color, strokeWidth = stroke)
            MARKER -> els += DesignElement(kind = ElementKind.RECT, x = cx - bw / 2, y = cy - bh / 2, w = bw, h = bh, fill = color, opacity = 0.38f, radius = px(6f))
            SPOT -> {
                // Everything around the box darker: one rectangle around it with a stroke wide enough to reach past the
                // edges of the frame (the renderer has no holes; four rectangles left fine seams where they met).
                val l = (cx - bw / 2).coerceIn(0f, fw); val r = (cx + bw / 2).coerceIn(0f, fw)
                val t = (cy - bh / 2).coerceIn(0f, fh); val b = (cy + bh / 2).coerceIn(0f, fh)
                val wide = 2 * maxOf(fw, fh)
                els += DesignElement(kind = ElementKind.RECT, x = l - wide / 2, y = t - wide / 2, w = r - l + wide, h = b - t + wide, stroke = "#000000", strokeWidth = wide, opacity = 0.6f)
                els += DesignElement(kind = ElementKind.RECT, x = l, y = t, w = r - l, h = b - t, stroke = "#FFFFFF", strokeWidth = px(3f), radius = px(4f), opacity = 0.8f)
            }
            ARROW -> {
                // The tip at (x, y), the tail [w] to the left and [h] below (negative: the other side): a straight arrow
                // drawn horizontally and turned around its middle.
                val tx = cx - o.w * fw
                val ty = cy + o.h * fh
                val len = hypot(cx - tx, cy - ty).coerceAtLeast(px(20f))
                val angle = Math.toDegrees(atan2((cy - ty).toDouble(), (cx - tx).toDouble())).toFloat()
                val mx = (cx + tx) / 2; val my = (cy + ty) / 2
                els += DesignElement(kind = ElementKind.ARROW, x = mx - len / 2, y = my, w = len, h = 0.001f, rotation = angle, stroke = color, strokeWidth = px(12f))
                top = minOf(cy, ty); bottom = maxOf(cy, ty)
            }
        }
        // The label: a short text on the colour, below the box (above it when the box is low in the picture).
        if (o.text.isNotBlank()) {
            val lw = minOf(fw - px(40f), maxOf(bw, px(420f)))
            val lh = px(76f)
            val below = bottom + px(16f) + lh < fh - px(20f)
            val ly = if (below) bottom + px(16f) else (top - px(16f) - lh).coerceAtLeast(px(10f))
            val lx = (cx - lw / 2).coerceIn(px(20f), fw - lw - px(20f))
            val fill = if (o.style == SPOT) "#000000" else color
            val label = DesignElement(
                kind = ElementKind.TEXT, x = lx, y = ly, w = lw, h = lh, text = o.text.trim(), fontSize = 40f * s * 0.75f, bold = true,
                color = if (light(fill)) "#111111" else "#FFFFFF", fill = fill, padding = px(14f), radius = px(10f), align = "center", valign = "middle", fit = true,
            )
            // Only as wide as its text (with the padding), centred where it was.
            val laid = io.github.thepytales.llmhub.creative.DesignText.layout(label)
            val textW = (0 until laid.layout.lineCount).maxOfOrNull { laid.layout.getLineRight(it) - laid.layout.getLineLeft(it) }?.div(io.github.thepytales.llmhub.creative.DesignText.REF) ?: lw
            val w = minOf(lw, textW + 2 * label.padding + px(8f))
            els += label.copy(x = (cx - w / 2).coerceIn(px(20f), fw - w - px(20f)), w = w)
        }
        return Design(id = "video-callout-${o.id}", title = o.text, format = "video", width = fw, height = fh, pages = listOf(DesignPage(background = "", elements = els)))
    }

    /** The picture of any overlay: a callout here, a title in the TUD design as before ([VideoTitles.design]). */
    fun designOf(o: TextOverlay, width: Int, height: Int): Design = if (isCallout(o.style)) design(o, width, height) else VideoTitles.design(o, width, height)
}
