package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.VideoClip
import io.github.thepytales.llmhub.video.VideoPlayer
import io.github.thepytales.llmhub.video.VideoProject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Flüssige Vorschau (AP-130): measures what the Hauptnutzer feels – how fast a picture comes after a jump of the
 * playhead, how closely the picture follows the mouse while dragging the playhead, and how evenly 1080p plays – with a
 * 1080p and a 4K recording, before and after the proxy file exists. The values go to build/ap130/messung.txt; the
 * thresholds below are the goals of the package.
 */
class VideoPreviewSpeedTest {
    private val base = Files.createTempDirectory("adeu-ap130").toFile()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun ffmpeg(): String? = Media.ffmpeg()?.takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrNull() == true }

    /** Footage like from a camera: moving pattern, a key frame every 2 s, with sound. */
    private fun source(name: String, size: String, sec: Int): File {
        val f = File(base, name)
        val p = ProcessBuilder(
            listOf(ffmpeg()!!, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=$size:r=25:d=$sec",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=$sec:sample_rate=48000", "-c:v", "libx264", "-preset", "ultrafast",
                "-g", "50", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", f.absolutePath),
        ).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        p.waitFor(300, TimeUnit.SECONDS)
        assertTrue(f.isFile && f.length() > 0, "Testvideo $name: $log")
        return f
    }

    private fun project(f: File, sec: Double) = VideoProject(title = f.name, clips = listOf(VideoClip(path = f.absolutePath, inSec = 0.0, outSec = sec)), fadeEnds = false)

    data class Result(val firstMs: Long, val jumpMs: Long, val againMs: Long, val followMs: Long, val followShown: Int, val endMs: Long, val fps: Double, val startMs: Long)

    /** Pictures handed to the screen, with the time they came. */
    private class Seen(player: VideoPlayer) {
        val list = CopyOnWriteArrayList<Pair<Long, VideoPlayer.Shown>>()
        init { player.onShown = { list += System.nanoTime() to it } }
        fun after(t0: Long, timeoutMs: Long = 20_000, ok: (VideoPlayer.Shown) -> Boolean = { true }): Long {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < end) {
                list.firstOrNull { it.first >= t0 && ok(it.second) }?.let { return (it.first - t0) / 1_000_000 }
                Thread.sleep(2)
            }
            return timeoutMs
        }
    }

    private fun measure(p: VideoProject): Result {
        val player = VideoPlayer()
        val seen = Seen(player)
        val spots = listOf(0.31, 0.77, 0.12, 0.93, 0.55, 0.42).map { it * p.duration }
        // Sprung: a click into the timeline, then the next one once the picture is there.
        val firsts = mutableListOf<Long>()
        val jumps = spots.map { t ->
            val t0 = System.nanoTime()
            player.seek(p, t)
            firsts += seen.after(t0) { kotlin.math.abs(it.at - t) < 0.05 }
            seen.after(t0) { kotlin.math.abs(it.at - t) < 0.05 && it.source != VideoPlayer.Source.NEAR }
        }
        // The same places again (back and forth while editing).
        val again = spots.map { t -> val t0 = System.nanoTime(); player.seek(p, t); seen.after(t0) { kotlin.math.abs(it.at - t) < 0.05 } }
        // Ziehen: the playhead follows the mouse, a new place every 20 ms for 1.6 s; how long until a picture near the
        // mouse (±1 s) is on screen, and how long after letting go until the exact one.
        val dragFrom = 0.05 * p.duration
        val dragTo = 0.85 * p.duration
        val steps = 80
        val waits = mutableListOf<Long>()
        val t0 = System.nanoTime()
        var last = 0.0
        val stepAt = LongArray(steps)
        repeat(steps) { i ->
            last = dragFrom + (dragTo - dragFrom) * i / (steps - 1)
            stepAt[i] = System.nanoTime()
            player.seek(p, last)
            Thread.sleep(20)
        }
        val shownWhileDragging = seen.list.count { it.first >= t0 }
        // For every 10th step: the delay until something within a second of it appeared (from the moment of that step).
        for (i in 0 until steps step 10) {
            val at = dragFrom + (dragTo - dragFrom) * i / (steps - 1)
            val when0 = stepAt[i]
            waits += seen.list.firstOrNull { it.first >= when0 && kotlin.math.abs(it.second.at - at) <= 1.0 }?.let { (it.first - when0) / 1_000_000 } ?: 5_000
        }
        val release = System.nanoTime()
        val endMs = seen.after(release) { kotlin.math.abs(it.at - last) < 0.05 && it.source != VideoPlayer.Source.NEAR }
        // Abspielen: 4 s from the middle.
        player.seek(p, p.duration * 0.2)
        Thread.sleep(300)
        val before = player.framesShown
        val play0 = System.nanoTime()
        player.play(p)
        val startMs = seen.after(play0) { it.source == VideoPlayer.Source.PLAY }
        val firstPlay = System.nanoTime()
        Thread.sleep(4000)
        player.stop()
        val fps = (player.framesShown - before) / ((System.nanoTime() - firstPlay) / 1e9)
        player.dispose()
        return Result(firsts.sorted()[firsts.size / 2], jumps.sorted()[jumps.size / 2], again.sorted()[again.size / 2], waits.sorted()[waits.size / 2], shownWhileDragging, endMs, fps, startMs)
    }

    @Test
    fun measureThePreview() {
        if (ffmpeg() == null) return
        val out = File("build/ap130").apply { mkdirs() }
        val report = StringBuilder("Vorschau (AP-130), ${java.time.LocalDateTime.now().withNano(0)} · ${Runtime.getRuntime().availableProcessors()} Kerne\n")
        report.append("Werte: Sprung = Median bis zum ersten / zum genauen Bild nach einem Klick in die Zeitleiste; erneut = dieselben Stellen noch einmal;\n")
        report.append("Ziehen = Median bis ein Bild nahe der Maus (±1 s) da ist; Bilder = gezeigte Bilder beim Ziehen (1,6 s);\n")
        report.append("Loslassen = bis zum genauen Bild; Abspielen = Bilder je Sekunde und Start bis zum ersten Bild.\n\n")
        val results = mutableMapOf<String, Result>()
        for ((name, size, sec) in listOf(Triple("1080p.mp4", "1920x1080", 40), Triple("4k.mp4", "3840x2160", 12))) {
            val f = source(name, size, sec)
            val p = project(f, sec.toDouble())
            // As in the editor: the strip (key frames only) comes first, the proxy afterwards.
            io.github.thepytales.llmhub.video.FrameStrips.make(f)
            val without = measure(p)
            Media.makeProxy(f)
            io.github.thepytales.llmhub.video.FrameStrips.make(f)
            val with = measure(p)
            results["$name ohne"] = without
            results["$name mit"] = with
            for ((label, r) in listOf("ohne Vorschaudatei" to without, "mit Vorschaudatei" to with)) {
                report.append(String.format(java.util.Locale.GERMANY, "%-10s %-19s Sprung %4d / %4d ms · erneut %5d ms · Ziehen %5d ms (%3d Bilder) · Loslassen %5d ms · Abspielen %5.1f B/s, Start %5d ms%n",
                    name.removeSuffix(".mp4"), label, r.firstMs, r.jumpMs, r.againMs, r.followMs, r.followShown, r.endMs, r.fps, r.startMs))
            }
        }
        File(out, "messung.txt").appendText("NACHHER\n" + report.toString() + "\n")
        println(report)
        val hd = results.getValue("1080p.mp4 mit")
        assertTrue(hd.againMs <= 40, "dieselbe Stelle noch einmal kommt sofort: ${hd.againMs} ms")
        assertTrue(hd.followMs <= 120, "beim Ziehen folgt das Bild ohne spürbare Verzögerung: ${hd.followMs} ms")
        assertTrue(hd.fps >= 22.0, "1080p spielt ohne Ruckeln: ${hd.fps} B/s")
    }
}
