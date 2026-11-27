package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.ChatMessage
import io.github.thepytales.llmhub.net.ThinkingLevel
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.ui.tr
import java.io.File

/**
 * Höhepunkte als Kurzclip (AP-129 c): from a long recording (a lecture, an interview) Kana picks the strongest passages
 * of the transcript for a clip of 30–90 s, and a **new** project is made from them – the original stays as it is. The
 * new project keeps what protects people (pixelated faces, changed voices, names in the sound, the sound of a lapel
 * microphone) and gets subtitles and, on request, the format of a short clip that follows the person ([Reframe]).
 */
object Highlights {
    /** A spoken passage [n] (from 1) of V1 clip [clip]: [from]..[to] in its source, [at] on the timeline. */
    data class Passage(val n: Int, val clip: Int, val from: Double, val to: Double, val text: String, val at: Double) {
        val length: Double get() = to - from
    }

    /**
     * The passages of [p] from the transcripts [ts] (source path → transcript): each segment within a clip of V1, in
     * the order of the timeline. Frozen and reversed clips have no passages; a clip with a speed keeps its source times.
     */
    fun passages(p: VideoProject, ts: Map<String, Transcript>): List<Passage> {
        val starts = p.starts()
        val out = mutableListOf<Passage>()
        p.clips.forEachIndexed { i, c ->
            if (c.fx.freeze || c.fx.reverse) return@forEachIndexed
            val t = ts[c.path] ?: return@forEachIndexed
            t.segments.filter { it.end > c.inSec + 0.1 && it.start < c.outSec - 0.1 && it.text.isNotBlank() }.forEach { s ->
                val a = maxOf(s.start, c.inSec)
                val b = minOf(s.end, c.outSec)
                out += Passage(out.size + 1, i, a, b, s.text.trim(), starts[i] + (a - c.inSec) / c.speed)
            }
        }
        return out
    }

    private fun clock(sec: Double): String = (sec.toInt()).let { "%d:%02d".format(it / 60, it % 60) }

    /** What the model gets: the numbered passages with time and length, the wish and the length of the clip. */
    fun prompt(passages: List<Passage>, wish: String, seconds: Int): String {
        val list = passages.joinToString("\n") { "${it.n} [${clock(it.at)}, ${"%.0f".format(it.length)} s] ${it.text}" }
        val topic = wish.trim().takeIf { it.isNotEmpty() }?.let { " Worum es gehen soll: „$it“." }.orEmpty()
        return "Du machst aus einer langen Aufnahme (Vorlesung, Interview, Vortrag) einen kurzen Clip für Social Media oder als Vorschau, " +
            "höchstens $seconds Sekunden lang.$topic Unten stehen die gesprochenen Abschnitte, nummeriert, mit Zeit und Länge. " +
            "Wähle die stärksten Stellen: verständlich ohne den Rest, mit einem klaren Gedanken, einer Pointe oder einem guten Beispiel; " +
            "ganze Sätze, die Summe der Längen höchstens $seconds Sekunden. Antworte NUR mit den Nummern in der Reihenfolge, " +
            "in der sie im Clip stehen sollen, durch Kommas getrennt (Bereiche wie 3-5 erlaubt), ohne weiteren Text.\n\n$list"
    }

    /**
     * The passages named in [answer] (numbers, ranges), in the order of the timeline, cut off when the clip would get
     * much longer than [seconds] – at least one passage.
     */
    fun pick(answer: String, passages: List<Passage>, seconds: Int): List<Passage> {
        val chosen = RoughCut.numbers(answer, passages.size).sorted().map { passages[it - 1] }
        val out = mutableListOf<Passage>()
        var total = 0.0
        for (pa in chosen) {
            if (out.isNotEmpty() && total + pa.length > seconds * 1.15) break
            out += pa
            total += pa.length
        }
        return out
    }

    /** Asks the default model for the passages (pseudonymised like every request of the app). */
    suspend fun choose(c: AppContainer, passages: List<Passage>, wish: String, seconds: Int): List<Passage> {
        require(passages.isNotEmpty()) { tr("Im Video ist nichts gesprochen – oder es gibt kein Transkript.") }
        val model = c.settings.current.defaultModel
        val result = c.llm.streamChat(model = model, messages = listOf(ChatMessage(role = "user", content = prompt(passages, wish, seconds))), tools = null, thinking = ThinkingLevel.OFF) { }
        return pick(result.content, passages, seconds).ifEmpty { throw IllegalStateException(tr("Kana hat keine Stellen ausgewählt.")) }
    }

    /**
     * The new project from [chosen]: one clip per passage (neighbours of the same clip within a second joined, a little
     * room before and after), with the clip's effects, sides of the sound and linked sound; the names in the sound,
     * the transcripts and the sound settings of [p]; subtitles from [ts], burned in („Wort für Wort“ for a short-clip
     * format); in [format] with the clips filling the frame. Music, titles, markers and pictures above are left out.
     */
    fun build(p: VideoProject, chosen: List<Passage>, ts: Map<String, Transcript>, info: (File) -> MediaInfo?, format: Reframe.Format? = null): VideoProject {
        require(chosen.isNotEmpty()) { tr("Kana hat keine Stellen ausgewählt.") }
        // Neighbouring passages of one clip become one piece.
        val pieces = mutableListOf<Triple<Int, Double, Double>>()
        for (pa in chosen.sortedWith(compareBy({ it.at }))) {
            val last = pieces.lastOrNull()
            if (last != null && last.first == pa.clip && pa.from - last.third <= 1.0 && pa.from >= last.second) pieces[pieces.lastIndex] = Triple(last.first, last.second, maxOf(last.third, pa.to))
            else pieces += Triple(pa.clip, pa.from, pa.to)
        }
        val clips = mutableListOf<VideoClip>()
        val sounds = mutableListOf<AudioClip>()
        for ((i, a, b) in pieces) {
            val src = p.clips[i]
            val clip = src.copy(id = newVideoId(), inSec = (a - 0.15).coerceAtLeast(src.inSec), outSec = (b + 0.25).coerceAtMost(src.outSec), transition = "none")
            clips += clip
            // Linked sound (a lapel microphone, the clip's own sound on a track) goes with its piece.
            p.audio.filter { it.linkedTo == src.id }.forEach { s -> sounds += s.copy(id = newVideoId(), linkedTo = clip.id) }
        }
        var q = VideoProject(
            title = tr("{0} – Kurzclip", p.title), width = p.width, height = p.height, fps = p.fps, clips = clips, audio = sounds,
            sound = p.sound, fadeEnds = true, transcripts = p.transcripts, bleeps = p.bleeps, nameList = p.nameList, privacyCheck = p.privacyCheck,
            audioTracks = maxOf(1, sounds.maxOfOrNull { it.track } ?: 1), trackStates = p.trackStates.filterKeys { it == "V1" || it.startsWith("A") },
            subtitleStyle = p.subtitleStyle,
        ).linked()
        q = VideoEdit.addToPool(q, clips.map { it.path }.distinct())
        if (format != null) q = Reframe.toFormat(q, format, info)
        val subs = RoughCut.subtitles(q, ts)
        val short = q.height >= q.width
        return q.copy(subtitles = subs, burnSubtitles = subs.isNotEmpty(), subtitleStyle = if (short) CaptionStyles.WORDS else q.subtitleStyle)
    }
}
