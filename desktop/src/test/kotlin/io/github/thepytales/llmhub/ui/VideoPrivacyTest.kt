package io.github.thepytales.llmhub.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.transcribe.Segment
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.transcribe.Word
import io.github.thepytales.llmhub.video.Face
import io.github.thepytales.llmhub.video.FaceDetector
import io.github.thepytales.llmhub.video.Faces
import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.NameGuard
import io.github.thepytales.llmhub.video.RoughCut
import io.github.thepytales.llmhub.video.TextCut
import io.github.thepytales.llmhub.video.VideoClip
import io.github.thepytales.llmhub.video.VideoPlayer
import io.github.thepytales.llmhub.video.VideoProject
import io.github.thepytales.llmhub.video.VideoRender
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Datenschutz in Unterrichtsvideos (AP-128): an Unterrichtsvideo with three names in the sound and two faces is made
 * clean in a few steps – the Prüfliste finds the faces, the names in the sound and in the subtitles, *Beheben* pixelates
 * and beeps, the export is then silent or beeps exactly where the names were; subtitles and the text show „[Name]“.
 * With a test sound (440 Hz), a test picture (yellow squares as faces) and a test detector.
 */
@OptIn(ExperimentalTestApi::class)
class VideoPrivacyTest {
    private val base = Files.createTempDirectory("adeu-ap128").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }

    @AfterTest
    fun cleanUp() {
        Faces.override = null
        base.deleteRecursively()
    }

    private fun ffmpeg(): String? = Media.ffmpeg()?.takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrNull() == true }

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

    private val path = "/videos/unterricht.mp4"

    /** „Danke, Anna. … Ben, du bist dran. … Frau Kaltenbach hat recht.“ – three names at 1.0, 2.5 and 4.4 s. */
    private fun transcript(): Transcript {
        fun seg(id: Int, start: Double, words: List<String>): Segment {
            var t = start
            val ws = words.map { w -> Word(t, t + 0.4, w).also { t += 0.5 } }
            return Segment(id, ws.first().start, ws.last().end, words.joinToString(" "), 0, ws)
        }
        return Transcript(
            id = "t1", title = "Unterricht", created = "2026-11-17T08:00:00", duration = 6.0, language = "de", modelName = "test", device = "pc",
            speakers = mapOf(0 to "Lehrkraft"), segments = listOf(
                seg(0, 0.5, listOf("Danke,", "Anna.")),
                seg(1, 2.5, listOf("Ben,", "du", "bist", "dran.")),
                seg(2, 3.9, listOf("Frau", "Kaltenbach", "hat", "recht.")),
            ),
            audio = null, folder = null, marks = emptyList(), notice = null, raw = JsonObject(emptyMap()),
        )
    }

    private fun project(file: String = path) = VideoProject(
        title = "Unterricht", fadeEnds = false, nameList = "Anna\nBen",
        clips = listOf(VideoClip(id = "c1", path = file, inSec = 0.0, outSec = 3.0), VideoClip(id = "c2", path = file, inSec = 3.0, outSec = 6.0)),
    )

    @Test
    fun namesAreFoundMaskedAndKeptByTheirSourceTime() {
        val p = project()
        val ts = mapOf(path to transcript())
        val found = NameGuard.find(p, ts, NameGuard.ownList(p.nameList))
        assertEquals(listOf("Anna", "Ben", "Kaltenbach"), found.map { it.name }, "eigene Liste und Namensschutz (Frau …)")
        assertEquals(1.0 - NameGuard.PAD, found[0].fromSec, 1e-9)
        // Settings of a place stay when searching again; the genitive and „[Name]“ in texts.
        val q = p.copy(bleeps = found.map { if (it.name == "Ben") it.copy(mode = NameGuard.STUMM, checked = true) else it })
        val again = NameGuard.find(q, ts, NameGuard.ownList(p.nameList))
        assertEquals(NameGuard.STUMM, again.first { it.name == "Ben" }.mode)
        assertTrue(again.first { it.name == "Ben" }.checked)
        assertEquals("Danke, [Name]! Das ist [Name] Heft.", NameGuard.mask("Danke, Anna! Das ist Annas Heft.", listOf("Anna")))
        assertEquals("[Name] hat recht.", NameGuard.mask("Anna Kaltenbach hat recht.", listOf("Anna", "Kaltenbach")))
        // The text of the video and the subtitles from the transcript show „[Name]“.
        val doc = TextCut.build(q, ts)
        assertEquals(3, doc.tokens.count { it.text == NameGuard.MASK })
        assertTrue(doc.tokens.none { it.text.startsWith("Anna") || it.text.startsWith("Kaltenbach") })
        val subs = RoughCut.subtitles(q, ts)
        assertTrue(subs.none { "Anna" in it.text || "Kaltenbach" in it.text || "Ben" in it.text } && subs.any { NameGuard.MASK in it.text }, subs.toString())
        // A cut that moves the clip keeps the place (source time), and it lies on the timeline where the clip is.
        val moved = q.copy(clips = q.clips.reversed())
        assertEquals(0.0 + (3.9 + 0.5 - NameGuard.PAD - 3.0), NameGuard.timelineAt(moved, again.first { it.name == "Kaltenbach" })!!, 1e-6)
    }

    @Test
    fun theFiltersOfTheGraph() {
        val (none, head0) = NameGuard.soundChain(emptyList(), path, 0.0, 3.0, "[0:a]", "aformat=x", "c0")
        assertTrue(none.isEmpty())
        assertEquals("[0:a]aformat=x,", head0)
        val b = NameGuard.find(project(), mapOf(path to transcript()), listOf("Anna", "Ben"))
        val (lines, head) = NameGuard.soundChain(b, path, 0.0, 3.0, "[0:a]", "aformat=x", "c0")
        assertEquals(3, lines.size, "Piepen: stumm, Sinuston, mischen")
        assertEquals("[nmc0]", head)
        assertTrue("between(t,0.940,1.460)" in lines[0] && "between(t,2.440,2.960)" in lines[0], lines[0])
        assertTrue("Kaltenbach" !in lines.joinToString() && "4.3" !in lines[0], "nur die Stellen dieses Stücks")
        val (stumm, h2) = NameGuard.soundChain(b.map { it.copy(mode = NameGuard.STIMME) }, path, 0.0, 3.0, "[0:a]", "aformat=x", "c0")
        assertTrue(stumm.isEmpty() && "acrusher" in h2 && h2.endsWith(","), h2)
    }

    /** max_volume of [f] from [from] for [len] s, after [filter]. */
    private fun loudness(f: File, from: Double, len: Double, filter: String = "anull"): Double {
        val p = ProcessBuilder(ffmpeg()!!, "-nostdin", "-hide_banner", "-ss", "$from", "-t", "$len", "-i", f.absolutePath, "-af", "$filter,volumedetect", "-f", "null", "-")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return Regex("max_volume: (-?[0-9.]+) dB").find(out)?.groupValues?.get(1)?.toDouble() ?: -200.0
    }

    @Test
    fun aLessonVideoWithThreeNamesAndTwoFacesBecomesCleanInAFewSteps(): Unit = runBlocking {
        if (ffmpeg() == null) return@runBlocking
        // 6 s: grey with a yellow square (a face) in both halves, a 440 Hz tone as the voice.
        val video = File(base, "unterricht.mp4")
        val mk = ProcessBuilder(
            ffmpeg()!!, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=gray:s=640x360:r=25:d=6", "-f", "lavfi", "-i", "color=c=yellow:s=64x64:r=25:d=6",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=6:sample_rate=48000",
            "-filter_complex", "[0][1]overlay=x=200:y=140[v]", "-map", "[v]", "-map", "2:a", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest", video.absolutePath,
        ).redirectErrorStream(true).start()
        mk.inputStream.readAllBytes()
        mk.waitFor(120, TimeUnit.SECONDS)
        Faces.override = YellowFaces()
        val ts = mapOf(video.absolutePath to transcript())
        var p = project(video.absolutePath)
        p = p.copy(subtitles = RoughCut.subtitles(p, ts))

        // Die Prüfliste: two clips with faces, three names in the sound, names in the subtitles.
        val issues = privacyIssues(p, ts)
        assertEquals(listOf(PrivacyIssue.Kind.FACES, PrivacyIssue.Kind.NAMES_SOUND, PrivacyIssue.Kind.NAMES_SUBTITLES), issues.map { it.kind }, issues.toString())
        assertEquals(2, issues[0].clips.size)
        assertTrue("3 Name(n) im Ton" in issues[1].text, issues[1].text)

        // Beheben, as the buttons do: pixelate, beep, mask.
        for (id in issues[0].clips) { val clip = p.clips.first { it.id == id }; val found = Faces.find(clip); p = Faces.apply(p, id, found) }
        p = p.copy(bleeps = issues[1].bleeps)
        p = p.copy(subtitles = p.subtitles.map { it.copy(text = NameGuard.mask(it.text, issues[2].names)) })
        assertTrue(p.clips.all { it.fx.regions.isNotEmpty() }, "beide Clips verpixelt")
        assertEquals(emptyList(), privacyIssues(p, ts), "danach nichts mehr offen")

        // The sound of the export: the names beeped, the voice elsewhere as it was; „Stumm“ silent.
        val preset = VideoRender.PRESETS.first()
        val out = VideoRender.render(p, preset, File(base, "sauber.m4a"), audioOnly = true) {}
        assertTrue(loudness(out, 1.05, 0.3, "bandpass=f=440:width_type=h:w=60") < loudness(out, 1.8, 0.3, "bandpass=f=440:width_type=h:w=60") - 20, "Anna: die Stimme ist weg")
        val beep = loudness(out, 1.05, 0.3, "bandpass=f=1000:width_type=h:w=80")
        val between = loudness(out, 1.8, 0.3, "bandpass=f=1000:width_type=h:w=80")
        assertTrue(beep > -25, "Anna: es piept ($beep dB)")
        assertTrue(between < beep - 10, "zwischen den Namen kein Piepen ($between dB gegen $beep dB)")
        val muted = VideoRender.render(p.copy(bleeps = p.bleeps.map { it.copy(mode = NameGuard.STUMM) }), preset, File(base, "stumm.m4a"), audioOnly = true) {}
        assertTrue(loudness(muted, 2.5, 0.3) < -60, "Ben: stumm")
        assertTrue(loudness(muted, 3.3, 0.3) > -20, "danach wieder Ton")
        val disguised = VideoRender.render(p.copy(bleeps = p.bleeps.map { it.copy(mode = NameGuard.STIMME) }), preset, File(base, "stimme.m4a"), audioOnly = true) {}
        assertTrue(loudness(disguised, 4.45, 0.3) > -30, "Kaltenbach: zu hören, aber verfremdet")
        assertTrue(loudness(disguised, 0.4, 0.3) > -20, "der Rest des Films bleibt laut (kein NaN im Graphen)")
    }

    // ── The dialog ──────────────────────────────────────────────────────────────────────────────────────────────

    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }
    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    private fun SkikoComposeUiTest.settle(rounds: Int = 6) {
        repeat(rounds) { Thread.sleep(60); mainClock.advanceTimeBy(200) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.has(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theDialogFindsTicksAndChangesTheWay() = runSkikoComposeUiTest(Size(1200f, 1000f), Density(1f)) {
        mainClock.autoAdvance = false
        val h = History(project())
        val player = VideoPlayer()
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) { NameGuardDialog(c, h, player, onDismiss = {}, load = { mapOf(path to transcript()) }) }
            }
        }
        settle()
        onAllNodesWithText("Namen suchen").onFirst().performClick()
        repeat(20) { if (h.project.bleeps.isEmpty()) settle(2) }
        assertEquals(3, h.project.bleeps.size)
        assertTrue(has("3 Stelle(n) · 0 geprüft") && has("Kaltenbach"))
        // „Alle: Stumm“, then one back to Piepen; tick all.
        onAllNodesWithText("Alle: Stumm").onFirst().performClick()
        settle(2)
        assertTrue(h.project.bleeps.all { it.mode == NameGuard.STUMM })
        onAllNodesWithText("Alle abhaken").onFirst().performClick()
        settle(2)
        assertTrue(h.project.bleeps.all { it.checked } && has("3 Stelle(n) · 3 geprüft"))
        player.dispose()
    }
}
