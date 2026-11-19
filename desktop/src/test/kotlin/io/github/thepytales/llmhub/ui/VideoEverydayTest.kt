package io.github.thepytales.llmhub.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.transcribe.Segment
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.transcribe.Word
import io.github.thepytales.llmhub.creative.pngBytes
import io.github.thepytales.llmhub.video.AudioClip
import io.github.thepytales.llmhub.video.AudioSettings
import io.github.thepytales.llmhub.video.Ducking
import io.github.thepytales.llmhub.video.Face
import io.github.thepytales.llmhub.video.FaceDetector
import io.github.thepytales.llmhub.video.Faces
import io.github.thepytales.llmhub.video.Highlights
import io.github.thepytales.llmhub.video.Key
import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.MultiCam
import io.github.thepytales.llmhub.video.Reframe
import io.github.thepytales.llmhub.video.SubtitleCue
import io.github.thepytales.llmhub.video.SubtitleStyles
import io.github.thepytales.llmhub.video.TextCut
import io.github.thepytales.llmhub.video.VideoClip
import io.github.thepytales.llmhub.video.VideoFx
import io.github.thepytales.llmhub.video.VideoProject
import io.github.thepytales.llmhub.video.VideoRender
import io.github.thepytales.llmhub.video.VideoTemplates
import io.github.thepytales.llmhub.video.VideoTitles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Video: was im Alltag fehlt (AP-129) – the gaps of high use in docs/video-luecken.md: Ducking (the music measurably
 * quieter by the chosen dB while someone speaks, as loud as before in between), Hochformat mit Ausschnitt, der der
 * Person folgt (the face lands in the middle of the 9:16 export), Mehrkamera nach Sprecher, Untertitel-Stile,
 * Höhepunkte als Clip, Vorlagen für Titel und Abspann; the template dialog with clicks.
 */
