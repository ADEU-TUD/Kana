package io.github.thepytales.llmhub.ssh

import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.DataPlace
import io.github.thepytales.llmhub.data.ModelCatalog
import io.github.thepytales.llmhub.local.DeviceBench
import io.github.thepytales.llmhub.ui.tr
import java.util.Locale

/**
 * Where Lora thinks in the Server-Terminal (AP-131 e): the model on this PC or the Uni-Server of the TU Dresden – never
 * an external cloud provider (like the Postfach). The terminal asks once ([AppSettings.serverTerminalModel] empty) with
 * a recommendation for this PC and the risks of both ways; Einstellungen › Lora changes it later.
 */
object ServerModelChoice {
    enum class Choice(val key: String) { LOCAL("LOCAL"), TU_SERVER("TU_SERVER") }

    fun of(key: String): Choice? = Choice.entries.firstOrNull { it.key == key }

    /** What the advice is based on – read from the app by [facts], set directly in tests. */
    data class Facts(
        /** Model id of the best installed local model, its name and rough strength (billions of parameters). */
        val localModel: String? = null,
        val localName: String = "",
        val localQuality: Double = 0.0,
        /** Words per second this PC writes with it (measured or estimated), null when unknown. */
        val wordsPerSec: Double? = null,
        val ramGb: Double? = null,
        /** The model on the Uni-Server, null without the key of the Uni-Server. */
        val tuModel: String? = null,
        val tuName: String = "",
        /** A key of an external provider is there (blocked here – said so in the dialog). */
        val cloudKey: Boolean = false,
    )

    /** One way with what speaks for it and what to keep in mind. */
    data class Option(val choice: Choice, val possible: Boolean, val summary: String, val good: List<String>, val risks: List<String>)

    data class Advice(val recommended: Choice?, val why: String, val local: Option, val tu: Option, val both: String) {
        val anyPossible: Boolean get() = local.possible || tu.possible
    }

    /** Below this a local model is too weak or too slow for shell commands in daily use. */
    const val GOOD_QUALITY = 7.0
    const val GOOD_WORDS_PER_SEC = 6.0
    /** A proposal (JSON with up to eight steps and explanations) has about this many words. */
    private const val PROPOSAL_WORDS = 160

    fun secondsPerProposal(wordsPerSec: Double): Int = (PROPOSAL_WORDS / wordsPerSec + 2).toInt()

    private fun n(v: Double) = String.format(Locale.GERMANY, "%.0f", v)

    /** The best installed local model for the terminal (code specialists count – shell commands are code). */
    fun localModel(c: AppContainer): String? = c.localLlm.installedModels().maxByOrNull { it.quality }?.modelId

    /** Lora's model when it runs at the TU, else the default Lora model on the Uni-Server; null without the Uni key. */
    fun tuModel(c: AppContainer): String? {
        val s = c.settings.current
        if (!s.hasApiKey) return null
        return listOf(s.loraModel, ModelCatalog.FAST_CHAT).firstOrNull { runCatching { DataPlace.ofModel(c, it) }.getOrNull() == DataPlace.TU }
    }

    fun facts(c: AppContainer): Facts {
        val m = c.localLlm.installedModels().maxByOrNull { it.quality }
        val hw = c.localLlm.hardware.value
        val wps = if (m != null && hw != null) runCatching { DeviceBench.forecast(c, m, hw).wordsPerSec }.getOrNull() else null
        val tu = tuModel(c)
        return Facts(
            localModel = m?.modelId, localName = m?.original.orEmpty(), localQuality = m?.quality ?: 0.0, wordsPerSec = wps, ramGb = hw?.ramGb,
            tuModel = tu, tuName = tu?.let { ModelCatalog.info(it).displayName }.orEmpty(),
            cloudKey = c.settings.current.providers.any { it.usable },
        )
    }

    fun advise(c: AppContainer): Advice = advise(facts(c))

