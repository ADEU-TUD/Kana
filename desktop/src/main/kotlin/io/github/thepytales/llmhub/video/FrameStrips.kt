package io.github.thepytales.llmhub.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.thepytales.llmhub.AppLog
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bildstreifen für die Vorschau (AP-130): one small picture per second of each file, kept as JPEG in memory. While the
 * playhead is dragged, the preview shows the nearest of them at once (a little blurred) and the exact picture once the
 * mouse rests or lets go – the playhead follows the mouse without waiting for ffmpeg. Made in the background from the
 * key frames only (quick even for 4K and before the proxy exists), again from the proxy once that is there.
 */
object FrameStrips {
    /** Height of the pictures; the width follows the file. */
    const val H = 108

    /**
     * The pictures of a file: [step] seconds apart, the first at 0, [w]×[H] each; [srcW]×[srcH] is the size of the file
     * (the near picture is fitted like the exact one, so nothing jumps when the exact picture comes).
     */
    class Strip(val step: Double, val jpegs: List<ByteArray>, val fromProxy: Boolean, val w: Int, val srcW: Int, val srcH: Int)

    private val strips = ConcurrentHashMap<String, Strip>()
    private val making = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Bildstreifen").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private fun key(file: File) = file.absolutePath

    fun get(file: File): Strip? = strips[key(file)]

    /** Makes the strip in the background unless it is there (or a better one, from the proxy, can be made now). */
    fun ensure(file: File) {
        val have = strips[key(file)]
        if (have != null && (have.fromProxy || Media.proxy(file) == null)) return
        if (!making.add(key(file))) return
        worker.execute { try { make(file) } finally { making.remove(key(file)) } }
    }

    /** Makes the strip of [file] now (blocking): from the proxy when there is one, otherwise from the file's key frames. */
    fun make(file: File): Strip? {
        if (!file.isFile || Media.isStill(file)) return null
        val exe = Media.ffmpeg() ?: return null
        val proxy = Media.proxy(file)
        val src = proxy ?: file
        val info = Media.probe(file)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val step = 1.0
        val w = maxOf(2, Math.round(H.toDouble() * info.width / info.height / 2).toInt() * 2)
        return runCatching {
            val args = listOf(exe, "-nostdin", "-hide_banner", "-loglevel", "error", "-skip_frame", "nokey", "-i", src.absolutePath, "-an",
                "-vf", "fps=${1 / step},scale=$w:$H,setsar=1,format=rgba",
                "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1")
            val proc = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val frame = ByteArray(w * H * 4)
            val out = ArrayList<ByteArray>()
            proc.inputStream.use { input ->
                while (true) {
                    var off = 0
                    while (off < frame.size) { val n = input.read(frame, off, frame.size - off); if (n <= 0) break; off += n }
                    if (off < frame.size) break
                    val img = Image.makeRaster(ImageInfo(w, H, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), frame, w * 4)
                    out += img.encodeToData(EncodedImageFormat.JPEG, 72)?.bytes ?: break
                }
            }
            if (!proc.waitFor(60, TimeUnit.SECONDS)) proc.destroyForcibly()
            if (out.isEmpty()) null else Strip(step, out, proxy != null, w, info.width, info.height).also { strips[key(file)] = it }
        }.onFailure { AppLog.e("Bildstreifen", it) }.getOrNull()
    }

    /**
     * The picture of [file] nearest to [sec] at preview size [w]×[h] (fitted with bars like the preview), or null while
     * there is no strip yet.
     */
    fun near(file: File, sec: Double, w: Int, h: Int): ImageBitmap? {
        val s = strips[key(file)] ?: return null
        val i = Math.round(sec / s.step).toInt().coerceIn(0, s.jpegs.lastIndex)
        return runCatching {
            val small = Image.makeFromEncoded(s.jpegs[i])
            val surface = Surface.makeRasterN32Premul(w, h)
            // Where ffmpeg's scale=…:force_original_aspect_ratio=decrease,pad puts the picture (VideoRender.fitInto).
            val (dw, dh) = if (s.srcW.toLong() * h > s.srcH.toLong() * w) w to (s.srcH.toLong() * w / s.srcW).toInt() else (s.srcW.toLong() * h / s.srcH).toInt() to h
            // pad works on 4:2:0 pictures: its offsets are even.
            val x = (w - dw) / 2 / 2 * 2
            val y = (h - dh) / 2 / 2 * 2
            surface.canvas.clear(0xFF000000.toInt())
            surface.canvas.drawImageRect(small, Rect.makeXYWH(x.toFloat(), y.toFloat(), dw.toFloat(), dh.toFloat()))
            surface.makeImageSnapshot().toComposeImageBitmap()
        }.getOrNull()
    }

    /** For tests: forget all strips. */
    internal fun clear() = strips.clear()
}
