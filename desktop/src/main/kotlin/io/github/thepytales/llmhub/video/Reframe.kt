package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.ui.tr
import io.github.thepytales.llmhub.ui.trLater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Format wechseln, Ausschnitt folgt der Person (AP-129 c): a project turns into 9:16, 1:1 or 16:9 in one step – the
 * clips whose pictures have another shape fill the new frame (cut instead of black bars, [ClipFx.fill]) – and the cut
 * follows the person in the picture: faces found on this computer (YuNet, as for the pixelation of AP-54) give a calm
 * camera path as zoom keys. Everything through the one graph of export and preview.
 */
object Reframe {
    /** The formats offered: id, width, height, label. */
    data class Format(val id: String, val width: Int, val height: Int, val label: String)

    val FORMATS = listOf(
        Format("quer", 1920, 1080, trLater("Quer 16:9")),
        Format("hoch", 1080, 1920, trLater("Hochformat 9:16")),
        Format("quadrat", 1080, 1080, trLater("Quadrat 1:1")),
        Format("hoch45", 1080, 1350, trLater("Hoch 4:5")),
    )

    fun format(id: String?): Format? = FORMATS.firstOrNull { it.id == id }

    /** Aspect ratio of [info]'s picture, or null (no picture, not known). */
    private fun aspect(info: MediaInfo?): Double? = info?.takeIf { it.hasVideo && it.width > 0 && it.height > 0 }?.let { it.width.toDouble() / it.height }

    /** Whether a source of aspect [a] stands with bars in a frame of aspect [frame] (more than 3 % apart). */
    fun differs(a: Double, frame: Double): Boolean = abs(a / frame - 1.0) > 0.03

    /**
     * The clips of V1 that stand with black bars in [p]'s frame (pictures included – a photo in 9:16 too), and are not
     * cut to fill it yet.
     */
    fun barred(p: VideoProject, info: (File) -> MediaInfo?): List<VideoClip> {
        val frame = p.width.toDouble() / p.height
        return p.clips.filter { c -> !c.fx.fill && !c.fx.vertical && c.frame.isFull && aspect(info(File(c.path)))?.let { differs(it, frame) } == true }
    }

    /**
     * [p] in [format]: the frame changes and every clip of V1 whose picture has another shape fills it ([fill]); clips
     * that already have the shape stay as they are. Titles, subtitles and lower thirds size themselves to the frame.
     */
    fun toFormat(p: VideoProject, format: Format, info: (File) -> MediaInfo?, fill: Boolean = true): VideoProject {
        val q = p.copy(width = format.width, height = format.height)
        if (!fill) return q
        val ids = barred(q, info).map { it.id }.toSet()
        return q.copy(clips = q.clips.map { if (it.id in ids) it.copy(fx = it.fx.copy(fill = true)) else it })
    }

    /** All clips of V1 back with bars (the cut and its keys removed). */
    fun unfill(p: VideoProject): VideoProject = p.copy(clips = p.clips.map { c -> if (c.fx.fill) c.copy(fx = c.fx.copy(fill = false, zoom = emptyList(), ease = true)) else c })

    /**
     * A calm camera path from face detections (source time → faces): the main face of each sample (the largest, or the
     * one nearest to the last when several are alike – an interview with two people does not jump), held where none is
     * found, outliers taken out (median of five), smoothed over [smoothSec] and only moved when the person leaves the
     * middle of the cut ([deadZone], share of the picture). Keys at full zoom (w = 1): only the position moves.
     */
    fun follow(samples: List<Pair<Double, List<Face>>>, smoothSec: Double = 1.6, deadZone: Float = 0.06f): List<Key> {
        val sorted = samples.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        // The main face per sample; null where there is none.
        var last: Face? = null
        val main = sorted.map { (t, faces) ->
            val best = faces.filter { it.w > 0.01f }.let { fs ->
                val big = fs.maxOfOrNull { it.w * it.h } ?: 0f
                // Faces nearly as large as the largest one count alike; of those the one nearest to the last.
                val alike = fs.filter { it.w * it.h >= big * 0.6f }
                val l = last
                if (l == null) alike.maxByOrNull { it.w * it.h } else alike.minByOrNull { abs(it.x - l.x) + abs(it.y - l.y) }
            }
            if (best != null) last = best
            t to best
        }
        if (main.all { it.second == null }) return listOf(Key(sorted.first().first, 0.5f, 0.5f, 1f, 1f))
        // Hold the last position where no face is seen (before the first one: the first).
        val firstFace = main.first { it.second != null }.second!!
        var hold = firstFace
        val raw = main.map { (t, f) -> if (f != null) hold = f; Triple(t, hold.x, (hold.y - hold.h * 0.1f).coerceIn(0f, 1f)) }
        fun median(i: Int, g: (Triple<Double, Float, Float>) -> Float): Float {
            val window = raw.subList(maxOf(0, i - 2), minOf(raw.size, i + 3)).map(g).sorted()
            return window[window.size / 2]
        }
        val calm = raw.indices.map { i -> Triple(raw[i].first, median(i) { it.second }, median(i) { it.third }) }
        // Moving average over smoothSec.
        val smooth = calm.map { (t, _, _) ->
            val near = calm.filter { abs(it.first - t) <= smoothSec / 2 }
            Triple(t, near.map { it.second }.average().toFloat(), near.map { it.third }.average().toFloat())
        }
        // Dead zone: the camera stays until the person is more than deadZone away, then follows (smoothly, as long as
        // the person moves) and stops again where the person stops – no jerks of small steps.
        val keys = mutableListOf<Key>()
        var cx = smooth.first().second
        var cy = smooth.first().third
        var moving = false
        keys += Key(smooth.first().first, cx, cy, 1f, 1f)
        for ((t, x, y) in smooth.drop(1)) {
            if (!moving && (abs(x - cx) > deadZone || abs(y - cy) > deadZone)) moving = true
            if (moving) {
                val still = abs(x - cx) < 0.004f && abs(y - cy) < 0.004f
                cx = x; cy = y
                if (still) moving = false
            }
            keys += Key(t, cx, cy, 1f, 1f)
        }
        // Where the camera stands still, only the first and the last key of the stretch are needed.
        return Faces.simplify(keys, tolerance = 0.004f)
    }

    /** Puts the camera path [keys] on V1 clip [id]: it fills the frame and moves without easing between the keys. */
    fun apply(p: VideoProject, id: String, keys: List<Key>): VideoProject =
        VideoFx.change(p, id) { it.copy(fill = true, zoom = keys, ease = false) }

    /** Seconds between two looks at the picture when following a person. */
    const val STEP = 0.5

    /**
     * Finds the person in V1 clip [c] (over its whole piece of the source) and gives the camera path; throws with a
     * clear message when the face detection is not there (without the Videoeditor-Paket the cut stays in the middle).
     */
    suspend fun find(c: VideoClip, progress: (Float) -> Unit = {}): List<Key> {
        val d = withContext(Dispatchers.IO) { Faces.detector() } ?: throw IllegalStateException(Faces.missing() ?: tr("Die Gesichtserkennung ließ sich nicht starten."))
        val src = File(c.path)
        if (Media.isStill(src)) {
            val img = withContext(Dispatchers.IO) { Media.frameAwt(src, 0.0) } ?: return emptyList()
            return follow(listOf(c.inSec to d.detect(img)))
        }
        return follow(Faces.scan(src, c.inSec, c.outSec, d, step = STEP, progress = progress))
    }
}
