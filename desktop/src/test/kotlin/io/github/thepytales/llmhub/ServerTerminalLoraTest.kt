package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.ssh.ServerAssistant
import io.github.thepytales.llmhub.ssh.ServerFiles
import io.github.thepytales.llmhub.ssh.ServerHelper
import io.github.thepytales.llmhub.ssh.ServerMask
import io.github.thepytales.llmhub.ssh.ServerStep
import io.github.thepytales.llmhub.ssh.ServerTerminal
import io.github.thepytales.llmhub.ssh.ServerWorkspace
import io.github.thepytales.llmhub.ssh.SshAuth
import io.github.thepytales.llmhub.ssh.SshConnection
import io.github.thepytales.llmhub.ssh.SshKeys
import io.github.thepytales.llmhub.ssh.SshProfile
import io.github.thepytales.llmhub.ssh.SshTty
import io.github.thepytales.llmhub.ssh.StepState
import io.github.thepytales.llmhub.ssh.TerminalTap
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AP-131 against an SSH/SFTP server embedded in the test: Kana types an approved command into the terminal (a shell that
 * behaves like bash), knows when it ended and what it printed, waits for a password typed by the user, looks at the
 * result and answers or goes on – each further command again only after approval; files over SFTP (list, up- and
 * download, cancel, rename, edit and save, delete); a new Ed25519 key goes into authorized_keys and logs in; two
 * sessions in tabs side by side, one line to both only through the confirmed broadcast. No real server anywhere.
 */
