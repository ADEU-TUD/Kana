package io.github.thepytales.llmhub.ssh

import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.Area
import io.github.thepytales.llmhub.data.ChatMessage
import io.github.thepytales.llmhub.data.ModelCatalog
import io.github.thepytales.llmhub.data.Place
import io.github.thepytales.llmhub.data.PlaceScope
import io.github.thepytales.llmhub.data.Places
import io.github.thepytales.llmhub.net.ThinkingLevel
import io.github.thepytales.llmhub.ui.tr
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lora in the Server-Terminal (AP-118): turns a wish („Deploy wie immer“, „Zertifikat läuft ab“) and what the terminal
 * showed into steps with command, explanation and safety notes. Prefers the model on this device; a server model only
 * when none is here and Wo Lora arbeitet allows it – then everything goes through [ServerMask] first (no host, IP, user,
 * mail address or secret leaves the device) and the screen says so. Never runs anything itself.
 */
/** What the Server-Terminal needs from Lora (AP-118/AP-131): where she answers and her proposals. Tests use a stand-in. */
interface ServerHelper {
    fun where(): ServerAssistant.Where

    /**
     * Steps for [wish]. [followUp]: the steps so far ran – Lora says whether the goal is reached (then no steps and the
     * result in the note) or what comes next. [screen] is what the terminal shows at the moment (plain text).
     */
    suspend fun propose(
        wish: String, profile: SshProfile, recent: String, done: List<ServerStep>, secrets: Collection<String>,
        screen: String = "", followUp: Boolean = false,
    ): ServerAssistant.Proposal
}

class ServerAssistant(private val c: AppContainer) : ServerHelper {
    /** Which model answers and what the screen says about it. */
    data class Where(val model: String?, val onDevice: Boolean, val note: String)

    data class Proposal(val steps: List<ServerStep>, val note: String, val model: String, val sent: String)

    override fun where(): Where {
        c.localLlm.preferredModel()?.let { m ->
            return Where(m, true, tr("Lora schlägt mit {0} auf diesem Gerät vor – nichts verlässt den PC.", ModelCatalog.info(m).displayName))
        }
        val s = c.settings.current
        val allowed = s.hasAnyApiKey && Places.effective(c, Area.LORA) != Place.LOCAL
        if (!allowed) return Where(null, false, tr("Für Vorschläge fehlt ein Sprachmodell auf diesem Gerät (Einstellungen › Wo Lora arbeitet). Die festen Abläufe gehen trotzdem."))
        val model = s.loraModel.ifBlank { ModelCatalog.FAST_CHAT }
        return Where(model, false, tr("Kein Modell auf diesem Gerät: Vorschläge kommen vom Server ({0}). Vorher werden Hosts, IP-Adressen, Benutzer, Mail-Adressen und alle Geheimnisse ersetzt.", ModelCatalog.info(model).displayName))
    }

    override suspend fun propose(
        wish: String, profile: SshProfile, recent: String, done: List<ServerStep>, secrets: Collection<String>,
        screen: String, followUp: Boolean,
    ): Proposal {
        val where = where()
        val model = where.model ?: throw IllegalStateException(where.note)
        // On this device nothing leaves the PC, but secrets still stay out of the model's context.
        val mask = ServerMask(secrets, profile.takeUnless { where.onDevice })
        val sent = request(wish, profile, recent, done, mask, screen, followUp)
        val messages = listOf(ChatMessage(role = "system", content = SYSTEM), ChatMessage(role = "user", content = sent))
        val result = withContext(PlaceScope(Area.LORA)) { c.llm.streamChat(model, messages, null, thinking = ThinkingLevel.OFF) {} }
        val (steps, note) = parse(mask.unmask(result.content))
        return Proposal(steps, note, model, sent)
    }

