package io.github.thepytales.llmhub.transcribe

import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.ChatMessage
import io.github.thepytales.llmhub.data.PiiDetector
import io.github.thepytales.llmhub.data.PiiHit
import io.github.thepytales.llmhub.data.PiiKind
import io.github.thepytales.llmhub.net.ThinkingLevel
import io.github.thepytales.llmhub.ui.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Anonymized (pseudonymized) copies of transcripts for passing on and for research (AP-136 a): names, places,
 * institutions, contact details, addresses and the user's own list (Einstellungen › Datenschutz) become pseudonyms,
 * consistently in the whole transcript; speakers with real names become „Person A“, „Person B“. Kana Namensschutz
 * (the recognition model, AP-67) finds them where it is there ([findings] with `detect`). The original stays
 * unchanged; the codings stay in the copy, their marked words moved with the text. The key list (original →
 * pseudonym) goes only into the Tresor ([io.github.thepytales.llmhub.ui.KeyList]).
 */
object TranscriptAnon {
    data class Result(val transcript: Transcript, val mapping: List<Pair<String, String>>)

    /** One thing found: the original, its pseudonym and how often it occurs (for checking before replacing). */
    data class Finding(val value: String, val pseudonym: String, val count: Int)

    /** Speaker labels that are already anonymous (I, B1, Interviewer, Sprecher 2 …). */
    private val NEUTRAL = Regex("^(I\\d*|B\\d*|IP\\d*|Interviewer(in)?|Befragte[rn]?\\s*\\d*|Sprecher(in)?\\s*\\d*|Person\\s+[A-Z]|L|S\\d*|Lehrkraft|Schüler(in)?\\s*\\d*|Moderation)$", RegexOption.IGNORE_CASE)

    fun anonymize(t: Transcript, ownTerms: List<String>, extra: List<String> = emptyList()): Result =
        apply(t, findings(t, ownTerms, extra).map { it.value to it.pseudonym })

    /**
     * What would be replaced in [t]: speakers with real names, what [detect] finds (default: the rules of
     * [PiiDetector]; the app passes Kana Namensschutz) and the words of [extra] (added by hand while checking).
     */
    fun findings(t: Transcript, ownTerms: List<String>, extra: List<String> = emptyList(), detect: ((String) -> List<PiiHit>)? = null): List<Finding> {
        val coding = TranscriptCoding.of(t)
        val texts = t.segments.flatMap { listOfNotNull(it.text, it.comment) } + t.title + coding.codings.mapNotNull { it.memo }
        val hits = texts.flatMap { text ->
            val found = detect?.invoke(text) ?: PiiDetector.find(text, ownTerms, automatic = true)
            found + if (extra.isEmpty()) emptyList() else PiiDetector.find(text, extra, automatic = false)
        } + extra.filter { e -> texts.any { it.contains(e, ignoreCase = true) } }.map { PiiHit(it, PiiKind.TERM) }
        // Speakers with real names count as names, too.
        val speakerNames = t.speakers.values.filter { !NEUTRAL.matches(it.trim()) && it.isNotBlank() }
        val counters = mutableMapOf<PiiKind, Int>()
        val mapping = linkedMapOf<String, String>()
        speakerNames.forEachIndexed { i, name -> mapping[name] = "Person ${'A' + i}" }
        hits.sortedByDescending { it.value.length }.forEach { h ->
            // One entry per word, whatever its case („Schmidt“ and „SCHMIDT“ are one person).
            if (h.value.isBlank() || mapping.keys.any { it.equals(h.value, ignoreCase = true) }) return@forEach
            // Parts of a speaker's name (e.g. only the first name in the text) get that speaker's label.
            val speaker = speakerNames.firstOrNull { name -> name.split(Regex("\\s+")).any { part -> part.length > 2 && h.value.equals(part, true) } }
            mapping[h.value] = speaker?.let { mapping[it] } ?: "[${h.kind.label} ${counters.merge(h.kind, 1, Int::plus)}]"
        }
        speakerNames.forEach { name ->
            name.split(Regex("\\s+")).filter { it.length > 2 }.forEach { part -> if (mapping.keys.none { it.equals(part, true) }) mapping[part] = mapping.getValue(name) }
        }
        val all = texts.joinToString("\n") + "\n" + t.speakers.values.joinToString("\n")
        return mapping.map { (k, v) -> Finding(k, v, pattern(listOf(k))?.findAll(all)?.count() ?: 0) }
    }

    private fun pattern(keys: Collection<String>): Regex? = if (keys.isEmpty()) null else Regex(
        keys.sortedByDescending { it.length }.joinToString("|") { "(?<![\\p{L}\\d])" + Regex.escape(it) + "(?![\\p{L}\\d])" },
        RegexOption.IGNORE_CASE,
    )

    /**
     * [s] with the [mapping] applied, and where each character of [s] went (index i of [s] → index in the result; one
     * more entry for the end) – so marked words of codings move with the text.
     */
    internal fun replaceMapped(s: String, mapping: Map<String, String>, re: Regex?): Pair<String, IntArray> {
        val map = IntArray(s.length + 1)
        if (re == null) { for (i in map.indices) map[i] = i; return s to map }
        val out = StringBuilder()
        var last = 0
        re.findAll(s).forEach { m ->
            for (i in last until m.range.first) { map[i] = out.length; out.append(s[i]) }
            val start = out.length
            val with = mapping[m.value] ?: mapping.entries.firstOrNull { it.key.equals(m.value, true) }?.value ?: m.value
            out.append(with)
            for (i in m.range) map[i] = start
            last = m.range.last + 1
        }
        for (i in last until s.length) { map[i] = out.length; out.append(s[i]) }
        map[s.length] = out.length
        return out.toString() to map
    }