    fun advise(f: Facts): Advice {
        val localOk = f.localModel != null
        val tuOk = f.tuModel != null
        val slow = f.wordsPerSec != null && f.wordsPerSec < GOOD_WORDS_PER_SEC
        val small = localOk && f.localQuality < GOOD_QUALITY
        val strongLocal = localOk && !slow && !small
        val recommended = when {
            localOk && tuOk -> if (strongLocal) Choice.LOCAL else Choice.TU_SERVER
            localOk -> Choice.LOCAL
            tuOk -> Choice.TU_SERVER
            else -> null
        }
        val speed = f.wordsPerSec?.let { tr("{0} schreibt auf diesem PC etwa {1} Wörter pro Sekunde – ein Vorschlag dauert rund {2} Sekunden.", f.localName, n(it), secondsPerProposal(it)) }
        val why = when {
            recommended == null -> tr("Weder ein Sprachmodell auf diesem PC noch der Zugang zum Uni-Server ist eingerichtet. Die festen Abläufe gehen trotzdem; für Vorschläge ein Modell laden (Einstellungen › Lokale Modelle) oder den Schlüssel des Uni-Servers eintragen.")
            localOk && tuOk && strongLocal -> tr("Dein PC ist stark genug: {0} Dann verlässt nichts den PC, auch keine Logs.", speed ?: tr("{0} ist ein großes Modell.", f.localName))
            localOk && tuOk && slow -> tr("Das Modell auf diesem PC ist für das Terminal zu langsam. {0} Der TU-Server antwortet in wenigen Sekunden und schlägt Shell-Befehle zuverlässiger vor.", speed.orEmpty())
            localOk && tuOk -> tr("Das Modell auf diesem PC ({0}) ist klein – bei Shell-Befehlen liegt es öfter daneben. Der TU-Server schlägt zuverlässiger vor.", f.localName)
            localOk -> if (f.cloudKey) tr("Ohne Schlüssel für den Uni-Server bleibt nur das Modell auf diesem PC. Dein externer Anbieter ist fürs Terminal gesperrt.") else tr("Ohne Schlüssel für den Uni-Server bleibt nur das Modell auf diesem PC.")
            else -> if (f.ramGb != null && f.ramGb < 12) tr("Auf diesem PC ist kein Sprachmodell installiert, und mit {0} GB Arbeitsspeicher wäre ein brauchbares Modell für Shell-Befehle zu langsam. Der TU-Server ist hier die bessere Wahl.", n(f.ramGb))
            else tr("Auf diesem PC ist kein Sprachmodell installiert. Der TU-Server ist sofort bereit; ein Modell für den PC lässt sich später unter Einstellungen › Lokale Modelle laden.")
        }
        val local = Option(
            Choice.LOCAL, localOk,
            if (localOk) tr("Modell auf diesem PC: {0}", f.localName) else tr("Modell auf diesem PC: keins installiert"),
            good = listOf(tr("Nichts verlässt den PC – auch keine Ausgaben, Logs oder Ordnernamen."), tr("Geht ohne Internet und ohne Uni-Server.")),
            risks = buildList {
                if (!localOk) add(tr("Erst ein Modell laden (Einstellungen › Lokale Modelle), sonst gibt es keine Vorschläge."))
                if (small || !localOk) add(tr("Kleine Modelle schlagen öfter falsche oder unvollständige Befehle vor – lies jede Karte, bevor du klickst."))
                if (slow && speed != null) add(speed)
                add(tr("Während Lora denkt, ist der PC ausgelastet (Lüfter, Akku)."))
            },
        )
        val tu = Option(
            Choice.TU_SERVER, tuOk,
            if (tuOk) tr("TU-Server: {0} (ScaDS.AI, TU Dresden)", f.tuName) else tr("TU-Server: Schlüssel des Uni-Servers fehlt"),
            good = listOf(tr("Schneller und zuverlässiger bei Shell-Befehlen; der PC bleibt frei."), tr("Die Daten bleiben an der TU Dresden – kein externer Anbieter.")),
            risks = buildList {
                if (!tuOk) add(tr("Dafür den Schlüssel des Uni-Servers eintragen (Einstellungen › Konto)."))
                add(tr("Was das Terminal zeigt (Ausgaben, Logs, die letzten Zeilen), geht an den Uni-Server."))
                add(tr("Hostnamen, IP-Adressen, Benutzer, Mail-Adressen und Passwörter werden vorher ersetzt – aber nicht alles lässt sich sicher erkennen: Namen von Personen in Logs oder interne Projektnamen können mitgehen. Bei Logs mit personenbezogenen Daten lieber den PC wählen."))
                add(tr("Ohne Internet oder bei einer Störung des Uni-Servers gibt es keine Vorschläge (die festen Abläufe gehen weiter)."))
            },
        )
        val both = tr("In beiden Fällen führt Lora nichts ohne deinen Klick aus. Externe Cloud-Anbieter (z. B. Gemini) sind fürs Terminal gesperrt.")
        return Advice(recommended, why, local, tu, both)
    }
}
