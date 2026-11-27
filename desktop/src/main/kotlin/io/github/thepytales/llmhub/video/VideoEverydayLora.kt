package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.agent.ActionArea
import io.github.thepytales.llmhub.agent.ActionFailed
import io.github.thepytales.llmhub.agent.ActionParam
import io.github.thepytales.llmhub.agent.ActionPlan
import io.github.thepytales.llmhub.agent.ActionResult
import io.github.thepytales.llmhub.agent.ToolAction
import io.github.thepytales.llmhub.agent.ToolActions
import io.github.thepytales.llmhub.files.TextFormats
import io.github.thepytales.llmhub.net.s
import io.github.thepytales.llmhub.ui.VideoHub
import io.github.thepytales.llmhub.ui.clock
import io.github.thepytales.llmhub.ui.tr
import io.github.thepytales.llmhub.ui.trLater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kana im Videoeditor, was im Alltag fehlt (AP-129): music quieter while someone speaks, voices evened out, another
 * format that follows the person, the look of the subtitles, a short clip from the highlights, an interview cut by
 * speaker and Hervorhebungen in teaching videos – the same functions as the editor's buttons, with preview and
 * Rückgängig through [VideoLora].
 */
object VideoEverydayLora {
    private fun yes(text: String?, default: Boolean = true): Boolean {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return default
        return !(t.startsWith("n") || t == "aus" || t == "off" || t == "false" || t == "0")
    }

    /** „hochformat“, „9:16“, „quadrat“, „1:1“, „4:5“, „quer“ → a format. */
    internal fun formatOf(text: String?): Reframe.Format? {
        val t = text?.lowercase()?.replace(" ", "").orEmpty()
        return when {
            "4:5" in t || "45" in t -> Reframe.format("hoch45")
            "hoch" in t || "9:16" in t || "story" in t || "reel" in t || "short" in t || "portrait" in t -> Reframe.format("hoch")
            "quadrat" in t || "1:1" in t || "square" in t -> Reframe.format("quadrat")
            "quer" in t || "16:9" in t || "landscape" in t -> Reframe.format("quer")
            else -> null
        }
    }

