package io.github.thepytales.llmhub

import androidx.compose.ui.graphics.toAwtImage
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.video.AudioClip
import io.github.thepytales.llmhub.video.AudioSettings
import io.github.thepytales.llmhub.video.CaptionStyles
import io.github.thepytales.llmhub.video.SubtitleCue
import io.github.thepytales.llmhub.video.VideoTitles
import io.github.thepytales.llmhub.video.ClipFx
import io.github.thepytales.llmhub.video.Face
import io.github.thepytales.llmhub.video.FaceDetector
import io.github.thepytales.llmhub.video.Faces
import io.github.thepytales.llmhub.video.Highlights
import io.github.thepytales.llmhub.video.MultiCam
import io.github.thepytales.llmhub.video.Callouts
import io.github.thepytales.llmhub.video.TextOverlay
import io.github.thepytales.llmhub.video.Frame
import io.github.thepytales.llmhub.video.Bleep
import io.github.thepytales.llmhub.transcribe.Segment
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.transcribe.Word
import kotlinx.serialization.json.JsonObject
import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.MediaInfo
import io.github.thepytales.llmhub.video.Reframe
import io.github.thepytales.llmhub.video.VideoFx
import io.github.thepytales.llmhub.video.SoundFix
import io.github.thepytales.llmhub.video.VideoClip
import io.github.thepytales.llmhub.video.VideoProject
import io.github.thepytales.llmhub.video.VideoRender
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AP-129 Video: was im Alltag fehlt – Stufe (b) Ton: music gets quieter while someone speaks (ducking), quiet and loud
 * voices are evened out, a recording with sound on one side only is put on both. With test tones: 440 Hz is the
 * „voice“, 2500 Hz the „music“; each is measured through a band-pass in the exported sound.
 */
class VideoEverydayTest {
    private val base = Files.createTempDirectory("adeu-ap129").toFile()