    /** The copy of [t] with [mapping] (original → pseudonym) applied; codings stay, their marked words moved along. */
    fun apply(t: Transcript, mapping: List<Pair<String, String>>): Result {
        val map = mapping.toMap()
        val re = pattern(map.keys)
        fun replace(s: String) = replaceMapped(s, map, re).first
        val coding = TranscriptCoding.of(t)
        val moved = mutableListOf<Coding>()
        val segments = t.segments.map { s ->
            val (text, where) = replaceMapped(s.text, map, re)
            coding.at(s).forEach { c ->
                moved += if (c.whole) c.copy(memo = c.memo?.let(::replace))
                else c.copy(from = where[c.from!!.coerceIn(0, s.text.length)], to = where[c.to!!.coerceIn(0, s.text.length)], memo = c.memo?.let(::replace))
            }
            val comment = s.comment?.let(::replace)
            // Unchanged words keep their measured times.
            if (text == s.text && comment == s.comment) s else s.copy(text = text, words = TranscriptEdit.realign(s, text), comment = comment)
        }
        val speakers = t.speakers.mapValues { (_, v) -> map[v] ?: replace(v) }
        val anon = t.copy(
            title = replace(t.title) + " (pseudonymisiert)", segments = segments, speakers = speakers,
            raw = JsonObject(t.raw - "original" - "coding"), summary = t.summary?.let(::replace),
            chapters = t.chapters.map { it.copy(title = replace(it.title)) },
        )
        val withCodes = if (coding.codings.isEmpty()) anon else anon.withCoding(coding.copy(codings = moved))
        return Result(withCodes, mapping)
    }

    /** The mapping as table, to be kept apart from the pseudonymized transcript (in the Tresor). */
    fun mappingTable(title: String, mapping: List<Pair<String, String>>): String = buildString {
        append("# Zuordnungsliste – $title\n\n")
        append("> [!WARNING]\n> Getrennt vom pseudonymisierten Transkript und geschützt aufbewahren; nach Abschluss der Auswertung löschen.\n\n")
        append("| Original | Ersetzt durch |\n| --- | --- |\n")
        mapping.forEach { (a, b) -> append("| ${a.replace("|", "/")} | $b |\n") }
    }
}

/** Translation of a transcript passage by passage: times, speakers and passages stay as they are (for subtitles). */
object TranscriptTranslate {
    val LANGUAGES = listOf("Englisch", "Deutsch", "Französisch", "Spanisch", "Italienisch", "Polnisch", "Tschechisch", "Ukrainisch", "Russisch", "Arabisch", "Türkisch", "Leichte Sprache")

    private val LINE = Regex("^\\s*(\\d+)\\s*[\\t|:.)-]\\s*(.*)$")

    /** Parses „<Nummer>\t<Übersetzung>“ lines; missing passages are null. */
    fun parse(answer: String, count: Int): List<String?> {
        val out = arrayOfNulls<String>(count)
        answer.lines().forEach { l ->
            val m = LINE.find(l) ?: return@forEach
            val i = m.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEach
            if (i in 0 until count && m.groupValues[2].isNotBlank()) out[i] = m.groupValues[2].trim()
        }
        return out.toList()
    }

    suspend fun translate(c: AppContainer, t: Transcript, language: String, onProgress: (Int, Int) -> Unit): Transcript {
        val model = c.settings.current.defaultModel
        val segs = t.segments
        val result = arrayOfNulls<String>(segs.size)
        val batch = 40
        var done = 0
        for (from in segs.indices step batch) {
            val part = segs.subList(from, minOf(segs.size, from + batch))
            val prompt = part.mapIndexed { i, s -> "${i + 1}\t${s.text.trim().replace('\n', ' ')}" }.joinToString("\n")
            val answer = c.llm.streamChat(
                model = model,
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "Du übersetzt Abschnitte eines Transkripts ins $language. Jede Zeile beginnt mit einer Nummer und einem " +
                            "Tabulator. Antworte mit genau denselben Nummern, je Zeile „Nummer<Tab>Übersetzung“, ohne Zeilen zusammenzufassen " +
                            "oder aufzuteilen und ohne Kommentar. Füllwörter und Abbrüche sinngemäß übertragen, Namen nicht übersetzen.",
                    ),
                    ChatMessage(role = "user", content = prompt),
                ),
                tools = null,
                thinking = ThinkingLevel.OFF,
            ) { }.content
            parse(io.github.thepytales.llmhub.ui.splitThinking(answer).second, part.size).forEachIndexed { i, s -> result[from + i] = s }
            done += part.size
            onProgress(done, segs.size)
        }
        val missing = result.count { it == null }
        return t.copy(
            id = UUID.randomUUID().toString().replace("-", "").take(24),
            title = "${t.title} ($language)",
            segments = segs.mapIndexed { i, s -> s.copy(text = result[i] ?: s.text, words = emptyList()) },
            language = null,
            audio = null,
            raw = JsonObject(t.raw - "original" - "coding" - "word_timing" + ("translated_from" to JsonPrimitive(t.id))),
            notice = if (missing > 0) tr("{0} Abschnitte blieben unübersetzt (Originaltext).", missing) else tr("Maschinell übersetzt – bitte prüfen."),
            status = TranscriptStatus.RAW,
        )
    }
}