@OptIn(ExperimentalTestApi::class)
class VideoEverydayTest {
    private val base = Files.createTempDirectory("adeu-ap129").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }

    @AfterTest
    fun cleanUp() {
        Faces.override = null
        base.deleteRecursively()
    }

    private fun ffmpeg(): String? = Media.ffmpeg()?.takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrNull() == true }

    private fun run(vararg args: String) {
        val p = ProcessBuilder(listOf(ffmpeg()!!, "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0, out)
    }

    /** max_volume of [f] from [from] for [len] s, after [filter]. */
    private fun loudness(f: File, from: Double, len: Double, filter: String = "anull"): Double {
        val p = ProcessBuilder(ffmpeg()!!, "-nostdin", "-hide_banner", "-ss", "$from", "-t", "$len", "-i", f.absolutePath, "-af", "$filter,volumedetect", "-f", "null", "-")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return Regex("max_volume: (-?[0-9.]+) dB").find(out)?.groupValues?.get(1)?.toDouble() ?: -200.0
    }

    // ── Ducking ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theGraphOfDuckingKeepsTheMusicAtTheChosenLevel() {
        val (lines, out) = Ducking.lines("a", listOf("la0"), listOf("m0", "m1"), 12f)
        assertEquals("amx", out)
        assertTrue(lines.any { "sidechaincompress" in it } && lines.any { "[dm2]volume=0.2512" in it }, lines.joinToString("\n"))
        assertTrue(lines.first().startsWith("[a][la0]amix"), "Sprache aus V1 und Bild-in-Bild steuert")
        assertEquals(0.5012, Ducking.floor(6f), 1e-3)
        // Without ducking the graph stays as before: no compressor.
        val p = VideoProject(title = "x", clips = listOf(VideoClip(path = "/v.mp4", inSec = 0.0, outSec = 4.0)), audio = listOf(AudioClip(path = "/m.mp3", startSec = 0.0, outSec = 4.0)))
        val info: (File) -> io.github.thepytales.llmhub.video.MediaInfo? = { io.github.thepytales.llmhub.video.MediaInfo(4.0, 640, 360, 25.0, hasVideo = it.extension == "mp4", hasAudio = true) }
        val off = VideoRender.graph(p, 640, 360, 25, File(base, "g1"), info, null)
        assertTrue(off.lines.none { "sidechaincompress" in it })
        val on = VideoRender.graph(p.copy(sound = AudioSettings(duck = true)), 640, 360, 25, File(base, "g2"), info, null)
        assertTrue(on.lines.any { "sidechaincompress" in it }, on.lines.joinToString("\n"))
        // Linked sound (a lapel microphone) is speech, not music.
        val linked = p.copy(sound = AudioSettings(duck = true), audio = p.audio.map { it.copy(linkedTo = p.clips[0].id) })
        assertTrue(VideoRender.graph(linked, 640, 360, 25, File(base, "g3"), info, null).lines.none { "sidechaincompress" in it })
    }

    @Test
    fun theMusicGoesDownWhileSomeoneSpeaksAndComesBack(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // Speech: 440 Hz from 0–2 s and 4–6 s, silent between. Music: 2 kHz the whole time.
        val speech = File(base, "sprache.mp4")
        run(
            "-f", "lavfi", "-i", "color=c=gray:s=320x180:r=25:d=6", "-f", "lavfi", "-i", "sine=frequency=440:duration=6:sample_rate=48000",
            "-af", "volume='if(between(t,2,4),0,0.5)':eval=frame", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", speech.absolutePath,
        )
        val music = File(base, "musik.m4a")
        run("-f", "lavfi", "-i", "sine=frequency=2000:duration=6:sample_rate=48000", "-af", "volume=0.3", "-c:a", "aac", music.absolutePath)
        val p = VideoProject(
            title = "Ducking", fadeEnds = false, sound = AudioSettings(normalize = false, duck = true, duckDb = 12f),
            clips = listOf(VideoClip(path = speech.absolutePath, inSec = 0.0, outSec = 6.0)),
            audio = listOf(AudioClip(path = music.absolutePath, startSec = 0.0, outSec = 6.0, volume = 1f, fadeSec = 0.0, track = 1)),
        )
        val band = "bandpass=f=2000:width_type=h:w=100"
        val out = VideoRender.render(p, VideoRender.PRESETS.first(), File(base, "geduckt.m4a"), audioOnly = true) {}
        val during = loudness(out, 1.0, 0.6, band)
        val between = loudness(out, 2.9, 0.6, band)
        val after = loudness(out, 5.0, 0.6, band)
        assertTrue(abs((between - during) - 12.0) < 3.0, "beim Sprechen etwa 12 dB leiser (sprechen $during dB, Pause $between dB)")
        assertTrue(abs((between - after) - 12.0) < 3.0, "danach wieder leiser beim Sprechen ($after dB)")
        val flat = VideoRender.render(p.copy(sound = p.sound.copy(duck = false)), VideoRender.PRESETS.first(), File(base, "flach.m4a"), audioOnly = true) {}
        assertTrue(abs(loudness(flat, 1.0, 0.6, band) - loudness(flat, 2.9, 0.6, band)) < 1.5, "ohne Ducking gleich laut")
        val voice = "bandpass=f=440:width_type=h:w=60"
        assertTrue(abs(loudness(out, 1.0, 0.6, voice) - loudness(flat, 1.0, 0.6, voice)) < 1.0, "die Sprache selbst bleibt, wie sie war")
    }

    // ── Hochformat mit Ausschnitt ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theWindowFollowsTheFaceCalmly() {
        // A face walking from the left third to the right third over 10 s, with a little jitter.
        val samples = (0..20).map { k -> val t = k * 0.5; t to listOf(Face(0.25f + 0.5f * k / 20f + (if (k % 2 == 0) 0.01f else -0.01f), 0.45f, 0.1f, 0.18f)) }
        val keys = Reframe.follow(samples, 16.0 / 9, 9.0 / 16, 0.0)
        assertTrue(keys.first().x < 0.35f && keys.last().x > 0.65f, keys.toString())
        assertTrue(keys.all { it.w == 1f }, "volle Höhe")
        assertTrue(keys.zipWithNext().all { (a, b) -> b.x >= a.x - 1e-4 }, "nur in eine Richtung, kein Zittern: $keys")
        // A face that only nods (±2 %) keeps the window still.
        val still = Reframe.follow((0..20).map { k -> k * 0.5 to listOf(Face(0.6f + if (k % 2 == 0) 0.02f else -0.02f, 0.5f, 0.1f, 0.2f)) }, 16.0 / 9, 9.0 / 16, 0.0)
        assertEquals(1, still.map { it.x }.distinct().size, still.toString())
        // A second person in the background does not take the window.
        val two = Reframe.mainFaces(listOf(0.0 to listOf(Face(0.3f, 0.5f, 0.2f, 0.3f), Face(0.8f, 0.4f, 0.05f, 0.08f)), 0.5 to listOf(Face(0.79f, 0.4f, 0.05f, 0.08f), Face(0.32f, 0.5f, 0.2f, 0.3f))))
        assertTrue(two.all { it.second.x < 0.5f }, two.toString())
        // Without a face: the middle.
        assertEquals(listOf(Key(2.0, 0.5f, 0.5f, 1f, 1f)), Reframe.follow(emptyList(), 16.0 / 9, 1.0, 2.0))
    }

    @Test
    fun theProjectBecomesPortraitAndTheGraphCutsAtFullHeight() {
        val p = VideoProject(title = "Clip", clips = listOf(VideoClip(id = "a", path = "/quer.mp4", inSec = 0.0, outSec = 8.0), VideoClip(id = "b", path = "/hoch.mp4", inSec = 0.0, outSec = 4.0)))
        val keys = listOf(Key(0.0, 0.7f, 0.5f, 1f, 1f))
        val q = Reframe.apply(p, Reframe.format("9:16"), mapOf("a" to keys)) { if (it.path == "/quer.mp4") 16.0 / 9 else 9.0 / 16 }
        assertEquals(1080 to 1920, q.width to q.height)
        assertEquals(9f / 16, q.clips[0].fx.aspect, 1e-4f)
        assertEquals(keys, q.clips[0].fx.zoom)
        assertEquals(0f, q.clips[1].fx.aspect, "eine Hochformat-Aufnahme bleibt, wie sie ist")
        assertTrue("Hochformat" in VideoFx.summary(q.clips[0].fx))
        // Working picture as high as the frame (no blow-up), window 9:16, scaled to the frame's width.
        val info = io.github.thepytales.llmhub.video.MediaInfo(8.0, 1920, 1080, 25.0, hasVideo = true, hasAudio = false)
        assertEquals(3412 to 1920, VideoFx.workSize(info, 1080, 1920, 9.0 / 16))
        val lines = VideoFx.pictureLines("[0:v]", "e0", q.clips[0].fx, VideoFx.span(q.clips[0], 0.0, 8.0, false), 3412, 1920, 25, false)
        assertTrue(lines.any { "crop=1080:1920:" in it && "scale=1080:1920" in it }, lines.joinToString("\n"))
        // Square from landscape, and back to landscape.
        val sq = Reframe.apply(p, Reframe.format("1:1"), emptyMap()) { 16.0 / 9 }
        assertEquals(1080 to 1080, sq.width to sq.height)
        assertTrue(sq.clips.all { it.fx.aspect == 1f && it.fx.zoom.single().x == 0.5f })
        val back = Reframe.undo(q)
        assertTrue(back.clips.all { it.fx.aspect == 0f && it.fx.zoom.isEmpty() } && back.width == 1920)
    }

    /** Stands in for YuNet: a "face" is a yellow square. */
    private class YellowFaces : FaceDetector {
        override val name = "Testdetektor"
        override fun detect(img: BufferedImage): List<Face> {
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val c = img.getRGB(x, y)
                if ((c shr 16 and 0xff) > 170 && (c shr 8 and 0xff) > 170 && (c and 0xff) < 90) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
            }
            if (x1 < 0) return emptyList()
            val w = img.width.toFloat(); val h = img.height.toFloat()
            return listOf(Face((x0 + x1) / 2f / w, (y0 + y1) / 2f / h, (x1 - x0 + 1) / w, (y1 - y0 + 1) / h, 0.95f))
        }
    }

    /** Centre (share of the width) of the yellow pixels in [img], or null. */
    private fun yellowX(img: BufferedImage): Double? {
        var sum = 0.0; var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = img.getRGB(x, y)
            if ((c shr 16 and 0xff) > 170 && (c shr 8 and 0xff) > 170 && (c and 0xff) < 90) { sum += x; n++ }
        }
        return if (n < 20) null else sum / n / img.width
    }

    @Test
    fun thePersonOnTheRightIsInTheMiddleOfThePortraitExport(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // A landscape recording with the "person" (yellow square) at the right edge, 3 s.
        val video = File(base, "quer.mp4")
        run(
            "-f", "lavfi", "-i", "color=c=gray:s=640x360:r=25:d=3", "-f", "lavfi", "-i", "color=c=yellow:s=60x60:r=25:d=3",
            "-filter_complex", "[0][1]overlay=x=520:y=120[v]", "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", video.absolutePath,
        )
        Faces.override = YellowFaces()
        val p = VideoProject(title = "Hoch", fadeEnds = false, sound = AudioSettings(normalize = false), clips = listOf(VideoClip(id = "c", path = video.absolutePath, inSec = 0.0, outSec = 3.0)))
        val f = Reframe.format("9:16")
        val keys = Reframe.scan(p, f, { Media.probe(it) })
        assertTrue(keys["c"]!!.first().x > 0.8f, keys.toString())
        val q = Reframe.apply(p, f, keys) { 16.0 / 9 }
        val out = VideoRender.render(q, VideoRender.PRESETS.first(), File(base, "hoch.mp4")) {}
        val png = File(base, "bild.png")
        run("-ss", "1.5", "-i", out.absolutePath, "-frames:v", "1", png.absolutePath)
        val img = ImageIO.read(png)
        assertTrue(img.height > img.width, "Hochformat (${img.width}×${img.height})")
        val x = yellowX(img)
        assertTrue(x != null && x in 0.55..0.95, "die Person ist im Bild, am rechten Rand des Ausschnitts, weil das Bild dort endet (x = $x)")
        // In the middle when the square sits in the middle of the frame.
        val mid = File(base, "mitte.mp4")
        run(
            "-f", "lavfi", "-i", "color=c=gray:s=640x360:r=25:d=2", "-f", "lavfi", "-i", "color=c=yellow:s=60x60:r=25:d=2",
            "-filter_complex", "[0][1]overlay=x=380:y=120[v]", "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", mid.absolutePath,
        )
        val p2 = p.copy(clips = listOf(VideoClip(id = "m", path = mid.absolutePath, inSec = 0.0, outSec = 2.0)))
        val q2 = Reframe.apply(p2, f, Reframe.scan(p2, f, { Media.probe(it) })) { 16.0 / 9 }
        val out2 = VideoRender.render(q2, VideoRender.PRESETS.first(), File(base, "hoch2.mp4")) {}
        run("-ss", "1.0", "-i", out2.absolutePath, "-frames:v", "1", png.absolutePath)
        val x2 = yellowX(ImageIO.read(png))
        assertTrue(x2 != null && abs(x2 - 0.5) < 0.1, "die Person steht in der Mitte des Hochformats (x = $x2)")
    }

    // ── Mehrkamera nach Sprecher ────────────────────────────────────────────────────────────────────────────

    private fun interview(): Transcript {
        fun seg(id: Int, start: Double, end: Double, speaker: Int, text: String) = Segment(id, start, end, text, speaker, emptyList())
        return Transcript(
            id = "t1", title = "Interview", created = "2026-11-19T08:00:00", duration = 20.0, language = "de", modelName = "test", device = "pc",
            speakers = mapOf(0 to "Frau Lehmann", 1 to "Herr Ober"), segments = listOf(
                seg(0, 0.5, 4.0, 0, "Wie war der erste Tag?"),
                seg(1, 4.5, 9.0, 1, "Aufregend, die Kinder waren sehr neugierig."),
                seg(2, 9.2, 9.8, 0, "Ja?"),
                seg(3, 10.0, 15.0, 1, "Ja, wir haben gleich ein Experiment gemacht."),
                seg(4, 15.5, 19.5, 0, "Das klingt gut."),
            ),
            audio = null, folder = null, marks = emptyList(), notice = null, raw = JsonObject(emptyMap()),
        )
    }

    @Test
    fun theCutGoesToThePersonWhoSpeaks() {
        val shots = MultiCam.fromSpeakers(interview(), 0.0, 20.0, mapOf(0 to 0, 1 to 1), minShot = 2.0)
        assertEquals(listOf(0, 1, 0), shots.map { it.camera }, shots.toString())
        assertEquals(4.35, shots[1].from, 1e-9)
        assertEquals(15.35, shots[2].from, 1e-9, "das kurze „Ja?“ unterbricht die Einstellung nicht")
        assertEquals(20.0, shots.last().to, 1e-9)
        // Loudness: the second microphone is louder from 5 to 12 s.
        val rate = 100
        val a = FloatArray(20 * rate) { 0.2f }
        val b = FloatArray(22 * rate) { i -> if (i / rate.toDouble() - 1.0 in 5.0..12.0) 0.6f else 0.05f }
        val loud = MultiCam.fromLoudness(a, b, rate, 1.0, 0.0, 20.0)
        assertEquals(listOf(0, 1, 0), loud.map { it.camera }, loud.toString())
        assertEquals(5.0, loud[1].from, 1.01)

        // Apply: the main clip becomes three shots, the second camera's shot muted with the main sound linked.
        val p = VideoProject(title = "I", clips = listOf(VideoClip(id = "haupt", path = "/a.mp4", inSec = 0.0, outSec = 20.0), VideoClip(id = "x", path = "/b.mp4", inSec = 0.0, outSec = 3.0)))
        val q = MultiCam.apply(p, "haupt", "/zweite.mp4", 0.8, shots)
        assertEquals(listOf("/a.mp4", "/zweite.mp4", "/a.mp4", "/b.mp4"), q.clips.map { it.path })
        assertEquals(p.duration, q.duration, 1e-6, "das Video bleibt gleich lang")
        val second = q.clips[1]
        assertTrue(second.muted && abs(second.inSec - (4.35 + 0.8)) < 1e-9)
        val sound = q.audio.single()
        assertEquals(second.id, sound.linkedTo)
        assertEquals("/a.mp4", sound.path)
        assertEquals(4.35, sound.inSec, 1e-9, "Ton der Hauptkamera genau an dieser Stelle")
        assertEquals(q.starts()[1], sound.startSec, 1e-9)
        assertTrue("/zweite.mp4" in q.media.map { it.path })
        assertNotEquals(null, MultiCam.problem(p.clips[0].copy(fx = p.clips[0].fx.copy(speed = 2.0))))
    }

    // ── Untertitel-Stile ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun wordByWordShowsAFewWordsWithTheSpokenOneHighlighted() {
        val cues = listOf(SubtitleCue(1.0, 4.0, "Heute geht es um Brüche. Los geht's"))
        assertEquals(listOf(listOf("Heute", "geht", "es"), listOf("um", "Brüche."), listOf("Los", "geht's")), SubtitleStyles.groups("Heute geht es um Brüche. Los geht's".split(" ")))
        val words = SubtitleStyles.words(cues)
        assertEquals(7, words.size)
        assertEquals(1.0, words.first().startSec, 1e-9)
        assertEquals(4.0, words.last().endSec, 1e-9)
        assertTrue(words.zipWithNext().all { (a, b) -> abs(a.endSec - b.startSec) < 1e-9 }, "lückenlos")
        assertEquals("Heute geht es", SubtitleStyles.plain(words[1].text))
        assertTrue("<span style=\"color:${SubtitleStyles.HIGHLIGHT}\">geht</span>" in words[1].text, words[1].text)
        val p = VideoProject(title = "x", subtitles = cues)
        assertEquals(cues, SubtitleStyles.display(p))
        assertEquals(cues, SubtitleStyles.display(p.copy(subtitleStyle = SubtitleStyles.GROSS)))
        assertEquals(7, SubtitleStyles.display(p.copy(subtitleStyle = SubtitleStyles.WOERTER)).size)
        assertTrue(SubtitleStyles.words(listOf(SubtitleCue(0.0, 1.0, "**fett** <b>"))).none { "**" in it.text || "<b>" in it.text })
    }

    @Test
    fun theStylesDrawDifferentlyAndTheExportTakesThem() {
        val dir = File("build/video-everyday").apply { mkdirs() }
        fun img(style: String, text: String) = VideoTitles.render(VideoTitles.styledSubtitleDesign(text, 1080, 1920, false, style), 270).also { b ->
            File(dir, "untertitel-$style.png").writeBytes(b.pngBytes())
        }
        val std = img(SubtitleStyles.STANDARD, "Heute geht es um Brüche")
        val big = img(SubtitleStyles.GROSS, "Heute geht es um Brüche")
        val word = img(SubtitleStyles.WOERTER, SubtitleStyles.words(listOf(SubtitleCue(0.0, 1.0, "Heute geht es")))[1].text)
        fun pixels(b: androidx.compose.ui.graphics.ImageBitmap): IntArray { val a = IntArray(b.width * b.height); b.readPixels(a); return a }
        val ps = pixels(std); val pb = pixels(big); val pw = pixels(word)
        assertTrue(ps.zip(pb).count { (a, b) -> a != b } > 500, "Groß sieht anders aus als Standard")
        // „Wort für Wort“: the highlighted word is yellow.
        val yellow = pw.count { c -> (c shr 16 and 0xff) > 200 && (c shr 8 and 0xff) > 180 && (c and 0xff) < 80 && (c ushr 24) > 200 }
        assertTrue(yellow > 30, "gelbes Wort ($yellow Bildpunkte)")
        // The graph of the export draws one picture per word.
        val p = VideoProject(title = "x", burnSubtitles = true, subtitleStyle = SubtitleStyles.WOERTER, clips = listOf(VideoClip(path = "/v.mp4", inSec = 0.0, outSec = 4.0)), subtitles = listOf(SubtitleCue(0.5, 3.0, "eins zwei drei vier")))
        val work = File(base, "g")
        VideoRender.graph(p, 320, 568, 25, work, { io.github.thepytales.llmhub.video.MediaInfo(4.0, 640, 360, 25.0, hasVideo = true, hasAudio = false) }, null)
        assertEquals(4, work.listFiles()!!.count { it.name.startsWith("untertitel-") && it.extension == "png" })
    }

    // ── Höhepunkte ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun highlightsBecomeANewShortProject() {
        val clip = VideoClip(id = "v", path = "/vorlesung.mp4", inSec = 0.0, outSec = 20.0, fx = io.github.thepytales.llmhub.video.ClipFx(brightness = 0.1f))
        val p = VideoProject(title = "Vorlesung 3", clips = listOf(clip), transcripts = mapOf("/vorlesung.mp4" to "t1"))
        val ts = mapOf("/vorlesung.mp4" to interview())
        val passages = Highlights.passages(p, ts)
        assertEquals(5, passages.size)
        val prompt = Highlights.prompt(passages, 30, "das Experiment")
        assertTrue("höchstens 30 Sekunden" in prompt && "2 [0:04–0:09" in prompt && "das Experiment" in prompt, prompt)
        // 1 and 4 chosen (3.9 + 5.4 s with air), 5 too much for 10 s.
        val chosen = Highlights.choose(passages, "1, 4, 5", 10)
        assertEquals(2, chosen.size, chosen.toString())
        assertTrue(chosen.sumOf { it.length } <= 11.0)
        // Passages right after each other become one piece.
        assertEquals(1, Highlights.choose(passages, "4-5", 60).size)
        val q = Highlights.project(p, chosen, ts, Reframe.format("9:16"), SubtitleStyles.WOERTER)
        assertNotEquals(p.id, q.id)
        assertEquals("Vorlesung 3 – Höhepunkte", q.title)
        assertEquals(1080 to 1920, q.width to q.height)
        assertEquals(2, q.clips.size)
        assertTrue(q.clips.all { it.fx.brightness == 0.1f }, "Bildkorrektur geht mit")
        assertTrue(q.subtitles.isNotEmpty() && q.burnSubtitles && q.subtitleStyle == SubtitleStyles.WOERTER)
        assertTrue(q.subtitles.any { "Experiment" in it.text })
        assertEquals(p.clips.single(), clip, "das lange Projekt bleibt unverändert")
    }

    // ── Vorlagen ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun templatesFillTitleLowerThirdsAndEndCard() {
        val p = VideoProject(title = "I", clips = listOf(VideoClip(id = "c", path = "/a.mp4", inSec = 0.0, outSec = 20.0)), overlays = listOf(io.github.thepytales.llmhub.video.TextOverlay(text = "Eigene", startSec = 8.0)))
        val doc = TextCut.build(p, mapOf("/a.mp4" to interview()))
        val speakers = VideoTemplates.speakers(doc)
        assertEquals(listOf("Frau Lehmann", "Herr Ober"), speakers.map { it.first })
        val t = VideoTemplates.template("interview")!!
        val q = VideoTemplates.apply(p, t, mapOf("titel" to "Lernen im Ganztag", "untertitel" to "Ein Gespräch", "abspann" to "Interview: Team\nTU Dresden"), speakers)
        val styles = q.overlays.map { it.style }
        assertEquals(listOf("bauchbinde", "titel", "bauchbinde", "bauchbinde", "abspann"), styles, q.overlays.toString())
        val title = q.overlays.first { it.style == "titel" }
        assertEquals(0.0, title.startSec)
        assertEquals("Ein Gespräch", title.subtext)
        val lehmann = q.overlays.first { it.text == "Frau Lehmann" }
        assertTrue(lehmann.startSec >= title.endSec, "nicht über dem Titel")
        val ober = q.overlays.first { it.text == "Herr Ober" }
        assertTrue(ober.startSec >= lehmann.endSec, "Bauchbinden nacheinander, nicht übereinander")
        val end = q.overlays.first { it.style == "abspann" }
        assertEquals(20.0, end.endSec, 1e-9)
        assertEquals("TU Dresden", end.subtext)
        // Again: replaces what the template set, keeps the own title.
        val again = VideoTemplates.apply(q, VideoTemplates.template("clip")!!, mapOf("titel" to "Drei Tipps"))
        assertEquals(listOf("Eigene", "Drei Tipps"), again.overlays.map { it.text })
        assertEquals("einblendung", again.overlays.last().style)
    }

    // ── Dialog ──────────────────────────────────────────────────────────────────────────────────────────────

    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 6) {
        repeat(rounds) { Thread.sleep(60); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    @Test
    fun theTemplateDialogPutsTheTitlesIn() = runSkikoComposeUiTest(Size(1200f, 1000f), Density(1f)) {
        mainClock.autoAdvance = false
        val h = History(VideoProject(title = "U", clips = listOf(VideoClip(id = "c", path = "/a.mp4", inSec = 0.0, outSec = 30.0))))
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { VideoTemplateDialog(c, h, onDismiss = {}, load = { emptyMap() }) }
            }
        }
        settle()
        onAllNodesWithText("Unterrichtsvideo").onFirst().performClick()
        settle(2)
        onAllNodesWithText("Thema der Stunde").onFirst().performTextInput("Brüche addieren")
        settle(2)
        onAllNodesWithText("Einsetzen").onFirst().performClick()
        settle(2)
        val t = h.project.overlays.single()
        assertEquals("titel" to "Brüche addieren", t.style to t.text)
    }
}