class ServerTerminalLoraTest {
    private val base = Files.createTempDirectory("adeu-ssh-131").toFile()
    private val user = "deploy"
    private val password = "Geheim-123!"
    private val sudoPassword = "Sudo-Pass-77"
    private val hostKey = KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256)
    private val root = File(base, "server-root").apply { File(this, "var/www/app/config").mkdirs() }
    private val home = File(base, "home").apply { mkdirs() }
    /** Lines the shells received (after Enter), per shell. */
    private val lines: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())
    private var shells = 0

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKey)
        setPasswordAuthenticator { u, p, _ -> u == user && p == password }
        // Like OpenSSH: a key logs in when its line is in ~/.ssh/authorized_keys.
        setPublickeyAuthenticator { u, key, _ ->
            val f = File(home, ".ssh/authorized_keys")
            u == user && f.isFile && f.readLines().any { it.split(' ').take(2).joinToString(" ") == PublicKeyEntry.toString(key) }
        }
        setShellFactory { _ -> BashLike(synchronized(this@ServerTerminalLoraTest) { ++shells }) }
        subsystemFactories = listOf(SftpSubsystemFactory())
        fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        start()
    }

    @AfterTest
    fun cleanUp() {
        runCatching { server.stop(true) }
        base.deleteRecursively()
    }

    private fun profile(id: String = "p1") = SshProfile(id = id, name = "Testserver $id", host = "127.0.0.1", port = server.port, user = user, auth = SshAuth.PASSWORD, workDir = "/var/www/app")

    /** Kana as a stand-in: answers from a list and keeps every request exactly as it would leave the device. */
    private class FakeLora(vararg answers: String) : ServerHelper {
        val answers = ArrayDeque(answers.toList())
        val asked = mutableListOf<Pair<Boolean, String>>()
        override fun where() = ServerAssistant.Where("test", false, "Test")
        override suspend fun propose(wish: String, profile: SshProfile, recent: String, done: List<ServerStep>, secrets: Collection<String>, screen: String, followUp: Boolean): ServerAssistant.Proposal {
            val mask = ServerMask(secrets, profile)
            val sent = ServerAssistant.request(wish, profile, recent, done, mask, screen, followUp)
            asked += followUp to sent
            val (steps, note) = ServerAssistant.parse(mask.unmask(answers.removeFirstOrNull() ?: """{"steps":[],"note":"Fertig."}"""))
            return ServerAssistant.Proposal(steps, note, "test", sent)
        }
    }

    /** A shell like bash: echoes the line, runs a few commands, prints the end marker when asked and the prompt. */
    private inner class BashLike(private val id: Int) : Command {
        private lateinit var input: InputStream
        private lateinit var out: OutputStream
        private lateinit var exit: ExitCallback
        private var cwd = "/home/deploy"
        override fun setInputStream(`in`: InputStream) { input = `in` }
        override fun setOutputStream(out: OutputStream) { this.out = out }
        override fun setErrorStream(err: OutputStream) {}
        override fun setExitCallback(callback: ExitCallback) { exit = callback }
        override fun destroy(channel: ChannelSession) {}
        private fun say(s: String) { out.write(s.replace("\n", "\r\n").toByteArray()); out.flush() }
        private fun prompt() = say("deploy@web:$cwd$ ")
        private fun readLine(echo: Boolean): String? {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                when (c) {
                    0x15 -> b.clear()
                    0x03 -> return "\u0003"
                    '\r'.code, '\n'.code -> { if (echo) say(b.toString() + "\n") else say("\n"); return b.toString() }
                    else -> b.append(c.toChar())
                }
            }
        }
        override fun start(channel: ChannelSession, env: Environment) {
            Thread {
                say("Willkommen auf dem Testserver\n"); prompt()
                while (true) {
                    val line = readLine(echo = true) ?: break
                    if (line == "\u0003") { say("^C\n"); prompt(); continue }
                    lines += id to line
                    if (line == "exit") break
                    val marked = line.endsWith(TerminalTap.SUFFIX)
                    val cmd = line.removeSuffix(TerminalTap.SUFFIX).trim()
                    var interrupted = false
                    val code = when {
                        cmd.startsWith("cd ") -> { cwd = cmd.removePrefix("cd ").removePrefix("-- ").trim('\'', ' '); 0 }
                        cmd == "df -h /" -> { say("Filesystem      Size  Used Avail Use% Mounted on\n/dev/sda1        50G   38G   12G  76% /\n"); 0 }
                        cmd.startsWith("echo ") -> { say(cmd.removePrefix("echo ") + "\n"); 0 }
                        cmd == "false" -> 1
                        cmd.startsWith("sudo ") -> {
                            say("[sudo] password for deploy: ")
                            val typed = readLine(echo = false)
                            if (typed == sudoPassword) { say("\u001B[32mok\u001B[0m\n"); 0 } else { say("Sorry, try again.\n"); 1 }
                        }
                        cmd.startsWith("sleep ") -> { if (readLine(echo = false) == "\u0003") { say("^C\n"); interrupted = true }; 130 }
                        else -> { say("bash: ${cmd.substringBefore(' ')}: command not found\n"); 127 }
                    }
                    if (marked && !interrupted) say("\u001B]133;D;$code;$cwd\u0007")
                    prompt()
                }
                exit.onExit(0)
            }.start()
        }
    }

    /** Connected, with an open terminal whose output is read like JediTerm does it. */
    private fun connected(t: ServerTerminal, id: String = "p1"): SshTty = runBlocking {
        t.knownHosts.accept("127.0.0.1", server.port, hostKey.public)
        t.connect(profile(id), password)
        assertTrue(t.state.value.connected, t.state.value.error ?: "")
        val tty = SshTty(t.connection!!.shell(100, 30), "test")
        Thread { val buf = CharArray(1024); while (runCatching { tty.read(buf, 0, buf.size) }.getOrDefault(-1) >= 0) Unit }.apply { isDaemon = true }.start()
        t.attach(tty)
        waitFor { "deploy@web:/var/www/app$" in tty.tap.screen() }
        tty
    }

    private fun waitFor(ms: Long = 10_000, what: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what(), "Zeit abgelaufen")
    }

    @Test
    fun loraTypesTheApprovedCommandIntoTheTerminalAndSaysWhatCameOut() = runBlocking {
        val lora = FakeLora(
            """{"steps":[{"command":"df -h /","what":"Zeigt, wie voll die Festplatte ist.","risk":"read"}],"note":"Ich sehe nach, wie viel Platz frei ist."}""",
            """{"steps":[],"note":"Auf / sind noch 12 GB von 50 GB frei (76 % belegt)."}""",
        )
        val t = ServerTerminal(File(base, "ssh"), lora)
        val tty = connected(t)
        assertEquals("/var/www/app", t.terminalFolder(), "das Terminal startet im Ordner der Anwendung")
        val before = lines.size

        t.propose("Wie viel Speicherplatz ist frei?")
        val s = t.state.value
        assertEquals("Ich sehe nach, wie viel Platz frei ist.", s.note, "Kana sagt kurz, was sie vorhat")
        assertEquals("df -h /", s.steps.single().command)
        assertEquals(before, lines.size, "Vorschlagen tippt nichts ein")
        val asked = lora.asked.single().second
        assertTrue("Terminal zuletzt" in asked && "deploy@web:<app-ordner>$" in asked, "Kana sieht das Terminal – Ordner pseudonymisiert:\n$asked")

        assertTrue(t.runInTerminal(0), t.state.value.error ?: "")
        waitFor { t.state.value.steps[0].state == StepState.DONE }
        assertEquals("df -h /" + TerminalTap.SUFFIX, lines.last().second, "genau dieser Befehl, mit Enter, im sichtbaren Terminal")
        val step = t.state.value.steps[0]
        assertEquals(0, step.exitCode)
        assertTrue("12G" in step.output && "Mounted on" in step.output, step.output)
        assertFalse("133;D" in step.output || "printf" in step.output, "weder Marke noch Befehlszeile in der Ausgabe: ${step.output}")
        assertFalse(t.runInTerminal(0), "ein fertiger Schritt läuft nicht zweimal")

        t.awaitLora()
        val after = t.state.value
        assertEquals("Auf / sind noch 12 GB von 50 GB frei (76 % belegt).", after.note, "Kana sagt, was herauskam")
        assertTrue(lora.asked.last().first && "12G" in lora.asked.last().second, "Kana bekommt die Ausgabe zum Auswerten")
        assertEquals(1, after.steps.size, "nichts Neues, nichts ausgeführt")
        assertEquals(1, lines.count { it.first == 1 && it.second.startsWith("df") })
        val log = t.readLog()
        assertTrue("Zugestimmt, im Terminal ausgeführt: df -h /" in log && "Ende mit Exit 0: df -h /" in log && "Kana zum Ergebnis" in log, log)
        tty.close(); t.disconnect()
    }

    @Test
    fun afterAFailureLoraProposesTheNextStepWhichAgainWaitsForAClick() = runBlocking {
        val lora = FakeLora(
            """{"steps":[{"command":"false","what":"Prüft etwas."},{"command":"echo danach","what":"Wäre der zweite Schritt."}],"note":"Zwei Schritte."}""",
            """{"steps":[{"command":"echo repariert","what":"Behebt es.","risk":"change"}],"note":"Der erste Schritt ging schief – so geht es weiter."}""",
        )
        val t = ServerTerminal(File(base, "ssh"), lora)
        val tty = connected(t)
        t.propose("Mach etwas")
        assertTrue(t.runInTerminal(0))
        waitFor { t.state.value.steps[0].state == StepState.FAILED }
        assertEquals(1, t.state.value.steps[0].exitCode)
        t.awaitLora()
        val s = t.state.value
        assertEquals(listOf("false", "echo repariert"), s.steps.map { it.command }, "der veraltete zweite Schritt fällt weg, Kanas neuer kommt dazu")
        assertEquals(StepState.OPEN, s.steps[1].state, "nichts läuft ohne Klick")
        assertTrue(lines.none { "echo" in it.second }, "weder der veraltete noch der neue Schritt wurde eingetippt")
        assertTrue(t.runInTerminal(1))
        waitFor { t.state.value.steps[1].state == StepState.DONE }
        assertEquals("repariert", t.state.value.steps[1].output)
        t.awaitLora()
        assertEquals("Fertig.", t.state.value.note)
        tty.close(); t.disconnect()
    }

    @Test
    fun passwordForSudoIsTypedInTheTerminalAndNeverSeenByLora() = runBlocking {
        val lora = FakeLora(
            """{"steps":[{"command":"sudo systemctl reload apache2","what":"Lädt Apache neu."}],"note":"Ich lade Apache neu."}""",
            """{"steps":[],"note":"Apache ist neu geladen."}""",
        )
        val t = ServerTerminal(File(base, "ssh"), lora)
        val tty = connected(t)
        t.propose("Apache neu laden")
        assertTrue(t.runInTerminal(0))
        waitFor { t.state.value.waitsForInput }
        assertEquals(StepState.RUNNING, t.state.value.steps[0].state, "wartet, bis die Nutzerin das Passwort im Terminal tippt")
        tty.write("$sudoPassword\r")
        waitFor { t.state.value.steps[0].state == StepState.DONE }
        assertFalse(t.state.value.waitsForInput)
        t.awaitLora()
        assertEquals("ok", t.state.value.steps[0].output.lines().last(), "Farben entfernt")
        val everything = t.readLog() + lora.asked.joinToString { it.second } + t.state.value.steps.joinToString { it.output } + tty.tap.screen(10_000)
        assertFalse(sudoPassword in everything, "das getippte Passwort sieht weder Kana noch das Protokoll")
        tty.close(); t.disconnect()
    }

    @Test
    fun loraDoesNotTypeIntoFullScreenProgramsAndCanStopARunningCommand() = runBlocking {
        val lora = FakeLora("""{"steps":[{"command":"sleep 100","what":"Wartet."}],"note":""}""")
        val t = ServerTerminal(File(base, "ssh"), lora)
        val tty = connected(t)
        t.propose("Warte")
        tty.tap.feed("\u001B[?1049h")
        assertFalse(t.runInTerminal(0), "vim, less, top: Kana tippt nicht hinein")
        assertTrue("vim" in t.state.value.error.orEmpty())
        tty.tap.feed("\u001B[?1049l")
        assertTrue(t.runInTerminal(0))
        waitFor { lines.any { it.second.startsWith("sleep 100") } }
        assertEquals(StepState.RUNNING, t.state.value.steps[0].state)
        t.interrupt(0)
        assertEquals(StepState.FAILED, t.state.value.steps[0].state)
        assertEquals(130, t.state.value.steps[0].exitCode)
        assertTrue("Abgebrochen (Strg+C): sleep 100" in t.readLog())
        tty.close(); t.disconnect()
    }

    @Test
    fun loraStopsAfterSomeRoundsAndAsks() = runBlocking {
        val again = """{"steps":[{"command":"false","what":"Nochmal."}],"note":"Ich versuche es weiter."}"""
        val lora = FakeLora(*Array(ServerTerminal.MAX_ROUNDS + 2) { again })
        val t = ServerTerminal(File(base, "ssh"), lora)
        val tty = connected(t)
        t.propose("Endlos")
        repeat(ServerTerminal.MAX_ROUNDS + 1) {
            val i = t.state.value.steps.indexOfFirst { it.state == StepState.OPEN }
            assertTrue(t.runInTerminal(i))
            waitFor { t.state.value.steps[i].state == StepState.FAILED }
            t.awaitLora()
        }
        assertEquals(ServerTerminal.MAX_ROUNDS + 1, lora.asked.size, "erste Frage plus höchstens ${ServerTerminal.MAX_ROUNDS} von allein")
        assertTrue("hält hier an" in t.state.value.note)
        tty.close(); t.disconnect()
    }

    @Test
    fun terminalTapReadsWhatTheServerPrinted() {
        val tap = TerminalTap()
        tap.watch { }
        tap.feed("df -h /; printf '\\033]133;D;%s;%s\\007' \"\$?\" \"\$PWD\"\r\n")
        assertTrue(tap.watching, "das Echo der Befehlszeile ist noch nicht das Ende")
        var end: TerminalTap.Finished? = null
        tap.stopWatching()
        tap.watch { end = it }
        tap.feed("x" + TerminalTap.SUFFIX + "\r\n\u001B[1;31mFehler\u001B[0m\r\nZeile 2\r\n\u001B]133;D;2;/srv/app\u0007deploy@web:/srv/app$ ")
        val e = assertNotNull(end)
        assertEquals(2, e.exitCode)
        assertEquals("/srv/app", e.cwd)
        assertEquals("Fehler\nZeile 2", e.output)
        assertEquals("/srv/app", tap.promptFolder())
        assertEquals("100%", TerminalTap.plain("  5%\r 50%\r100%"))
        assertTrue(TerminalTap.waitsForInput("[sudo] password for deploy: "))
        assertTrue(TerminalTap.waitsForInput("Do you want to continue? [Y/n] "))
        assertFalse(TerminalTap.waitsForInput("Passwort wurde geändert."))
    }

    @Test
    fun filesOfTheServerOverSftp() {
        File(root, "var/www/app/config/app.conf").writeText("debug = true\n")
        File(root, "var/www/app/README").writeText("hallo")
        val t = ServerTerminal(File(base, "ssh"))
        runBlocking { t.knownHosts.accept("127.0.0.1", server.port, hostKey.public); t.connect(profile(), password) }
        val f = assertNotNull(t.openFiles(), t.state.value.error ?: "")
        val app = f.resolve("/var/www/app")
        val list = f.list(app)
        assertEquals(listOf("config", "README"), list.map { it.name }, "Ordner zuerst")
        assertTrue(list[0].isDir && list[0].permissions.length == 9)

        // Upload, download, refuse to overwrite without asking.
        val local = File(base, "neu.txt").apply { writeText("Inhalt") }
        val remote = f.upload(local, app)
        assertEquals("Inhalt", File(root, "var/www/app/neu.txt").readText())
        assertTrue(runCatching { f.upload(local, app) }.isFailure, "ohne Nachfrage wird nichts ersetzt")
        local.writeText("Neu"); f.upload(local, app, replace = true)
        assertEquals("Neu", File(root, "var/www/app/neu.txt").readText())
        val back = File(base, "zurueck/neu.txt")
        f.download(remote, back)
        assertEquals("Neu", back.readText())

        // A configuration file: fetch, change, save back.
        val conf = ServerFiles.child(app, "config/app.conf")
        val text = assertNotNull(f.readText(conf))
        f.writeText(conf, text.replace("true", "false"))
        assertEquals("debug = false\n", File(root, "var/www/app/config/app.conf").readText())
        File(root, "var/www/app/bild.bin").writeBytes(byteArrayOf(1, 0, 2, 0))
        assertNull(f.readText(ServerFiles.child(app, "bild.bin")), "keine Binärdateien im Editor")

        // Rename, folder, delete with everything inside.
        val renamed = f.rename(remote, "umbenannt.txt")
        assertTrue(File(root, "var/www/app/umbenannt.txt").isFile && !File(root, "var/www/app/neu.txt").exists())
        val dir = f.mkdir(app, "alt")
        f.upload(back, dir)
        val alt = f.list(app).first { it.name == "alt" }
        assertEquals(2, f.countInside(alt))
        f.delete(alt)
        assertFalse(File(root, "var/www/app/alt").exists())
        f.delete(f.list(app).first { it.path == renamed })

        // A big upload can be stopped; the half file does not stay.
        val big = File(base, "gross.bin").apply { writeBytes(ByteArray(40 * 1024 * 1024)) }
        val stopper = Thread { while (f.transfer.value == null || f.transfer.value!!.done == 0L) Thread.sleep(1); f.cancelTransfer() }.apply { start() }
        assertTrue(runCatching { f.upload(big, app) }.exceptionOrNull() is ServerFiles.Cancelled)
        stopper.join(5000)
        assertFalse(File(root, "var/www/app/gross.bin").exists())
        assertNull(f.transfer.value)
        val log = t.readLog()
        assertTrue("Hochgeladen: neu.txt" in log && "Gespeichert: $conf" in log && "Gelöscht: " in log, log)
        t.disconnect()
    }

    @Test
    fun newKeyGoesIntoAuthorizedKeysAndLogsIn() = runBlocking {
        val sshDir = File(base, "ssh")
        val k = SshKeys.create(sshDir, "lora web/01", "Pass-Phrase-1", "lora@test-pc")
        assertEquals("lora-web-01", k.privateKey.name)
        assertTrue(k.publicLine.startsWith("ssh-ed25519 ") && k.publicLine.endsWith(" lora@test-pc"))
        assertTrue(k.privateKey.readText().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        if ("posix" in k.privateKey.toPath().fileSystem.supportedFileAttributeViews()) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(k.privateKey.toPath()), "nur fürs eigene Konto lesbar")
        }
        assertTrue(runCatching { SshConnection.loadKeys(k.privateKey, null) }.isFailure, "geschützt durch die Passphrase")
        assertEquals(k.publicLine, SshKeys.publicLineOf(k.privateKey, null), "öffentlicher Teil aus der .pub-Datei")
        assertNotNull(SshKeys.fileFor(sshDir, "lora web/01").takeIf { it.name == "lora-web-01-2" }, "nie eine vorhandene Datei überschreiben")

        // The step that puts it on the server – run twice by a real shell: the line is there once, rights as OpenSSH wants.
        val bash = File("/bin/bash")
        if (bash.canExecute()) repeat(2) {
            val p = ProcessBuilder(bash.path, "-c", SshKeys.installCommand(k.publicLine)).apply { environment()["HOME"] = home.absolutePath }.inheritIO().start()
            assertEquals(0, p.waitFor())
        } else { File(home, ".ssh").mkdirs(); File(home, ".ssh/authorized_keys").writeText(k.publicLine + "\n") }
        val auth = File(home, ".ssh/authorized_keys")
        assertEquals(1, auth.readLines().count { it == k.publicLine })
        if (bash.canExecute()) assertEquals("rw-------", ServerFiles.permissions(Files.getAttribute(auth.toPath(), "unix:mode") as Int))

        // The session offers the step; after it ran, Kana offers to log in with the key, and that works.
        val t = ServerTerminal(sshDir)
        val tty = connected(t)
        t.offer("Schlüssel auf den Server legen", SshKeys.installStep(k.publicLine), keyFile = k.privateKey.absolutePath)
        assertEquals(StepState.OPEN, t.state.value.steps.single().state)
        assertNull(t.state.value.keySwitch, "erst nach dem Schritt")
        t.skip(0)
        assertNull(t.state.value.keySwitch)
        t.offer("Schlüssel auf den Server legen", ServerStep("echo gelegt", "Test"), keyFile = k.privateKey.absolutePath)
        assertTrue(t.runInTerminal(0))
        waitFor { t.state.value.keySwitch != null }
        t.switchToKey()
        val p = t.sessions.get("p1")!!
        assertEquals(SshAuth.KEY, p.auth)
        assertEquals(k.privateKey.absolutePath, p.keyFile)
        tty.close(); t.disconnect()
        t.connect(p, "Pass-Phrase-1")
        assertTrue(t.state.value.connected, "mit dem neuen Schlüssel angemeldet: ${t.state.value.error}")
        t.disconnect()
    }

    @Test
    fun twoSessionsInTabsAndOneLineToBothOnlyWhenChosen() = runBlocking {
        val ws = ServerWorkspace(File(base, "ssh")) { FakeLora("""{"steps":[{"command":"echo eins","what":"x"}],"note":"Nur hier."}""") }
        val a = ws.current
        val b = ws.add()
        assertEquals(1, ws.active.value)
        val ta = connected(a, "p1")
        val tb = connected(b, "p2")
        assertEquals(2, ws.connected().size)

        a.propose("Etwas in A")
        assertEquals("Nur hier.", a.state.value.note)
        assertTrue(b.state.value.steps.isEmpty(), "Kana hilft nur in der Sitzung, in der gefragt wurde")

        assertEquals(1, ws.broadcast("echo an-einen", listOf(b)), "nur die gewählte Sitzung")
        waitFor { lines.any { it.second == "echo an-einen" } }
        assertEquals(1, lines.count { it.second == "echo an-einen" })
        assertEquals(2, ws.broadcast("echo an-alle", ws.connected()))
        waitFor { lines.count { it.second == "echo an-alle" } == 2 }
        assertEquals(2, lines.filter { it.second == "echo an-alle" }.map { it.first }.distinct().size, "in beiden Shells")
        assertTrue("An mehrere Sitzungen gesendet (bestätigt): echo an-alle" in a.readLog())

        ws.close(1)
        assertEquals(1, ws.tabs.value.size)
        assertFalse(b.state.value.connected, "geschlossener Tab trennt die Verbindung")
        assertTrue(a.state.value.connected)
        ws.close(0)
        assertEquals(1, ws.tabs.value.size, "es bleibt immer ein Tab")
        assertFalse(ws.current.state.value.connected)
        ta.close(); tb.close()
    }
}
