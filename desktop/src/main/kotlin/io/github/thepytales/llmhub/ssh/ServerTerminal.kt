package io.github.thepytales.llmhub.ssh

import io.github.thepytales.llmhub.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State and rules of one Server-Terminal session (AP-118, one tab since AP-131 c), without UI: the saved sessions, the
 * connection, Kana's steps and the protocol. The rule that matters: a step runs only through [run] or [runInTerminal]
 * for exactly that step – after the user's click –, never several at once and never on its own. After a step Kana may
 * look at the result and say what came out or propose the next step (AP-131 d) – which again waits for a click.
 */
class ServerTerminal(val dir: File, private val assistant: ServerHelper? = null) {
    val sessions = ServerSessions(dir)
    val knownHosts = KnownHosts(File(dir, "known_hosts"))

    data class State(
        val profile: SshProfile? = null,
        val connected: Boolean = false,
        val busy: String? = null,
        val error: String? = null,
        /** The server's key needs a decision (new: confirm; changed: explicit replace). */
        val hostKey: HostKeyProblem? = null,
        /** Password or passphrase is needed for the next try. */
        val needsSecret: Boolean = false,
        val steps: List<ServerStep> = emptyList(),
        val flowTitle: String? = null,
        val note: String = "",
        /** What the last request to a model on a server contained (after masking) – shown on request. */
        val sent: String? = null,
        /** The user's wish when the steps come from Kana – she looks at the results and goes on (AP-131 d). */
        val goal: String? = null,
        /** How often Kana went on by herself for [goal]; she stops after [MAX_ROUNDS]. */
        val rounds: Int = 0,
        /** The command running in the terminal waits for typed input (password, yes/no) – the user answers there. */
        val waitsForInput: Boolean = false,
        /** A key was put on the server: offer to log in with it from now on (AP-131 a). */
        val keySwitch: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var connection: SshConnection? = null
        private set
    /** The open terminal of this session (JediTerm's connector) – Kana types her approved commands here. */
    var tty: SshTty? = null
        private set
    /** Files of the server over SFTP (AP-131 b), opened on first use. */
    var files: ServerFiles? = null
        private set
    private var log: ServerLog? = null
    /** Password/passphrase of this connection, in memory only (unless saved encrypted in [sessions]). */
    private var secret: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastJob: Job? = null
    private var offeredKey: String? = null

    private fun secrets(): Collection<String> = (sessions.secrets.all() + listOfNotNull(secret)).distinct()

    fun logOf(profile: SshProfile) = ServerLog(File(dir, "protokoll"), profile, ::secrets)

    /** Connects with [profile]; [typed] is a password/passphrase typed now, kept encrypted when [keep]. */
    suspend fun connect(profile: SshProfile, typed: String? = null, keep: Boolean = false) = withContext(Dispatchers.IO) {
        disconnect()
        val pass = typed?.takeIf { it.isNotEmpty() } ?: sessions.secrets.get(profile.id)
        _state.update { it.copy(profile = profile, busy = tr("Verbinde mit {0} …", profile.label), error = null, hostKey = null, needsSecret = false) }
        val l = logOf(profile).also { log = it }
        try {
            val conn = SshConnection.connect(profile, pass, knownHosts)
            connection = conn
            secret = pass
            if (keep && !typed.isNullOrEmpty()) sessions.secrets.put(profile.id, typed)
            l.write("Verbunden mit ${profile.user}@${profile.host}:${profile.port}")
            _state.update { it.copy(connected = true, busy = null) }
        } catch (e: HostKeyProblem) {
            l.write(if (e.result is KnownHosts.Result.Changed) "Verbindung blockiert: Server-Schlüssel geändert (${e.result.fingerprint})" else "Neuer Server-Schlüssel ${e.result.fingerprint} – wartet auf Bestätigung")
            secret = pass
            _state.update { it.copy(busy = null, hostKey = e) }
        } catch (e: SecretNeeded) {
            _state.update { it.copy(busy = null, needsSecret = true, error = e.message) }
        } catch (e: Exception) {
            l.write("Verbindung fehlgeschlagen", e.message.orEmpty())
            _state.update { it.copy(busy = null, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** The user confirmed the key shown in [State.hostKey]; a changed key needs [replace]. Then connects again. */
    suspend fun acceptHostKey(replace: Boolean) {
        val p = _state.value.hostKey ?: return
        val profile = _state.value.profile ?: return
        if (p.result is KnownHosts.Result.Changed && !replace) return
        knownHosts.accept(p.host, p.port, p.key, replace = replace)
        logOf(profile).write(if (replace) "Geänderten Server-Schlüssel ausdrücklich ersetzt: ${p.result.fingerprint}" else "Server-Schlüssel bestätigt: ${p.result.fingerprint}")
        connect(profile, secret)
    }

    fun rejectHostKey() {
        _state.update { it.copy(hostKey = null, error = tr("Verbindung abgebrochen – der Server-Schlüssel wurde nicht bestätigt.")) }
    }

    fun disconnect() {
        detach()
        files?.let { runCatching { it.close() } }
        files = null
        connection?.let { c -> runCatching { c.close() }; log?.write("Verbindung getrennt") }
        connection = null
        _state.update { it.copy(connected = false, waitsForInput = false) }
    }

    /** Ends this session for good (its tab was closed). */
    fun close() {
        disconnect()
        lastJob?.cancel()
    }

    /** The terminal of this session opened (AP-131 d): Kana can type here; it starts in the application folder. */
    fun attach(t: SshTty) {
        tty = t
        t.tap.onChunk = {
            if (t.tap.watching) {
                val w = TerminalTap.waitsForInput(t.tap.sinceStart())
                if (w != _state.value.waitsForInput) _state.update { it.copy(waitsForInput = w) }
            }
        }
        _state.value.profile?.workDir?.takeIf { it.isNotBlank() }?.let { runCatching { t.write("cd -- ${SshConnection.quote(it)}\r") } }
    }

    fun detach() {
        tty?.tap?.onChunk = null
        tty = null
    }

    /** Files of this server over SFTP (AP-131 b); opens the channel on first use. */
    fun openFiles(): ServerFiles? {
        files?.takeIf { it.isOpen }?.let { return it }
        val conn = connection ?: return null
        return runCatching { ServerFiles(conn, ::logFile) }.onFailure { e -> _state.update { it.copy(error = tr("Dateien des Servers nicht erreichbar: {0}", e.message ?: e.javaClass.simpleName)) } }
            .getOrNull()?.also { files = it }
    }

    private fun logFile(event: String) { log?.write(event) }

    /** The folder the terminal is in – from the prompt (`user@host:/var/www/app$`) or the end of Kana's last command. */
    fun terminalFolder(): String? = tty?.tap?.let { it.promptFolder() ?: it.cwd }

    /** Puts the steps of [flow] up for approval – nothing runs yet. */
    fun load(flow: ServerFlow) {
        val p = _state.value.profile ?: return
        val steps = flow.steps(p) { tr(it) }
        offeredKey = null
        _state.update { it.copy(steps = steps, flowTitle = tr(flow.title), note = tr(flow.description), goal = null, rounds = 0) }
        log?.write("Ablauf vorgeschlagen: ${flow.title}", steps.joinToString("\n") { "${it.risk.name.lowercase()}: ${it.command}" })
    }

    /**
     * Puts one prepared step up for approval (e.g. the public key into authorized_keys, AP-131 a) – nothing runs yet.
     * When it ran without error and [keyFile] is given, the screen offers to log in with that key from now on.
     */
    fun offer(title: String, step: ServerStep, note: String = "", keyFile: String? = null) {
        offeredKey = keyFile
        _state.update { it.copy(steps = listOf(step), flowTitle = title, note = note, goal = null, rounds = 0) }
        log?.write("Vorgeschlagen: $title", "${step.risk.name.lowercase()}: ${step.command}")
    }

    /** Kana proposes steps for [wish] (model on this device preferred, see [ServerAssistant]). */
    suspend fun propose(wish: String, recent: String = "") {
        val p = _state.value.profile ?: return
        val a = assistant ?: return
        _state.update { it.copy(busy = tr("Kana überlegt …"), error = null) }
        try {
            val done = _state.value.steps.filter { it.state != StepState.OPEN }
            // Without an output of its own: what the last step printed (a failed step is what Kana should look at).
            val seen = recent.ifBlank { done.lastOrNull { it.state == StepState.FAILED }?.output ?: done.lastOrNull()?.output.orEmpty() }
            val r = a.propose(wish, p, seen, done, secrets(), screen = tty?.tap?.screen(1500).orEmpty())
            _state.update { it.copy(busy = null, steps = r.steps, flowTitle = wish.trim(), note = r.note, sent = r.sent.takeUnless { a.where().onDevice }, goal = wish.trim(), rounds = 0) }
            log?.write("Kana schlägt vor ($wish)", r.steps.joinToString("\n") { "${it.risk.name.lowercase()}: ${it.command}" })
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun set(index: Int, f: (ServerStep) -> ServerStep) =
        _state.update { s -> s.copy(steps = s.steps.mapIndexed { i, st -> if (i == index) f(st) else st }) }

    private fun runnable(index: Int): ServerStep? {
        if (_state.value.steps.any { it.state == StepState.RUNNING }) return null
        return _state.value.steps.getOrNull(index)?.takeIf { it.state == StepState.OPEN || it.state == StepState.FAILED }
    }

    /** Runs exactly step [index] after the user's click, without the terminal; [onOutput] streams its output. */
    suspend fun run(index: Int, onOutput: (String) -> Unit = {}): ExecResult? = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext null
        val step = runnable(index) ?: return@withContext null
        set(index) { it.copy(state = StepState.RUNNING, output = "") }
        log?.write("Zugestimmt, ausgeführt: ${step.command}")
        val r = runCatching { conn.exec(step.command) { chunk -> set(index) { it.copy(output = (it.output + chunk).takeLast(20_000)) }; onOutput(chunk) } }
            .getOrElse { ExecResult(null, it.message ?: it.javaClass.simpleName) }
        set(index) { it.copy(state = if (r.ok) StepState.DONE else StepState.FAILED, exitCode = r.exitCode, output = r.output.takeLast(20_000)) }
        log?.write(if (r.timedOut) "Zeitüberschreitung: ${step.command}" else "Ende mit Exit ${r.exitCode ?: "?"}: ${step.command}", r.output)
        afterStep(index)
        r
    }

    /**
     * Runs exactly step [index] **in the open terminal** after the user's click (AP-131 d): Kana types the command and
     * presses Enter – the user sees it there, `cd` stays, and a password or a question is answered in the terminal. The
     * end, exit code and output come from [TerminalTap]. Returns false (and says why) when it cannot type now.
     */
    fun runInTerminal(index: Int): Boolean {
        val t = tty ?: return false
        val step = runnable(index) ?: return false
        if (t.tap.fullScreen) {
            _state.update { it.copy(error = tr("Im Terminal läuft gerade ein Programm mit ganzem Fenster (z. B. vim, less oder top). Beende es zuerst – dann trägt Kana den Befehl ein.")) }
            return false
        }
        if (!t.isConnected) {
            _state.update { it.copy(error = tr("Das Terminal ist geschlossen – bitte neu verbinden.")) }
            return false
        }
        set(index) { it.copy(state = StepState.RUNNING, output = "", exitCode = null) }
        _state.update { it.copy(error = null, waitsForInput = false) }
        log?.write("Zugestimmt, im Terminal ausgeführt: ${step.command}")
        t.tap.watch { f -> finishInTerminal(index, f.exitCode, f.output) }
        // Ctrl+U first: whatever was half typed on the prompt line must not run together with Kana's command.
        val ok = runCatching { t.write("\u0015" + TerminalTap.line(step.command) + "\r") }.isSuccess
        if (!ok) {
            t.tap.stopWatching()
            set(index) { it.copy(state = StepState.FAILED, output = tr("Konnte nicht ins Terminal schreiben.")) }
        }
        return ok
    }

    private fun finishInTerminal(index: Int, exitCode: Int?, output: String) {
        val step = _state.value.steps.getOrNull(index) ?: return
        val ok = exitCode == null || exitCode == 0
        set(index) { it.copy(state = if (ok) StepState.DONE else StepState.FAILED, exitCode = exitCode, output = output.takeLast(20_000)) }
        _state.update { it.copy(waitsForInput = false) }
        log?.write("Ende mit Exit ${exitCode ?: "?"}: ${step.command}", output)
        afterStep(index)
    }

    /** The command in the terminal is finished, but its end was not recognized (other shell) – the user says so. */
    fun markDone(index: Int) {
        val step = _state.value.steps.getOrNull(index)?.takeIf { it.state == StepState.RUNNING } ?: return
        val out = tty?.tap?.stopWatching().orEmpty()
        set(index) { it.copy(state = StepState.DONE, output = out.takeLast(20_000)) }
        _state.update { it.copy(waitsForInput = false) }
        log?.write("Als fertig markiert: ${step.command}", out)
        afterStep(index)
    }

    /** Stops the running command with Ctrl+C. */
    fun interrupt(index: Int) {
        val step = _state.value.steps.getOrNull(index)?.takeIf { it.state == StepState.RUNNING } ?: return
        runCatching { tty?.write("\u0003") }
        val out = tty?.tap?.stopWatching().orEmpty()
        set(index) { it.copy(state = StepState.FAILED, exitCode = 130, output = out.takeLast(20_000)) }
        _state.update { it.copy(waitsForInput = false) }
        log?.write("Abgebrochen (Strg+C): ${step.command}", out)
    }

    /**
     * After a step of Kana's proposal: when it failed or was the last one, Kana looks at the outputs and either says
     * what came out or proposes the next steps (each again with a click). Fixed flows do not go on by themselves.
     */
    private fun afterStep(index: Int) {
        val s = _state.value
        offeredKey?.let { key ->
            if (s.steps.getOrNull(index)?.state == StepState.DONE) { offeredKey = null; offerKeySwitch(key) }
        }
        val a = assistant ?: return
        val goal = s.goal ?: return
        val step = s.steps.getOrNull(index) ?: return
        val more = s.steps.any { it.state == StepState.OPEN }
        if (step.state != StepState.FAILED && more) return
        if (s.rounds >= MAX_ROUNDS) {
            _state.update { it.copy(note = tr("Kana hält hier an – sag ihr, wie es weitergehen soll.")) }
            return
        }
        lastJob = scope.launch { followUp(a, goal) }
    }

    private suspend fun followUp(a: ServerHelper, goal: String) {
        val p = _state.value.profile ?: return
        _state.update { it.copy(busy = tr("Kana sieht sich das Ergebnis an …")) }
        try {
            val done = _state.value.steps.filter { it.state != StepState.OPEN }
            val r = a.propose(goal, p, "", done, secrets(), screen = tty?.tap?.screen(1500).orEmpty(), followUp = true)
            _state.update { it.copy(busy = null, steps = done + r.steps, note = r.note, rounds = it.rounds + 1, sent = r.sent.takeUnless { a.where().onDevice }) }
            log?.write("Kana zum Ergebnis ($goal)", (listOf(r.note) + r.steps.map { "${it.risk.name.lowercase()}: ${it.command}" }).joinToString("\n"))
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Waits until Kana's look at the last result is done (tests; the screen just follows [state]). */
    suspend fun awaitLora() { lastJob?.join() }

    fun skip(index: Int) {
        val step = _state.value.steps.getOrNull(index) ?: return
        set(index) { it.copy(state = StepState.SKIPPED) }
        log?.write("Übersprungen: ${step.command}")
    }

    /** The user changed the command; the app assesses it again (risk and notes). */
    fun edit(index: Int, command: String) {
        val step = _state.value.steps.getOrNull(index) ?: return
        val c = command.trim().takeIf { it.isNotEmpty() && '\n' !in it } ?: return
        set(index) { ServerStep(c, it.what, Risks.notes(c).joinToString(" ") { n -> tr(n) }) }
        log?.write("Bearbeitet: ${step.command} → $c")
    }

    /** Types the command into the terminal without Enter – the user checks or completes it there and presses Enter. */
    fun typeIntoTerminal(index: Int) {
        val step = _state.value.steps.getOrNull(index) ?: return
        val t = tty ?: return
        runCatching { t.write("\u0015" + step.command) }
        set(index) { it.copy(state = StepState.DONE, output = tr("Im Terminal eingetragen – Enter drückst du dort.")) }
        log?.write("Ins Terminal eingetragen (ohne Enter): ${step.command}")
    }

    /**
     * Types [line] with Enter into this terminal – one target of „An mehrere Sitzungen senden“ (AP-131 c), which only
     * happens after the user confirmed the list of sessions. False when nothing could be typed.
     */
    fun sendLine(line: String): Boolean {
        val t = tty ?: return false
        if (t.tap.fullScreen || '\n' in line) return false
        val ok = runCatching { t.write("\u0015" + line.trim() + "\r") }.isSuccess
        if (ok) log?.write("An mehrere Sitzungen gesendet (bestätigt): ${line.trim()}")
        return ok
    }

    /** A public key went onto the server (AP-131 a) – offer to log in with [keyFile] from now on. */
    fun offerKeySwitch(keyFile: String) = _state.update { it.copy(keySwitch = keyFile) }

    /** Saves the session with the key instead of the password (the saved password is forgotten). */
    fun switchToKey() {
        val key = _state.value.keySwitch ?: return
        val p = _state.value.profile ?: return
        val np = p.copy(auth = SshAuth.KEY, keyFile = key)
        sessions.save(np)
        sessions.secrets.forget(p.id)
        log?.write("Sitzung meldet sich künftig mit Schlüssel an: ${File(key).name}")
        _state.update { it.copy(profile = np, keySwitch = null) }
    }

    fun dismissKeySwitch() = _state.update { it.copy(keySwitch = null) }

    /** Ends the proposal; open steps are dropped. */
    fun cancel() {
        val open = _state.value.steps.count { it.state == StepState.OPEN }
        if (_state.value.steps.any { it.state == StepState.RUNNING }) tty?.tap?.stopWatching()
        if (_state.value.steps.isNotEmpty()) log?.write("Abgebrochen", if (open > 0) "$open Schritte nicht ausgeführt" else "")
        _state.update { it.copy(steps = emptyList(), flowTitle = null, note = "", sent = null, goal = null, rounds = 0, waitsForInput = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun readLog(): String = log?.read().orEmpty()

    companion object {
        /** How often Kana goes on by herself for one wish before she asks the user. */
        const val MAX_ROUNDS = 5
    }
}
