package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.ui.tr
import io.github.thepytales.llmhub.ui.trLater

/**
 * Vorlagen für Titel und Abspann (AP-129): for the four kinds of video of the team – Vorlesungsaufzeichnung, Interview,
 * kurzer Clip, Unterrichtsvideo – a set of titles in one go: title at the start, the names of the speakers as lower
 * thirds where they first speak (from the transcript), an end card. The look is the TUD design of [VideoTitles] as it
 * is; a template only fills in texts and times. Applying a template again replaces what it put there before.
 */
object VideoTemplates {
    /** A text the template asks for: [key], label, example. */
    data class Field(val key: String, val label: String, val example: String)

    data class Template(val id: String, val label: String, val hint: String, val fields: List<Field>, val speakers: Boolean = false)

    val TEMPLATES = listOf(
        Template(
            "vorlesung", trLater("Vorlesungsaufzeichnung"), trLater("Titel mit Veranstaltung und Termin, Abspann mit Professur."),
            listOf(Field("titel", trLater("Veranstaltung / Thema"), trLater("Einführung in die Schulpädagogik")), Field("untertitel", trLater("Termin, Lehrende"), trLater("Vorlesung 3 · Prof. Muster")), Field("abspann", trLater("Abspann"), trLater("Professur für … · TU Dresden"))),
        ),
        Template(
            "interview", trLater("Interview / Gespräch"), trLater("Titel, Namen der Sprecher:innen als Bauchbinde beim ersten Wort (aus dem Transkript), Abspann."),
            listOf(Field("titel", trLater("Thema"), trLater("Lernen im Ganztag")), Field("untertitel", trLater("Untertitel"), trLater("Ein Gespräch mit …")), Field("abspann", trLater("Abspann"), trLater("Interview: … · TU Dresden"))),
            speakers = true,
        ),
        Template(
            "clip", trLater("Kurzer Clip"), trLater("Ein Satz am Anfang statt eines ganzen Titels, kurzer Abspann."),
            listOf(Field("titel", trLater("Satz am Anfang"), trLater("Drei Tipps für gute Folien")), Field("abspann", trLater("Abspann"), trLater("TU Dresden"))),
        ),
        Template(
            "unterricht", trLater("Unterrichtsvideo"), trLater("Titel mit Fach und Klasse, Abspann mit Hinweis zur Einwilligung."),
            listOf(Field("titel", trLater("Thema der Stunde"), trLater("Brüche addieren")), Field("untertitel", trLater("Fach, Klasse"), trLater("Mathematik · Klasse 6")), Field("abspann", trLater("Abspann"), trLater("Aufgenommen mit Einwilligung aller Beteiligten"))),
        ),
    )

    fun template(id: String?): Template? = TEMPLATES.firstOrNull { it.id == id?.trim()?.lowercase() }

    /** Id prefix of the overlays a template sets (so applying again replaces them). */
    private const val PREFIX = "vorlage-"

    /**
     * [p] with the titles of template [t] filled with [values] (field key → text; blank fields are left out).
     * [speakers]: name and first time on the timeline of each speaker (for „Interview“). Titles the template set before
     * are replaced; other titles stay.
     */
    fun apply(p: VideoProject, t: Template, values: Map<String, String>, speakers: List<Pair<String, Double>> = emptyList()): VideoProject {
        fun v(k: String) = values[k]?.trim().orEmpty()
        val end = p.duration
        val out = mutableListOf<TextOverlay>()
        val title = v("titel")
        when (t.id) {
            "clip" -> if (title.isNotBlank()) out += TextOverlay(id = PREFIX + newVideoId(), style = "einblendung", text = title, startSec = 0.3, durationSec = minOf(3.5, maxOf(1.0, end / 3)))
            else -> if (title.isNotBlank()) out += TextOverlay(id = PREFIX + newVideoId(), style = "titel", text = title, subtext = v("untertitel"), startSec = 0.0, durationSec = minOf(5.0, maxOf(1.5, end / 4)))
        }
        if (t.speakers) {
            // A lower third where each speaker first speaks – not over the title, one after the other.
            var free = (out.firstOrNull()?.endSec ?: 0.0) + 0.3
            speakers.forEach { (name, at) ->
                if (name.isBlank()) return@forEach
                val start = maxOf(at, free)
                if (start < end - 2.0) {
                    val o = TextOverlay(id = PREFIX + newVideoId(), style = "bauchbinde", text = name, startSec = start, durationSec = minOf(5.0, end - start - 0.5))
                    out += o
                    free = o.endSec + 0.3
                }
            }
        }
        val credits = v("abspann")
        if (credits.isNotBlank() && end > 3.0) {
            val d = if (t.id == "clip") 2.5 else 5.0
            out += TextOverlay(id = PREFIX + newVideoId(), style = "abspann", text = credits.substringBefore("\n"), subtext = credits.substringAfter("\n", ""), startSec = maxOf(0.0, end - d), durationSec = minOf(d, end))
        }
        return p.copy(overlays = p.overlays.filterNot { it.id.startsWith(PREFIX) } + out)
    }

    /** The speakers of [doc] (the text of the video) with the time each first speaks; generic „Sprecher 2“ included. */
    fun speakers(doc: TextCut.Doc): List<Pair<String, Double>> =
        doc.paragraphs.filter { !it.speaker.isNullOrBlank() }.groupBy { it.speaker!! }.map { (name, ps) -> name to ps.minOf { it.start } }.sortedBy { it.second }

    /** A short line of what [t] puts in, for Lora's preview. */
    fun describe(t: Template, values: Map<String, String>, speakers: Int): String = listOfNotNull(
        values["titel"]?.takeIf { it.isNotBlank() }?.let { tr("Titel „{0}“", it.take(40)) },
        if (t.speakers && speakers > 0) tr("{0} Bauchbinde(n)", speakers) else null,
        values["abspann"]?.takeIf { it.isNotBlank() }?.let { tr("Abspann") },
    ).joinToString(" · ")
}