    private val pictures = File("build/ap129").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        Faces.override = null
        base.deleteRecursively()
    }

    private fun ffmpeg(): String? = Media.ffmpeg()?.takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrNull() == true }

    private fun make(name: String, vararg args: String): File {
        val f = File(base, name)
        val p = ProcessBuilder(listOf(ffmpeg()!!, "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + args.toList() + f.absolutePath).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertTrue(f.isFile && f.length() > 0, "Testdatei $name: $log")
        return f
    }

    /** max_volume of [f] from [from] for [len] s, after [filter]. */
    private fun level(f: File, from: Double, len: Double, filter: String = "anull"): Double {
        val p = ProcessBuilder(ffmpeg()!!, "-nostdin", "-hide_banner", "-ss", "$from", "-t", "$len", "-i", f.absolutePath, "-af", "$filter,volumedetect", "-f", "null", "-")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return Regex("max_volume: (-?[0-9.]+) dB").find(out)?.groupValues?.get(1)?.toDouble() ?: -200.0
    }

    private val voice = "bandpass=f=440:width_type=h:w=80"
    private val music = "bandpass=f=2500:width_type=h:w=200"

    /** 8 s of grey picture with a „voice“ (440 Hz) that speaks 0–2 s and 5–8 s and is silent in between. */
    private fun talk(): File = make(
        "vortrag.mp4", "-f", "lavfi", "-i", "color=c=gray:s=320x180:r=25:d=8",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=8:sample_rate=48000,volume='if(between(t,2,5),0,1)':eval=frame",
        "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
    )

    private fun project(video: File, vararg audio: AudioClip, sound: AudioSettings = AudioSettings(normalize = false)) = VideoProject(
        title = "Alltag", width = 320, height = 180, fadeEnds = false, sound = sound,
        clips = listOf(VideoClip(id = "v", path = video.absolutePath, inSec = 0.0, outSec = 8.0)), audio = audio.toList(),
    )

    @Test
    fun musicGetsQuieterWhileSomeoneSpeaksAndComesBackInThePause(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        val video = talk()
        val tune = make("musik.m4a", "-f", "lavfi", "-i", "sine=frequency=2500:duration=8:sample_rate=48000", "-c:a", "aac")
        val preset = VideoRender.PRESETS.first()
        val ducked = VideoRender.render(project(video, AudioClip(id = "m", path = tune.absolutePath, startSec = 0.0, outSec = 8.0, volume = 0.5f, fadeSec = 0.0, duck = true)), preset, File(base, "geduckt.m4a"), audioOnly = true) {}
        val plain = VideoRender.render(project(video, AudioClip(id = "m", path = tune.absolutePath, startSec = 0.0, outSec = 8.0, volume = 0.5f, fadeSec = 0.0)), preset, File(base, "fest.m4a"), audioOnly = true) {}
        val whileTalking = level(ducked, 0.8, 0.8, music)
        val inThePause = level(ducked, 3.2, 1.0, music)
        val afterwards = level(ducked, 6.0, 1.0, music)
        assertTrue(inThePause - whileTalking >= 8.0, "Musik beim Sprechen leiser: $whileTalking dB gegen $inThePause dB in der Pause")
        assertTrue(inThePause - afterwards >= 8.0, "und beim zweiten Sprechen wieder: $afterwards dB")
        // The voice itself is untouched, and without ducking the music stays as loud as it was.
        assertTrue(kotlin.math.abs(level(ducked, 0.8, 0.8, voice) - level(plain, 0.8, 0.8, voice)) < 1.0, "die Stimme bleibt, wie sie war")
        assertTrue(kotlin.math.abs(level(plain, 0.8, 0.8, music) - level(plain, 3.2, 1.0, music)) < 2.0, "ohne Ducking bleibt die Musik gleich")
        // Two ducked sounds and an undocked second voice on A2 work in the same graph.
        val two = project(
            video,
            AudioClip(id = "m1", path = tune.absolutePath, startSec = 0.0, outSec = 4.0, volume = 0.5f, fadeSec = 0.0, duck = true),
            AudioClip(id = "m2", path = tune.absolutePath, startSec = 4.0, outSec = 4.0, volume = 0.5f, fadeSec = 0.0, duck = true, track = 2),
            AudioClip(id = "s", path = video.absolutePath, startSec = 0.0, outSec = 8.0, volume = 0.5f, fadeSec = 0.0, track = 3),
        )
        val mixed = VideoRender.render(two, preset, File(base, "zwei.m4a"), audioOnly = true) {}
        assertTrue(level(mixed, 3.2, 1.0, music) - level(mixed, 6.0, 1.0, music) >= 8.0, "auch mit zwei Musikstücken")
    }

    @Test
    fun quietAndLoudVoicesAreEvenedOut(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // A loud voice for 6 s (peaks at −3 dBFS, as at the lectern), then a quiet one 20 dB below (a question from the room).
        val video = make(
            "saal.mp4", "-f", "lavfi", "-i", "color=c=gray:s=320x180:r=25:d=12",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=12:sample_rate=48000,volume='if(lt(t,6),5.6,0.56)':eval=frame",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        )
        val p = project(video).copy(clips = listOf(VideoClip(id = "v", path = video.absolutePath, inSec = 0.0, outSec = 12.0)))
        val preset = VideoRender.PRESETS.first()
        val before = VideoRender.render(p, preset, File(base, "vorher.m4a"), audioOnly = true) {}
        val after = VideoRender.render(p.copy(sound = p.sound.copy(level = true)), preset, File(base, "nachher.m4a"), audioOnly = true) {}
        val gapBefore = level(before, 3.0, 2.0) - level(before, 9.0, 2.0)
        val gapAfter = level(after, 3.0, 2.0) - level(after, 9.0, 2.0)
        assertTrue(gapBefore > 17.0, "vorher 20 dB Unterschied: $gapBefore")
        assertTrue(gapAfter < 7.0, "nachher fast gleich laut: $gapAfter dB")
        assertTrue(VideoRender.LEVEL_FILTER in VideoRender.graph(p.copy(sound = p.sound.copy(level = true)), 320, 180, 25, File(base, "g"), { Media.probe(it) }).lines.joinToString())
    }

    @Test
    fun soundOnOneSideIsFoundAndPutOnBoth(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // A lapel microphone in the left input of the camera: the right side is silent.
        val video = make(
            "links.mp4", "-f", "lavfi", "-i", "color=c=gray:s=320x180:r=25:d=4",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=4:sample_rate=48000", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono",
            "-filter_complex", "[1:a][2:a]join=inputs=2:channel_layout=stereo[a]", "-map", "0:v", "-map", "[a]",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-t", "4",
        )
        assertEquals("links", SoundFix.oneSided(video))
        assertEquals("", SoundFix.oneSided(talk()), "beide Seiten gleich: nichts zu tun")
        val p = project(video).copy(clips = listOf(VideoClip(id = "v", path = video.absolutePath, inSec = 0.0, outSec = 4.0)))
        val preset = VideoRender.PRESETS.first()
        val asIs = VideoRender.render(p, preset, File(base, "wie.m4a"), audioOnly = true) {}
        val both = VideoRender.render(SoundFix.setClip(p, "v", "links"), preset, File(base, "beide.m4a"), audioOnly = true) {}
        val right = "pan=mono|c0=c1"
        assertTrue(level(asIs, 1.0, 2.0, right) < -60, "vorher rechts still")
        assertTrue(level(both, 1.0, 2.0, right) > -25, "nachher rechts zu hören: ${level(both, 1.0, 2.0, right)} dB")
        assertTrue(level(both, 1.0, 2.0, "pan=mono|c0=c0") > -25, "links bleibt")
    }

    @Test
    fun channelsAreReadFromAstats() {
        val out = """
            [Parsed_astats_0 @ 0x1] Channel: 1
            [Parsed_astats_0 @ 0x1] RMS level dB: -21.073726
            [Parsed_astats_0 @ 0x1] Channel: 2
            [Parsed_astats_0 @ 0x1] RMS level dB: -inf
        """.trimIndent()
        assertEquals(listOf(-21.073726, Double.NEGATIVE_INFINITY), SoundFix.levels(out))
        assertEquals("links", SoundFix.side(SoundFix.levels(out)))
        assertEquals("rechts", SoundFix.side(listOf(-70.0, -18.0)))
        assertEquals("", SoundFix.side(listOf(-20.0, -24.0)), "ungleich laut, aber beide da")
        assertEquals("", SoundFix.side(listOf(-90.0, Double.NEGATIVE_INFINITY)), "still")
        assertEquals("", SoundFix.side(listOf(-20.0)), "mono")
        // Ducking on and off for all music at once leaves linked sound alone.
        val p = VideoProject(title = "x", audio = listOf(AudioClip(id = "m", path = "/m.mp3", startSec = 0.0, outSec = 5.0), AudioClip(id = "l", path = "/v.mp4", startSec = 0.0, outSec = 5.0, linkedTo = "v")))
        assertEquals(listOf(true, false), SoundFix.duck(p, true).audio.map { it.duck })
    }

    // ── (c) Format wechseln, Ausschnitt folgt der Person ──

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

    @Test
    fun theCameraPathIsCalmAndFollowsThePerson() {
        // A person walks from 30 % to 70 % of the picture in 10 s, the detection jitters by ±1,5 %, once it misses.
        val walk = (0..40).map { k ->
            val t = k * 0.5
            val x = (0.3 + 0.4 * (t / 10.0).coerceIn(0.0, 1.0) + if (k % 2 == 0) 0.015 else -0.015).toFloat()
            t to if (k == 13) emptyList() else listOf(Face(x, 0.4f, 0.12f, 0.2f))
        }
        val keys = Reframe.follow(walk)
        assertTrue(keys.first().x in 0.27f..0.36f, "Anfang bei der Person: ${keys.first()}")
        assertTrue(keys.last().x in 0.64f..0.73f, "Ende bei der Person: ${keys.last()}")
        assertTrue(keys.zipWithNext().all { (a, b) -> abs(b.x - a.x) < 0.1f }, "keine Sprünge: $keys")
        assertTrue(keys.all { it.w == 1f }, "nur die Lage bewegt sich")
        // Standing still with jitter: the camera does not move at all.
        val still = (0..30).map { k -> k * 0.5 to listOf(Face(if (k % 2 == 0) 0.42f else 0.40f, 0.4f, 0.1f, 0.2f)) }
        val calm = Reframe.follow(still)
        assertTrue(calm.size <= 2 && calm.all { abs(it.x - calm.first().x) < 0.001f }, "ruhig: $calm")
        // Two people of the same size (an interview): the cut stays with the first one instead of jumping.
        val two = (0..20).map { k -> k * 0.5 to listOf(Face(0.25f, 0.4f, 0.1f, 0.2f), Face(0.75f, 0.4f, 0.1f, 0.2f)).let { if (k % 2 == 0) it else it.reversed() } }
        assertTrue(Reframe.follow(two).all { abs(it.x - 0.25f) < 0.03f }, "bleibt bei einer Person")
        assertEquals(listOf(0.5f), Reframe.follow(listOf(0.0 to emptyList(), 1.0 to emptyList())).map { it.x }, "niemand da: Mitte")
    }

    @Test
    fun anotherFormatFillsTheFrameWithTheClipsThatHaveBars() {
        val info: (File) -> MediaInfo? = { f -> if ("hoch" in f.name) MediaInfo(10.0, 1080, 1920, 25.0, hasVideo = true, hasAudio = true) else MediaInfo(10.0, 1920, 1080, 25.0, hasVideo = true, hasAudio = true) }
        val p = VideoProject(title = "Kurz", clips = listOf(VideoClip(id = "q", path = "/x/quer.mp4", inSec = 0.0, outSec = 5.0), VideoClip(id = "h", path = "/x/hoch.mp4", inSec = 0.0, outSec = 5.0)))
        val hoch = Reframe.toFormat(p, Reframe.format("hoch")!!, info)
        assertEquals(1080 to 1920, hoch.width to hoch.height)
        assertEquals(listOf(true, false), hoch.clips.map { it.fx.fill }, "nur der Querformat-Clip wird zugeschnitten")
        assertEquals(emptyList(), Reframe.barred(hoch, info))
        assertEquals(listOf("h"), Reframe.barred(Reframe.toFormat(p, Reframe.format("quer")!!, info, fill = false), info).map { it.id })
        assertEquals(listOf(false, false), Reframe.unfill(hoch).clips.map { it.fx.fill })
        // The working picture is as sharp as the source allows: 4K in 9:16 keeps 1920 lines, Full HD is not shrunk first.
        assertEquals(3412 to 1920, VideoFx.cutWorkSize(MediaInfo(10.0, 3840, 2160, 25.0, hasVideo = true), 1080, 1920, 9.0 / 16))
        assertEquals(1920 to 1080, VideoFx.cutWorkSize(MediaInfo(10.0, 1920, 1080, 25.0, hasVideo = true), 1080, 1920, 9.0 / 16))
        assertEquals(1080 to 608, VideoFx.workSize(MediaInfo(10.0, 1920, 1080, 25.0, hasVideo = true), 1080, 1920), "vorher")
        // A portrait clip in a landscape frame: the cut is a band across the full width.
        val g = VideoRender.graph(Reframe.toFormat(p, Reframe.format("quer")!!, info), 1920, 1080, 25, File(base, "g"), info).lines.joinToString("\n")
        assertTrue("scale=1080:608,setsar=1" in g, g)
    }

    private fun frameOf(video: File, sec: Double, name: String): BufferedImage {
        val png = File(pictures, name)
        val p = ProcessBuilder(ffmpeg()!!, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-ss", "$sec", "-i", video.absolutePath, "-frames:v", "1", png.absolutePath).redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(60, TimeUnit.SECONDS)
        return ImageIO.read(png)
    }

    private fun yellowShare(img: BufferedImage): Double {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) { val c = img.getRGB(x, y); if ((c shr 16 and 0xff) > 170 && (c shr 8 and 0xff) > 170 && (c and 0xff) < 90) n++ }
        return n.toDouble() / (img.width * img.height)
    }

    private fun dark(img: BufferedImage, x: Int): Boolean = (0 until img.height step 8).all { y -> (img.getRGB(x, y) shr 8 and 0xff) < 30 }

    @Test
    fun aLandscapeRecordingBecomesAPortraitClipThatFollowsThePerson(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // 640×360, grey, a yellow „face“ on the right (x 448–512) that walks to the left half after 2 s.
        val video = make(
            "interview.mp4", "-f", "lavfi", "-i", "color=c=gray:s=640x360:r=25:d=6", "-f", "lavfi", "-i", "color=c=yellow:s=64x64:r=25:d=6",
            "-filter_complex", "[0][1]overlay=x='if(lt(t,2),448,if(lt(t,4),448-(t-2)*150,148))':y=120[v]", "-map", "[v]",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        )
        Faces.override = YellowFaces()
        val p0 = VideoProject(title = "Kurzclip", fadeEnds = false, sound = AudioSettings(normalize = false), clips = listOf(VideoClip(id = "c", path = video.absolutePath, inSec = 0.0, outSec = 6.0)))
        val middle = Reframe.toFormat(p0, Reframe.format("hoch")!!, { Media.probe(it) })
        val keys = Reframe.find(middle.clips[0])
        assertTrue(keys.first().x > 0.7f && keys.last().x < 0.35f, "der Weg folgt dem Gesicht: $keys")
        val following = Reframe.apply(middle, "c", keys)
        val preset = VideoRender.PRESETS.first()
        val a = VideoRender.render(middle, preset, File(base, "mitte.mp4")) {}
        val b = VideoRender.render(following, preset, File(base, "folgt.mp4")) {}
        val atStart = frameOf(a, 1.0, "mitte-1s.png")
        val followedStart = frameOf(b, 1.0, "folgt-1s.png")
        val followedEnd = frameOf(b, 5.0, "folgt-5s.png")
        assertEquals(404 to 720, atStart.width to atStart.height, "Hochformat")
        assertTrue(!dark(atStart, 2) && !dark(atStart, atStart.width - 3), "keine schwarzen Balken")
        assertTrue(yellowShare(atStart) < 0.001, "Mitte: das Gesicht rechts ist nicht im Bild")
        assertTrue(yellowShare(followedStart) > 0.01, "folgt: das Gesicht ist im Bild (Anfang)")
        assertTrue(yellowShare(followedEnd) > 0.01, "folgt: das Gesicht ist im Bild (nachdem es nach links ging)")
    }

    // ── (c) Untertitel-Stile ──

    @Test
    fun subtitleStylesForShortClips() {
        val cue = SubtitleCue(10.0, 13.0, "Heute geht es\num Statistik und Daten")
        // TUD stays exactly as it was.
        fun same(d: io.github.thepytales.llmhub.data.Design) = d.copy(createdAt = 0, updatedAt = 0, pages = d.pages.map { pg -> pg.copy(id = "", elements = pg.elements.map { it.copy(id = "") }) })
        assertEquals(same(VideoTitles.subtitleDesign(cue.text, 1920, 1080, false)), same(CaptionStyles.design(CaptionStyles.TUD, CaptionStyles.parts(CaptionStyles.TUD, cue).single(), 1920, 1080)))
        assertEquals(1, CaptionStyles.parts(CaptionStyles.BIG, cue).size)
        // Wort für Wort: up to three words at a time (fewer when long), one picture per word, end to end over the cue.
        val parts = CaptionStyles.parts(CaptionStyles.WORDS, cue)
        assertEquals(7, parts.size)
        assertEquals(listOf("Heute geht es", "um Statistik und", "Daten"), parts.map { it.text }.distinct())
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), parts.map { it.highlight })
        assertEquals(10.0, parts.first().start, 1e-9)
        assertEquals(13.0, parts.last().end, 1e-9)
        assertTrue(parts.zipWithNext().all { (a, b) -> abs(a.end - b.start) < 1e-9 }, "lückenlos: $parts")
        assertTrue(parts[4].end - parts[4].start > parts[3].end - parts[3].start, "„Statistik“ dauert länger als „um“")
        // Measured word times win.
        val measured = cue.copy(words = listOf(10.0, 10.9, 11.1, 11.8, 12.0, 12.6, 12.7))
        assertEquals(listOf(10.0, 10.9, 11.1, 11.8, 12.0, 12.6, 12.7), CaptionStyles.parts(CaptionStyles.WORDS, measured).map { it.start })
        assertEquals("geht", CaptionStyles.partAt(CaptionStyles.WORDS, measured, 11.0)!!.let { it.text.split(" ")[it.highlight] })

        // The pictures: the spoken word yellow, the others white; large text in the lower middle of a 9:16 frame.
        val word = VideoTitles.render(CaptionStyles.design(CaptionStyles.WORDS, parts[1], 1080, 1920), 540).toAwtImage()
        val big = VideoTitles.render(CaptionStyles.design(CaptionStyles.BIG, parts[0].copy(text = cue.text), 1080, 1920), 540).toAwtImage()
        val tud = VideoTitles.render(VideoTitles.subtitleDesign(cue.text, 1080, 1920), 540).toAwtImage()
        ImageIO.write(word, "png", File(pictures, "untertitel-wort.png"))
        ImageIO.write(big, "png", File(pictures, "untertitel-gross.png"))
        ImageIO.write(tud, "png", File(pictures, "untertitel-tud.png"))
        assertTrue(yellowShare(word) > 0.002, "das gesprochene Wort ist gelb")
        assertTrue(yellowShare(big) < 0.0001, "„Groß“ hat kein Gelb")
        fun textRows(img: BufferedImage) = (0 until img.height).filter { y -> (0 until img.width step 2).any { x -> (img.getRGB(x, y) ushr 24) > 100 } }
        val rows = textRows(big)
        assertTrue(rows.first() > big.height * 0.5 && rows.last() < big.height * 0.8, "untere Bildmitte: ${rows.first()}–${rows.last()} von ${big.height}")
        assertTrue(rows.size > textRows(tud).size * 1.15, "größer als die TUD-Untertitel")
    }

    @Test
    fun wordByWordIsBurnedInOnePicturePerWord() {
        val cue = SubtitleCue(0.5, 2.5, "eins zwei drei vier")
        val p = VideoProject(
            title = "x", width = 1080, height = 1920, burnSubtitles = true, subtitleStyle = CaptionStyles.WORDS, subtitles = listOf(cue),
            clips = listOf(VideoClip(path = "/x/a.mp4", inSec = 0.0, outSec = 3.0)),
        )
        val work = File(base, "w")
        VideoRender.graph(p, 1080, 1920, 25, work, { MediaInfo(3.0, 1920, 1080, 25.0, hasVideo = true, hasAudio = true) })
        val list = File(work, "untertitel.ffconcat").readText()
        assertEquals(4, Regex("untertitel-0-\\d\\.png").findAll(list).count(), list)
        VideoRender.graph(p.copy(subtitleStyle = CaptionStyles.TUD), 1080, 1920, 25, File(base, "t"), { MediaInfo(3.0, 1920, 1080, 25.0, hasVideo = true, hasAudio = true) })
        assertEquals(1, Regex("untertitel-0-\\d\\.png").findAll(File(base, "t/untertitel.ffconcat").readText()).count())
    }

    // ── (c) Höhepunkte als Kurzclip ──

    /** A lecture of 10 minutes: a sentence of 8 words every 20 s (measured word times). */
    private fun lecture(): Transcript {
        val segs = (0 until 30).map { k ->
            val start = k * 20.0 + 1.0
            val words = "Satz $k erklärt einen Gedanken der Vorlesung kurz".split(" ")
            val ws = words.mapIndexed { j, w -> Word(start + j * 0.5, start + j * 0.5 + 0.4, w) }
            Segment(k, ws.first().start, ws.last().end, words.joinToString(" "), 0, ws)
        }
        return Transcript(
            id = "t1", title = "vorlesung", created = "2026-11-27T08:00:00", duration = 600.0, language = "de", modelName = "test", device = "pc",
            speakers = mapOf(0 to "Dozentin"), segments = segs, audio = null, folder = null, marks = emptyList(), notice = null, raw = JsonObject(emptyMap()),
        )
    }

    @Test
    fun highlightsBecomeANewShortProjectThatKeepsWhatProtectsPeople() {
        val path = "/aufnahmen/vorlesung.mp4"
        val ts = mapOf(path to lecture())
        val p = VideoProject(
            title = "Vorlesung 3", sound = AudioSettings(denoise = "leicht", level = true),
            clips = listOf(VideoClip(id = "a", path = path, inSec = 0.0, outSec = 300.0, fx = ClipFx(regions = listOf(io.github.thepytales.llmhub.video.Region(keys = listOf(io.github.thepytales.llmhub.video.Key(0.0)))))), VideoClip(id = "b", path = path, inSec = 300.0, outSec = 600.0)),
            audio = listOf(
                AudioClip(id = "mic", path = "/aufnahmen/ansteck.wav", startSec = 0.0, outSec = 300.0, linkedTo = "a", linkOffset = 0.4, volume = 1f),
                AudioClip(id = "musik", path = "/musik.mp3", startSec = 0.0, outSec = 60.0, duck = true),
            ),
            bleeps = listOf(Bleep(path = path, fromSec = 41.0, toSec = 41.6, name = "Anna")),
            transcripts = mapOf(path to "t1"),
        )
        val passages = Highlights.passages(p, ts)
        assertEquals(30, passages.size)
        assertEquals(listOf(0, 1), passages.map { it.clip }.distinct())
        assertTrue("Satz 3 erklärt" in Highlights.prompt(passages, "Gedanken", 60) && "höchstens 60 Sekunden" in Highlights.prompt(passages, "", 60))
        // The model names more than fits: the clip is cut off at about 60 s (each passage is about 3.9 s).
        val many = Highlights.pick((1..30).joinToString(", "), passages, 20)
        assertTrue(many.sumOf { it.length } <= 20 * 1.15 && many.size in 4..6, "begrenzt: ${many.size}")
        assertEquals(listOf(3, 4, 17), Highlights.pick("17, 3-4", passages, 60).map { it.n }, "Reihenfolge der Zeitleiste")

        val info: (File) -> MediaInfo? = { MediaInfo(600.0, 1920, 1080, 25.0, hasVideo = true, hasAudio = true) }
        val q = Highlights.build(p, Highlights.pick("3, 4, 17", passages, 60), ts, info, Reframe.format("hoch"))
        assertTrue(q.id != p.id && "Kurzclip" in q.title, q.title)
        assertEquals(1080 to 1920, q.width to q.height)
        // Passages 3 and 4 are 20 s apart: two pieces of clip a, one of clip b.
        assertEquals(3, q.clips.size)
        assertTrue(q.clips.all { it.fx.fill }, "füllen das Hochformat")
        assertTrue(q.clips[0].fx.regions.isNotEmpty(), "Verpixelung bleibt")
        assertEquals(41.0 - 0.15, q.clips[0].inSec, 1e-6)
        assertTrue(q.duration < 20.0, "kurz: ${q.duration}")
        // The lapel microphone goes with the pieces of clip a, the music does not.
        assertEquals(2, q.audio.size)
        assertTrue(q.audio.all { a -> a.path.endsWith("ansteck.wav") && q.clips.any { it.id == a.linkedTo } })
        assertEquals(p.bleeps, q.bleeps, "Namen im Ton bleiben geschützt")
        assertEquals(p.sound, q.sound)
        assertTrue(q.burnSubtitles && q.subtitles.isNotEmpty() && q.subtitleStyle == CaptionStyles.WORDS, "Untertitel Wort für Wort")
        assertTrue(q.subtitles.first().words.isNotEmpty(), "gemessene Wortzeiten")
        assertEquals(p.clips.size, 2, "das Original bleibt")
    }

    // ── (d) Mehrkamera-Schnitt nach Sprecher ──

    @Test
    fun shotsFollowTheSpeakerCalmly() {
        // Activity per 0.25 s: A speaks 0–6 s, B 6–12 s, both 12–15 s, A 15–20 s; a short „mhm“ of B at 3 s (0.5 s).
        fun steps(f: (Double) -> Double) = DoubleArray(80) { f(it * MultiCam.STEP) }
        val a = steps { t -> if (t < 6 || t >= 12) 20.0 else 4.0 }
        val b = steps { t -> if (t in 6.0..<15.0 || t in 3.0..<3.5) 20.0 else 4.0 }
        val two = listOf(MultiCam.Angle("/a.mp4"), MultiCam.Angle("/b.mp4"))
        val shots = MultiCam.shots(two, listOf(a, b), 0.0, 20.0)
        assertEquals(listOf(0, 1, 0), shots.map { it.angle }, "$shots")
        assertEquals(6.0, shots[1].from, 0.3)
        assertEquals(15.0, shots[2].from, 0.3)
        assertTrue(shots.all { it.to - it.from >= 2.0 }, "keine kurzen Einstellungen")
        // With a wide shot: it opens the film and comes when both speak.
        val three = two + MultiCam.Angle("/totale.mp4", wide = true)
        val wideShots = MultiCam.shots(three, listOf(a, b, steps { 10.0 }), 0.0, 20.0)
        assertEquals(2, wideShots.first().angle, "beginnt mit der Totale")
        assertTrue(wideShots.any { it.angle == 2 && it.from in 12.0..16.5 }, "Totale, wenn beide sprechen: $wideShots")
        // A camera that started later is not used before it runs.
        val late = listOf(MultiCam.Angle("/a.mp4"), MultiCam.Angle("/b.mp4", offset = -8.0))
        assertTrue(MultiCam.shots(late, listOf(a, b), 0.0, 20.0).filter { it.angle == 1 }.all { it.from >= 8.0 })
    }

    @Test
    fun anInterviewWithTwoCamerasIsCutBySpeakerAndStaysInSync(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // Syllables: a tone switched on and off irregularly; A speaks 0–6 s and 12–18 s, B 6–12 s. Each camera hears its
        // person loud and the other faint; camera B started 1.5 s later.
        val gate = "gt(sin(2*PI*3.1*T)+sin(2*PI*1.3*T)+0.6*sin(2*PI*0.37*T),0.2)"
        val turnA = "(lt(T,6)+between(T,12,18))"
        val turnB = "between(T,6,12)"
        fun voice(t: String, loudA: Double, loudB: Double) =
            "($loudA*$turnA*sin(2*PI*440*T)+$loudB*$turnB*sin(2*PI*660*T))*$gate".replace("T", t)
        val camA = make(
            "kamera-a.mp4", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=25:d=18", "-f", "lavfi", "-i", "aevalsrc=exprs='${voice("t", 0.6, 0.12)}':s=48000:d=18",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        )
        val camB = make(
            "kamera-b.mp4", "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=25:d=16.5", "-f", "lavfi", "-i", "aevalsrc=exprs='${voice("(t+1.5)", 0.12, 0.6)}':s=48000:d=16.5",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        )
        val p = VideoProject(
            title = "Interview", width = 320, height = 180, fadeEnds = false, sound = AudioSettings(normalize = false),
            clips = listOf(VideoClip(id = "a", path = camA.absolutePath, inSec = 0.0, outSec = 18.0)),
        )
        val (q, text) = MultiCam.run(p, "a", listOf(camB))
        assertEquals(listOf(camA, camB, camA).map { it.absolutePath }, q.clips.map { it.path }, text)
        assertTrue(q.clips.all { it.muted }, "das Bild ist stumm, der Ton kommt verknüpft von Kamera A")
        assertEquals(6.0, q.starts()[1], 0.5)
        assertEquals(12.0, q.starts()[2], 0.5)
        assertEquals(q.starts()[1] - 1.5, q.clips[1].inSec, 0.08, "Kamera B synchron (1,5 s später gestartet)")
        assertEquals(3, q.audio.size)
        assertTrue(q.audio.all { it.path == camA.absolutePath })
        assertEquals(18.0, q.duration, 0.05)
        assertTrue("2" in text || "3" in text, text)
        // The export: red while A speaks, blue while B speaks, and the sound of camera A without a gap or a jump.
        val out = VideoRender.render(q, VideoRender.PRESETS.first(), File(base, "interview.mp4")) {}
        fun colour(sec: Double) = frameOf(out, sec, "interview-${sec.toInt()}s.png").getRGB(100, 60)
        assertTrue((colour(3.0) shr 16 and 0xff) > 150 && (colour(3.0) and 0xff) < 80, "A rot bei 3 s")
        assertTrue((colour(9.0) and 0xff) > 150 && (colour(9.0) shr 16 and 0xff) < 80, "B blau bei 9 s")
        assertTrue((colour(15.0) shr 16 and 0xff) > 150, "A rot bei 15 s")
        val a440 = "bandpass=f=440:width_type=h:w=60"
        val b660 = "bandpass=f=660:width_type=h:w=60"
        assertTrue(level(out, 2.0, 2.0, a440) > -12, "A laut, aus Kamera A")
        assertTrue(level(out, 8.0, 2.0, b660) in -30.0..-12.0, "B leise aus Kamera A (nicht laut aus Kamera B): ${level(out, 8.0, 2.0, b660)}")
        assertTrue(level(out, 13.0, 2.0, a440) > -12, "A wieder laut")
    }

    // ── (e) Hervorhebungen für Unterrichtsvideos ──

    private fun red(c: Int) = (c ushr 24) > 150 && (c shr 16 and 0xff) > 180 && (c shr 8 and 0xff) < 110 && (c and 0xff) < 110

    @Test
    fun calloutsPointAtThePlaceAndLeaveTheTudTitlesAlone() {
        val w = 960; val hh = 540
        fun img(o: TextOverlay) = VideoTitles.render(Callouts.design(o, 1920, 1080), w).toAwtImage()
        // A frame around the middle right: red on its edge, nothing inside, nothing far away.
        val frame = img(Callouts.create(Callouts.FRAME, 0.0).copy(x = 0.7f, y = 0.5f, w = 0.2f, h = 0.2f, text = "Hier klicken"))
        ImageIO.write(frame, "png", File(pictures, "hervorhebung-rahmen.png"))
        val left = (0.6 * w).toInt(); val right = (0.8 * w).toInt(); val topY = (0.4 * hh).toInt()
        assertTrue((topY - 3..topY + 3).any { y -> red(frame.getRGB((0.7 * w).toInt(), y)) }, "oberer Rand rot")
        assertTrue((left - 3..left + 3).any { x -> red(frame.getRGB(x, hh / 2)) }, "linker Rand rot")
        assertTrue(!red(frame.getRGB((0.7 * w).toInt(), hh / 2)) && (frame.getRGB((0.7 * w).toInt(), hh / 2) ushr 24) < 20, "innen frei")
        assertTrue((frame.getRGB(50, 50) ushr 24) < 5, "weit weg nichts")
        assertTrue((hh * 6 / 10 + 5 until hh * 8 / 10).any { y -> red(frame.getRGB((0.7 * w).toInt(), y)) }, "Beschriftung darunter")
        assertTrue(right < w)
        // An arrow from below left to its tip in the middle.
        val arrow = img(Callouts.create(Callouts.ARROW, 0.0))
        ImageIO.write(arrow, "png", File(pictures, "hervorhebung-pfeil.png"))
        assertTrue((-6..6).any { d -> red(arrow.getRGB(w / 2 - 8 + d, hh / 2 + 8 + d)) }, "Spitze an der Mitte")
        assertTrue((-6..6).any { d -> red(arrow.getRGB((0.36 * w).toInt() + d, (0.68 * hh).toInt() - d)) }, "Schaft nach links unten")
        assertTrue(!red(arrow.getRGB((0.6 * w).toInt(), (0.4 * hh).toInt())), "nicht hinter der Spitze")
        // The spot: dark around, clear inside.
        val spot = img(Callouts.create(Callouts.SPOT, 0.0))
        ImageIO.write(spot, "png", File(pictures, "hervorhebung-spot.png"))
        assertTrue((spot.getRGB(30, 30) ushr 24) in 120..170, "außen abgedunkelt")
        assertTrue((spot.getRGB(w / 2, hh / 2) ushr 24) < 5, "innen klar")
        // The marker: a yellow, see-through bar.
        val marker = img(Callouts.create(Callouts.MARKER, 0.0))
        val m = marker.getRGB(w / 2, hh / 2)
        assertTrue((m ushr 24) in 60..140 && (m shr 16 and 0xff) > 200 && (m and 0xff) < 120, "gelb, durchscheinend: ${Integer.toHexString(m)}")
        // Titles in the TUD design are drawn as before.
        val title = TextOverlay(style = "bauchbinde", text = "Prof. Muster", subtext = "Professur", startSec = 0.0)
        fun same(d: io.github.thepytales.llmhub.data.Design) = d.copy(createdAt = 0, updatedAt = 0, pages = d.pages.map { pg -> pg.copy(id = "", elements = pg.elements.map { it.copy(id = "") }) })
        assertEquals(same(VideoTitles.design(title, 1920, 1080)), same(Callouts.designOf(title, 1920, 1080)))
        assertEquals("Pfeil", TextOverlay.label(Callouts.ARROW))
        assertEquals(0.22f to 0.75f, Callouts.place("unten links"))
        // Side by side: camera left, slides right.
        assertEquals(Frame(x = 0.25f, y = 0.5f, scale = 0.5f), Frame.corner("links-halb"))
    }

    @Test
    fun aCalloutIsInTheExportedFilm(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        val video = make("screencast.mp4", "-f", "lavfi", "-i", "color=c=gray:s=640x360:r=25:d=4", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p")
        val o = Callouts.create(Callouts.FRAME, 1.0).copy(x = 0.3f, y = 0.4f, w = 0.25f, h = 0.25f, durationSec = 2.0)
        val p = VideoProject(title = "Screencast", width = 640, height = 360, fadeEnds = false, clips = listOf(VideoClip(path = video.absolutePath, inSec = 0.0, outSec = 4.0)), overlays = listOf(o))
        val out = VideoRender.render(p, VideoRender.PRESETS.first(), File(base, "hervorhebung.mp4")) {}
        val during = frameOf(out, 2.0, "hervorhebung-export.png")
        val before = frameOf(out, 0.3, "hervorhebung-vorher.png")
        fun redCount(img: BufferedImage) = (0 until img.height step 2).sumOf { y -> (0 until img.width step 2).count { x -> val c = img.getRGB(x, y) or (0xff shl 24); red(c) } }
        assertTrue(redCount(during) > 200, "Rahmen im Film: ${redCount(during)}")
        assertEquals(0, redCount(before), "vorher nicht")
    }

    // ── Kana ──

    @Test
    fun kanaDucksLevelsChangesTheFormatSetsCalloutsAndStyles(): Unit = runBlocking {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(base, "daten").absolutePath)
        val c = AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() }))
        fun args(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.JsonObject(pairs.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        io.github.thepytales.llmhub.video.VideoLora.register()
        val ids = listOf("video.duck", "video.level_voices", "video.format", "video.subtitle_style", "video.highlights", "video.multicam", "video.callout")
        assertTrue(io.github.thepytales.llmhub.agent.ToolActions.forArea(io.github.thepytales.llmhub.agent.ActionArea.VIDEO, android = false).map { it.id }.containsAll(ids))
        val store = io.github.thepytales.llmhub.ui.VideoHub.store
        val p = store.save(VideoProject(
            title = "Kana", clips = listOf(VideoClip(path = "/x/v.mp4", inSec = 0.0, outSec = 60.0)), subtitles = listOf(SubtitleCue(1.0, 3.0, "Hallo zusammen")),
            audio = listOf(AudioClip(id = "m", path = "/x/musik.mp3", startSec = 0.0, outSec = 60.0)),
        ))
        io.github.thepytales.llmhub.video.VideoLora.lastId = p.id
        fun run(id: String, vararg a: Pair<String, String>) = runBlocking { io.github.thepytales.llmhub.agent.ToolActions.byId(id)!!.plan(c, args(*a)).run() }
        val duck = run("video.duck")
        assertTrue(store.get(p.id)!!.audio.single().duck)
        duck.undo!!()
        assertTrue(!store.get(p.id)!!.audio.single().duck, "Rückgängig")
        run("video.level_voices")
        assertTrue(store.get(p.id)!!.sound.level)
        run("video.subtitle_style", "stil" to "Wort für Wort")
        assertEquals(CaptionStyles.WORDS, store.get(p.id)!!.subtitleStyle)
        run("video.callout", "art" to "pfeil", "wo" to "oben rechts", "text" to "Hier", "zeit" to "0:10")
        val o = store.get(p.id)!!.overlays.single()
        assertEquals(Callouts.ARROW, o.style); assertEquals(10.0, o.startSec); assertEquals(0.78f to 0.25f, o.x to o.y); assertEquals("Hier", o.text)
        // The format: without a readable file the clip keeps its bars, but the frame changes.
        run("video.format", "format" to "Hochformat", "person_folgen" to "nein")
        assertEquals(1080 to 1920, store.get(p.id)!!.let { it.width to it.height })
        assertEquals(Reframe.format("quadrat"), io.github.thepytales.llmhub.video.VideoEverydayLora.formatOf("1:1"))
        assertEquals(Reframe.format("hoch45"), io.github.thepytales.llmhub.video.VideoEverydayLora.formatOf("4:5"))
        io.github.thepytales.llmhub.video.VideoLora.lastId = null
    }
}