    val ACTIONS: List<ToolAction> = listOf(
        ToolAction(
            "video.duck", ActionArea.VIDEO, trLater("Musik leiser beim Sprechen"),
            "Musik auf den Tonspuren wird automatisch leiser, sobald im Video jemand spricht, und danach wieder lauter (Ducking, AP-129).",
            listOf(ActionParam("an", "ja (Standard) oder nein", required = false)), risky = false, android = false,
        ) { _, a ->
            val p = VideoLora.need()
            val on = yes(a.s("an"))
            val music = p.audio.filter { it.linkedTo == null }
            if (music.isEmpty()) throw ActionFailed(tr("Im Projekt ist keine Musik – zuerst über Hinzufügen › Musik / Ton eine Datei unterlegen."))
            ActionPlan("video.duck", if (on) tr("Musik wird beim Sprechen leiser …") else tr("Musik bleibt gleich laut …"), false) {
                VideoLora.change(p, if (on) tr("{0} Ton/Töne werden leiser, wenn gesprochen wird.", music.size) else tr("Musik bleibt jetzt gleich laut.")) { pp -> SoundFix.duck(pp, on) }
            }
        },
        ToolAction(
            "video.level_voices", ActionArea.VIDEO, trLater("Stimmen angleichen"),
            "Gleicht leise und laute Stimmen an – z. B. Fragen aus dem Saal oder wer weiter weg vom Mikrofon spricht (AP-129).",
            listOf(ActionParam("an", "ja (Standard) oder nein", required = false)), risky = false, android = false,
        ) { _, a ->
            val p = VideoLora.need()
            val on = yes(a.s("an"))
            ActionPlan("video.level_voices", if (on) tr("Gleicht die Stimmen an …") else tr("Stimmen wie aufgenommen …"), false) {
                VideoLora.change(p, if (on) tr("Leise und laute Stimmen werden angeglichen.") else tr("Stimmen bleiben wie aufgenommen.")) { pp -> pp.copy(sound = pp.sound.copy(level = on)) }
            }
        },
        ToolAction(
            "video.format", ActionArea.VIDEO, trLater("Format wechseln"),
            "Stellt das Video auf Hochformat 9:16, Quadrat 1:1, Hoch 4:5 oder Quer 16:9 um; die Clips füllen das neue Bild, auf Wunsch folgt der Ausschnitt der Person (Gesichter auf diesem Computer, AP-129).",
            listOf(ActionParam("format", "hochformat, quadrat, 4:5 oder quer"), ActionParam("person_folgen", "ja (Standard) oder nein", required = false)),
            risky = false, android = false, remember = listOf("format"),
        ) { _, a ->
            val p = VideoLora.need()
            val format = formatOf(a.s("format")) ?: throw ActionFailed(tr("Welches Format? Hochformat, Quadrat, 4:5 oder quer."))
            if (p.clips.isEmpty()) throw ActionFailed(tr("Das Projekt hat noch keine Clips."))
            val follow = yes(a.s("person_folgen")) && Faces.missing() == null
            ActionPlan("video.format", tr("Stellt auf {0} um …", tr(format.label)), false) {
                var q = withContext(Dispatchers.IO) { Reframe.toFormat(p, format, { Media.probe(it) }) }
                var found = 0
                if (follow) for (clip in q.clips.filter { it.fx.fill }) {
                    val keys = runCatching { Reframe.find(clip) }.getOrElse { throw ActionFailed(it.message ?: tr("Die Gesichtserkennung ist fehlgeschlagen.")) }
                    if (keys.size > 1 || keys.any { kotlin.math.abs(it.x - 0.5f) > 0.02f }) found++
                    q = Reframe.apply(q, clip.id, keys)
                }
                val result = q
                VideoLora.change(p, tr("Jetzt {0}.", tr(format.label)) + " " + when {
                    follow && found > 0 -> tr("Der Ausschnitt folgt der Person in {0} Clip(s) – bitte in der Vorschau prüfen.", found)
                    Faces.missing() != null && yes(a.s("person_folgen")) -> tr("Ohne Videoeditor-Paket bleibt der Ausschnitt in der Mitte.")
                    else -> ""
                }) { _ -> result }
            }
        },
        ToolAction(
            "video.subtitle_style", ActionArea.VIDEO, trLater("Untertitel-Stil"),
            "Aussehen der eingebrannten Untertitel: tud (Standard, weiß auf dunklem Kasten), gross (große Schrift in der unteren Bildmitte) oder wort (Wort für Wort, das gesprochene Wort gelb) – für kurze Clips (AP-129).",
            listOf(ActionParam("stil", "tud, gross oder wort")), risky = false, android = false, remember = listOf("stil"),
        ) { _, a ->
            val p = VideoLora.need()
            val t = a.s("stil")?.lowercase().orEmpty()
            val style = when { "wort" in t || "word" in t -> CaptionStyles.WORDS; "gro" in t || "big" in t -> CaptionStyles.BIG; else -> CaptionStyles.TUD }
            if (p.subtitles.isEmpty()) throw ActionFailed(tr("Das Video hat noch keine Untertitel – zuerst „Untertitel erzeugen“."))
            ActionPlan("video.subtitle_style", tr("Untertitel: {0} …", tr(CaptionStyles.label(style))), false) {
                VideoLora.change(p, tr("Untertitel jetzt „{0}“ und ins Bild eingebrannt.", tr(CaptionStyles.label(style)))) { pp -> pp.copy(subtitleStyle = style, burnSubtitles = true) }
            }
        },
        ToolAction(
            "video.highlights", ActionArea.VIDEO, trLater("Kurzclip aus Höhepunkten"),
            "Macht aus einer langen Aufnahme einen kurzen Clip (30–90 s) mit den stärksten Stellen des Transkripts als neues Projekt – das Original bleibt; auf Wunsch im Hochformat mit Untertiteln Wort für Wort (AP-129).",
            listOf(
                ActionParam("wunsch", "Worum es gehen soll (optional)", required = false), ActionParam("sekunden", "Länge, z. B. 60 (Standard)", required = false),
                ActionParam("format", "hochformat (Standard), quadrat, 4:5 oder wie das projekt", required = false),
            ), risky = false, android = false, usesModel = true,
        ) { c, a ->
            val p = VideoLora.need()
            if (ToolActions.model(c) == null) throw ActionFailed(tr("Dafür braucht es ein Modell – hier ist keines erlaubt oder kein API-Schlüssel da."))
            val seconds = a.s("sekunden")?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(15, 180) ?: 60
            val fmtText = a.s("format").orEmpty()
            val format = if ("projekt" in fmtText.lowercase() || "wie" in fmtText.lowercase()) null else formatOf(fmtText.ifBlank { "hoch" })
            val ts = VideoLora.transcripts(c, p)
            val passages = Highlights.passages(p, ts)
            if (passages.isEmpty()) throw ActionFailed(tr("Für die Clips gibt es kein Transkript – zuerst „Untertitel erzeugen“ oder transkribieren."))
            ActionPlan("video.highlights", tr("Sucht die Höhepunkte für einen Clip von {0} s …", seconds), false) {
                val chosen = runCatching { Highlights.choose(c, passages, a.s("wunsch").orEmpty(), seconds) }.getOrElse { throw ActionFailed(it.message ?: it.javaClass.simpleName) }
                var q = withContext(Dispatchers.IO) { Highlights.build(p, chosen, ts, { Media.probe(it) }, format) }
                if (format != null && Faces.missing() == null) for (clip in q.clips.filter { it.fx.fill }) q = Reframe.apply(q, clip.id, runCatching { Reframe.find(clip) }.getOrDefault(emptyList()))
                val saved = withContext(Dispatchers.IO) { VideoHub.store.save(q) }
                VideoLora.lastId = saved.id
                VideoLora.openRequest.value = saved.id
                ActionResult(tr("Kurzclip „{0}“ angelegt ({1}, {2} Stellen) – das Original bleibt unverändert.", saved.title, clock(saved.duration), chosen.size), undo = {
                    VideoHub.store.delete(saved.id)
                    if (VideoLora.lastId == saved.id) VideoLora.lastId = p.id
                    tr("Kurzclip wieder entfernt.")
                })
            }
        },
        ToolAction(
            "video.multicam", ActionArea.VIDEO, trLater("Interview nach Sprecher schneiden"),
            "Schneidet ein Interview mit zwei oder drei Kameras danach, wer spricht: synchronisiert die Kameras über den Ton, zeigt die sprechende Person, bei beiden die Totale; der Ton bleibt durchgehend von der Hauptkamera (AP-129).",
            listOf(
                ActionParam("kameras", "die weiteren Kameras (Dateien; leer: die ausgewählten)", required = false),
                ActionParam("totale", "welche Datei die Totale zeigt (optional)", required = false),
                ActionParam("clip", "Nummer des Clips der Hauptkamera (leer: der längste)", required = false),
            ), risky = true, android = false,
        ) { c, a ->
            val p = VideoLora.need()
            val main = a.s("clip")?.filter { it.isDigit() }?.toIntOrNull()?.let { p.clips.getOrNull(it - 1) } ?: p.clips.maxByOrNull { it.length }
                ?: throw ActionFailed(tr("Das Projekt hat noch keine Clips."))
            val others = ToolActions.files(c, a.s("kameras")).filter { it.extension.lowercase() in TextFormats.MEDIA && it.absolutePath != main.path }
                .ifEmpty { p.media.map { File(it.path) }.filter { it.absolutePath != main.path && !Media.isStill(it) && it.extension.lowercase() in TextFormats.MEDIA && p.clips.none { c2 -> c2.path == it.absolutePath } } }
            if (others.isEmpty()) throw ActionFailed(tr("Welche weiteren Kameras? Bitte die Aufnahmen auswählen oder nennen."))
            val wideName = a.s("totale")?.trim()?.lowercase().orEmpty()
            val wide = (others.map { it.absolutePath } + main.path).filter { wideName.isNotEmpty() && File(it).name.lowercase().contains(wideName) }.toSet()
            ActionPlan(
                "video.multicam", tr("Schneidet nach Sprecher mit {0} Kameras …", others.size + 1), true,
                listOf(File(main.path).name to tr("Hauptkamera, Ton")) + others.map { it.name to if (it.absolutePath in wide) tr("Totale") else tr("Kamera") },
            ) {
                val (q, text) = runCatching { MultiCam.run(p, main.id, others, wide) }.getOrElse { throw ActionFailed(it.message ?: it.javaClass.simpleName) }
                VideoLora.change(p, tr("Nach Sprecher geschnitten: {0}.", text)) { _ -> q }
            }
        },
        ToolAction(
            "video.callout", ActionArea.VIDEO, trLater("Hervorhebung"),
            "Setzt eine Hervorhebung ins Bild – rahmen, kreis, pfeil, markierung oder spot – mit kurzer Beschriftung, z. B. „Hier klicken“ im Screencast (AP-129).",
            listOf(
                ActionParam("art", "rahmen (Standard), kreis, pfeil, markierung oder spot", required = false),
                ActionParam("wo", "oben links, oben rechts, unten links, unten rechts, links, rechts, oben, unten oder Mitte", required = false),
                ActionParam("text", "Beschriftung (optional)", required = false),
                ActionParam("zeit", "Beginn, z. B. 1:30 (leer: Abspielposition)", required = false),
                ActionParam("dauer", "Sekunden (Standard 4)", required = false),
            ), risky = false, android = false, remember = listOf("art"),
        ) { _, a ->
            val p = VideoLora.need()
            val art = a.s("art")?.lowercase().orEmpty()
            val style = Callouts.STYLES.firstOrNull { art.startsWith(it.first.take(4)) }?.first ?: if ("arrow" in art) Callouts.ARROW else if ("circle" in art) Callouts.CIRCLE else Callouts.FRAME
            val at = VideoLora.seconds(a.s("zeit")) ?: VideoLora.host?.takeIf { it.id == p.id }?.playhead?.invoke() ?: 0.0
            val dur = a.s("dauer")?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(0.5, 60.0) ?: 4.0
            val (x, y) = Callouts.place(a.s("wo"))
            val o = Callouts.create(style, at, a.s("text").orEmpty()).copy(x = x, y = y, durationSec = dur)
            ActionPlan("video.callout", tr("Setzt eine Hervorhebung ({0}) bei {1} …", tr(Callouts.label(style) ?: style), clock(at)), false) {
                VideoLora.change(p, tr("Hervorhebung „{0}“ bei {1} – Lage und Größe in den Eigenschaften.", tr(Callouts.label(style) ?: style), clock(at))) { pp -> pp.copy(overlays = pp.overlays + o) }
            }
        },
    )
}