    companion object {
        /** The request as it would leave the device – what [propose] sends (shown under „Was gesendet wird“). */
        fun request(
            wish: String, profile: SshProfile, recent: String, done: List<ServerStep>, mask: ServerMask,
            screen: String = "", followUp: Boolean = false,
        ): String = mask.mask(buildString {
            append(if (followUp) "Ziel: " else "Wunsch: ").append(wish.trim()).append('\n')
            append("Server: Linux, Benutzer ").append(profile.user).append(", Webserver-Benutzer ").append(profile.webUser.ifBlank { "www-data" })
            if (profile.workDir.isNotBlank()) append(", Anwendung in ").append(profile.workDir)
            append('\n')
            if (done.isNotEmpty()) {
                append("Bisherige Schritte:\n")
                val recentSteps = done.takeLast(8)
                recentSteps.forEachIndexed { i, s ->
                    append("- ").append(s.command).append(" → ").append(s.state.name.lowercase()).append(s.exitCode?.let { " (Exit $it)" } ?: "").append('\n')
                    // The outputs of the last three steps: what Lora needs to answer or to go on.
                    if (followUp && i >= recentSteps.size - 3 && s.output.isNotBlank()) append("  Ausgabe:\n").append(s.output.takeLast(1500).prependIndent("    ")).append('\n')
                }
            }
            if (recent.isNotBlank() && !followUp) append("Letzte Ausgabe:\n").append(recent.takeLast(3000)).append('\n')
            if (screen.isNotBlank()) append("Terminal zuletzt (mit Eingabeaufforderung und aktuellem Ordner):\n").append(screen.takeLast(1500)).append('\n')
            if (followUp) append(FOLLOW_UP).append('\n')
        })

        /** The question after the steps ran (AP-131 d): is the goal reached, and what did come out? */
        const val FOLLOW_UP = "Die Schritte oben sind gelaufen. Ist das Ziel erreicht? Dann gib keine Schritte und schreibe in note in 1–3 Sätzen das Ergebnis für die Nutzerin oder den Nutzer (z. B. die gefragte Zahl, was jetzt läuft). Sonst schlage die nächsten Schritte vor und sage in note kurz, warum."

        val SYSTEM = """
            Du bist Lora und hilfst, einen Linux-Webserver (Apache, PHP, Symfony, Let's Encrypt) über SSH zu pflegen.
            Die Befehle laufen nach Zustimmung nacheinander im offenen Terminal; ein cd gilt für die folgenden Schritte.
            Schlage höchstens 8 Schritte vor, jeder ein einzelner Shell-Befehl ohne Kommentar (#). Erkläre je Schritt in einem Satz auf Deutsch,
            was er tut, und nenne Sicherheitsaspekte (Rechte, Neustart, Datenverlust, sudo). Bevorzuge erst prüfen, dann ändern
            (z. B. apachectl configtest vor reload, certbot --dry-run vor renew). Nie chmod 777, nie Passwörter in Befehle schreiben,
            nichts löschen ohne Not. Platzhalter wie <host-1> oder <geheim> unverändert lassen.
            Keine Programme, die den ganzen Bildschirm brauchen (vim, nano, less, top) – nutze cat, tail, grep, sed -n.
            In note sagst du der Nutzerin oder dem Nutzer in einem Satz, was du vorhast – oder, wenn nichts mehr zu tun ist, das Ergebnis.
            Antworte nur mit JSON: {"steps":[{"command":"…","what":"…","safety":"…","risk":"read|change|risky"}],"note":"…"}
        """.trimIndent()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Steps from the model's answer (JSON, also inside ``` fences); the app's own risk and notes are added. */
        fun parse(answer: String): Pair<List<ServerStep>, String> {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList<ServerStep>() to answer.trim()
            val obj = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)).jsonObject }.getOrNull()
                ?: return emptyList<ServerStep>() to answer.trim()
            val steps = obj["steps"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val cmd = o["command"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("$ ")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if ('\n' in cmd) return@mapNotNull null
                val lora = when (o["risk"]?.jsonPrimitive?.contentOrNull?.lowercase()) { "risky" -> Risk.RISKY; "change" -> Risk.CHANGE; "read" -> Risk.READ; else -> null }
                val safety = (listOfNotNull(o["safety"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }) + Risks.notes(cmd).map { tr(it) }).distinct().joinToString(" ")
                ServerStep(cmd, o["what"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(), safety).withLoraRisk(lora)
            }.take(8)
            return steps to (obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
        }
    }
}
