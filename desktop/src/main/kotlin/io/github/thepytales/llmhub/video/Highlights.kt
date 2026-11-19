package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.ChatMessage
import io.github.thepytales.llmhub.net.ThinkingLevel
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.ui.tr
import java.util.Locale

/**
 * Höhepunkte aus einer langen Aufnahme als Clip (AP-129, wie „Highlights“ in CapCut und Clipchamp): Lora liest das
 * Transkript einer Vorlesung oder eines Gesprächs und wählt die Abschnitte, die zusammen einen kurzen Clip ergeben
 * (30 s bis 3 min) – eine starke Aussage, ein Beispiel, eine Pointe. Daraus wird ein **neues** Projekt (das lange bleibt
 * unverändert), auf Wunsch gleich im Hochformat mit großen Untertiteln.
 */
object Highlights {
    /** Lengths offered in the editor (seconds). */
    val LENGTHS = listOf(30, 60, 90, 180)

    /** A spoken passage of a clip on V1: source time of [clip], its text. */
    data class Passage(val clip: VideoClip, val start: Double, val end: Double, val text: String) {
        val length: Double get() = end - start
    }

    private fun clock(sec: Double): String {
        val s = sec.toInt()
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** The spoken passages of all clips of V1 that have a transcript, in the order of the timeline. */
    fun passages(p: VideoProject, transcripts: Map<String, Transcript>): List<Passage> = p.clips.flatMap { c ->
        val t = transcripts[c.path] ?: return@flatMap emptyList()
        if (c.fx.freeze || c.fx.reverse) return@flatMap emptyList()
        t.segments.filter { it.end > c.inSec && it.start < c.outSec && it.text.isNotBlank() }
            .map { Passage(c, maxOf(it.start, c.inSec), minOf(it.end, c.outSec), it.text.trim()) }
    }

    /** The question to the model: numbered passages with times, the length, the wish. */
    fun prompt(passages: List<Passage>, seconds: Int, wish: String): String {
        val list = passages.mapIndexed { i, s -> "${i + 1} [${clock(s.start)}–${clock(s.end)}, ${Math.round(s.length)} s] ${s.text}" }.joinToString("\n")
        return "Du machst aus einer langen Aufnahme (Vorlesung, Interview oder Unterricht) einen kurzen Clip von höchstens $seconds Sekunden. " +
            "Unten stehen die gesprochenen Abschnitte, nummeriert, mit Zeit und Länge. Wähle die Abschnitte, die zusammen den stärksten " +
            "kurzen Clip ergeben: eine klare Aussage, ein anschauliches Beispiel oder eine Pointe, die man ohne den Rest versteht. " +
            "Ganze Gedanken, keine halben Sätze; zusammen höchstens $seconds Sekunden; die Reihenfolge bleibt." +
            (if (wish.isNotBlank()) " Wunsch: „${wish.trim()}“." else "") +
            " Antworte NUR mit den Nummern, durch Kommas getrennt (Bereiche wie 3-5 erlaubt), ohne weiteren Text.\n\n$list"
    }

    /**
     * The passages the model chose ([answer]), in timeline order, at most [seconds] long together (later ones are left
     * out; one passage alone is cut at the length), with a little air before and after each.
     */
    fun choose(passages: List<Passage>, answer: String, seconds: Int): List<Passage> {
        val keep = RoughCut.numbers(answer, passages.size).sorted().map { passages[it - 1] }
        val out = mutableListOf<Passage>()
        var total = 0.0
        for (s in keep) {
            val padded = s.copy(start = (s.start - 0.15).coerceAtLeast(s.clip.inSec), end = (s.end + 0.25).coerceAtMost(s.clip.outSec))
            if (total + padded.length > seconds + 1.0) {
                if (out.isEmpty()) out += padded.copy(end = padded.start + seconds)
                break
            }
            // Passages right after each other in the same clip become one piece.
            val last = out.lastOrNull()
            if (last != null && last.clip.id == s.clip.id && padded.start - last.end <= 1.0 && padded.start >= last.start) {
                total += padded.end - last.end
                out[out.lastIndex] = last.copy(end = padded.end, text = last.text + " " + s.text)
            } else {
                total += padded.length
                out += padded
            }
        }
        return out
    }

    /** Asks the default model for the highlights; throws with a clear message when nothing is spoken or chosen. */
    suspend fun pick(c: AppContainer, passages: List<Passage>, seconds: Int, wish: String): List<Passage> {
        require(passages.isNotEmpty()) { tr("Für die Höhepunkte braucht ein Clip ein Transkript – unter Untertitel › Aus der Transkription zuordnen.") }
        val result = c.llm.streamChat(model = c.settings.current.defaultModel, messages = listOf(ChatMessage(role = "user", content = prompt(passages, seconds, wish))), tools = null, thinking = ThinkingLevel.OFF) { }
        val chosen = choose(passages, result.content, seconds)
        require(chosen.isNotEmpty()) { tr("Lora hat keine Abschnitte ausgewählt.") }
        return chosen
    }

    /**
     * The new project of the highlights: the chosen pieces one after another (with the clips' picture effects), the
     * subtitles from the transcripts, the sound settings of [p]; in [format] when given (the window is set later by
     * [Reframe]) and with [subtitleStyle].
     */
    fun project(p: VideoProject, chosen: List<Passage>, transcripts: Map<String, Transcript>, format: Reframe.Format?, subtitleStyle: String): VideoProject {
        val clips = chosen.map { s -> s.clip.copy(id = newVideoId(), inSec = s.start, outSec = s.end, transition = "none") }
        var q = VideoProject(
            title = tr("{0} – Höhepunkte", p.title), width = format?.width ?: p.width, height = format?.height ?: p.height, fps = p.fps,
            clips = clips, sound = p.sound, fadeEnds = true, transcripts = p.transcripts.filterKeys { k -> clips.any { it.path == k } },
            bleeps = p.bleeps, nameList = p.nameList, subtitleStyle = subtitleStyle,
        )
        q = VideoEdit.withPool(q)
        val cues = RoughCut.subtitles(q, transcripts)
        return q.copy(subtitles = cues, burnSubtitles = cues.isNotEmpty())
    }
}
