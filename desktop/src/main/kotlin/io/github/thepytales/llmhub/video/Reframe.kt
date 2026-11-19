package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.ui.trLater
import java.io.File
import kotlin.math.abs

/**
 * Hochformat und Quadrat mit Bildausschnitt, der der Person folgt (AP-129, „Auto-Reframe“ wie in CapCut, Clipchamp und
 * DaVinci): a landscape recording becomes a 9:16, 1:1 or 4:5 clip whose window follows the main face – found on this
 * device with the face detection of AP-54. The window is the reframe of [ClipFx.aspect] with the zoom keys in source
 * time, so trimming, splitting and the preview keep it; without a face it stays in the middle.
 */
object Reframe {
    /** A target format: project size and width/height of the window. */
    data class Format(val id: String, val label: String, val width: Int, val height: Int) {
        val ratio: Double get() = width.toDouble() / height
    }

    val FORMATS = listOf(
        Format("9:16", trLater("Hochformat 9:16 (Reels, Shorts, Story)"), 1080, 1920),
        Format("1:1", trLater("Quadrat 1:1"), 1080, 1080),
        Format("4:5", trLater("4:5 (Feed)"), 1080, 1350),
    )

    fun format(id: String?): Format = FORMATS.firstOrNull { it.id == id?.trim() } ?: FORMATS.first()

    /**
     * The main face of each sample: the largest at the start, then the one nearest to where the window was (a person
     * who turns away for a moment does not make the window jump to someone in the background).
     */
    internal fun mainFaces(samples: List<Pair<Double, List<Face>>>): List<Pair<Double, Face>> {
        val out = mutableListOf<Pair<Double, Face>>()
        var last: Face? = null
        for ((t, faces) in samples.sortedBy { it.first }) {
            if (faces.isEmpty()) continue
            val f = last?.let { l -> faces.minByOrNull { abs(it.x - l.x) + abs(it.y - l.y) - it.w * 0.5f } } ?: faces.maxByOrNull { it.w * it.h }!!
            out += t to f
            last = f
        }
        return out
    }

    /**
     * Keys of the window (source time) for a source of [sourceAspect] (width/height) cut to [ratio]: centred on the main
     * face, smoothed over [smooth] seconds and held still while the face stays within the middle [deadZone] of the
     * window (a calm picture as from a camera operator, no wobble with every nod). The window is as high as the
     * picture ([Key.w] = 1); the vertical position follows only when the window is less high than the picture (a 9:16
     * window from a 9:16 source, or a wide window).
     */
    fun follow(samples: List<Pair<Double, List<Face>>>, sourceAspect: Double, ratio: Double, from: Double, smooth: Double = 1.2, deadZone: Double = 0.3): List<Key> {
        val main = mainFaces(samples)
        if (main.isEmpty()) return listOf(Key(from, 0.5f, 0.5f, 1f, 1f))
        // Share of the source width the window takes (1 when the source is narrower than the window).
        val share = (ratio / sourceAspect).coerceIn(0.05, 1.0)
        fun clampX(x: Double) = x.coerceIn(share / 2, 1 - share / 2)
        // Moving average of the face centre.
        val smoothed = main.map { (t, _) ->
            val near = main.filter { abs(it.first - t) <= smooth / 2 }
            t to near.sumOf { it.second.x.toDouble() } / near.size
        }
        val keys = mutableListOf<Key>()
        var cx = clampX(smoothed.first().second)
        keys += Key(minOf(from, smoothed.first().first), cx.toFloat(), 0.5f, 1f, 1f)
        for ((t, x) in smoothed.drop(1)) {
            // Moves only when the face leaves the middle of the window, then just far enough to have it there again.
            val limit = share * deadZone / 2
            val next = when {
                x > cx + limit -> clampX(x - limit)
                x < cx - limit -> clampX(x + limit)
                else -> cx
            }
            if (abs(next - cx) > 1e-4) {
                // Hold until just before the move (otherwise the window would drift from the last key all the time).
                if (keys.last().t < t - 0.5) keys += keys.last().copy(t = t - 0.5)
                cx = next
                keys += Key(t, cx.toFloat(), 0.5f, 1f, 1f)
            }
        }
        return Faces.simplify(keys, 0.004f)
    }

    /**
     * The project in [format]: the size changes, and every clip of V1 whose source is wider than the window gets the
     * reframe with the keys from [keys] (clip id → keys; a clip without keys stays in the middle). Pictures on V2 …,
     * titles and subtitles stay where they are (titles and subtitles are laid out for the new size anyway).
     */
    fun apply(p: VideoProject, format: Format, keys: Map<String, List<Key>>, sourceAspect: (VideoClip) -> Double?): VideoProject {
        val clips = p.clips.map { c ->
            val a = sourceAspect(c) ?: return@map c
            if (a <= format.ratio + 0.02) return@map c.copy(fx = c.fx.copy(aspect = 0f, vertical = false))
            c.copy(fx = c.fx.copy(aspect = format.ratio.toFloat(), vertical = false, zoom = keys[c.id] ?: listOf(Key(c.inSec, 0.5f, 0.5f, 1f, 1f)), ease = true))
        }
        return p.copy(width = format.width, height = format.height, clips = clips).linked()
    }

    /** Back to landscape: the reframe goes, the keys of a reframed clip with it (the zoom of AP-54 is a key with w < 1). */
    fun undo(p: VideoProject, width: Int = 1920, height: Int = 1080): VideoProject = p.copy(
        width = width, height = height,
        clips = p.clips.map { c -> if (c.fx.aspect > 0.05f) c.copy(fx = c.fx.copy(aspect = 0f, zoom = c.fx.zoom.filter { it.w < 0.999f })) else c },
    )

    /**
     * Finds the faces in each clip of V1 and gives the keys (clip id → keys). Clips without a detector or without faces
     * get none (they stay in the middle). [progress] 0..1 over all clips.
     */
    suspend fun scan(p: VideoProject, format: Format, info: (File) -> MediaInfo?, progress: (Float) -> Unit = {}): Map<String, List<Key>> {
        val d = Faces.detector() ?: return emptyMap()
        val out = mutableMapOf<String, List<Key>>()
        val total = p.clips.sumOf { it.sourceLength }.coerceAtLeast(0.1)
        var done = 0.0
        for (c in p.clips) {
            val f = File(c.path)
            val mi = info(f)
            val a = mi?.let { if (it.height > 0) it.width.toDouble() / it.height else null }
            if (a != null && a > format.ratio + 0.02 && !c.fx.freeze && !Media.isStill(f, mi)) {
                val samples = Faces.scan(f, c.inSec, c.outSec, d, step = 0.5) { k -> progress(((done + k * c.sourceLength) / total).toFloat()) }
                out[c.id] = follow(samples, a, format.ratio, c.inSec)
            }
            done += c.sourceLength
            progress((done / total).toFloat())
        }
        return out
    }
}
