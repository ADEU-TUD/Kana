package io.github.thepytales.llmhub.ssh

/**
 * Reads along what the server prints into the terminal (AP-131 d), so Kana can run a command **in the visible
 * terminal** and still know when it ended, with which exit code and what it printed. The command Kana types ends with
 * [suffix]: `; printf '\033]133;D;%s;%s\007' "$?" "$PWD"` – the shell prints an OSC 133 sequence (the same marker
 * VS Code and other terminals use for „command finished“) that the terminal does not show. The echo of the typed line
 * contains the text `\033`, never the real escape character, so it cannot be mistaken for the end.
 *
 * Also notices full-screen programs (vim, top, less – alternate screen), in which Kana must not type, and keeps the last
 * lines of the screen as plain text (escape sequences removed) for Kana's context. Nothing typed by the user passes
 * here – a password typed at a `sudo` prompt is not echoed by the server and so never seen.
 */
class TerminalTap(private val keep: Int = 64_000) {
    /** A command Kana typed has ended. */
    data class Finished(val exitCode: Int?, val cwd: String?, val output: String)

    private val raw = StringBuilder()
    /** Total characters seen so far (positions survive trimming of [raw]). */
    private var seen = 0L
    private var dropped = 0L
    private var watchFrom: Long? = null
    private var onFinished: ((Finished) -> Unit)? = null
    private var pending = ""

    @Volatile var fullScreen = false
        private set
    @Volatile var cwd: String? = null
        private set
    @Volatile var lastOutputAt = 0L
        private set
    /** Called after each chunk (e.g. to notice a password prompt while a command runs). */
    @Volatile var onChunk: (() -> Unit)? = null

    @Synchronized
    fun feed(chars: CharArray, offset: Int, length: Int) = feed(String(chars, offset, length))

    @Synchronized
    fun feed(text: String) {
        if (text.isEmpty()) return
        lastOutputAt = System.currentTimeMillis()
        raw.append(text)
        seen += text.length
        // Alternate screen on/off (vim, less, top, htop, mc …).
        val probe = pending + text
        ALT_SCREEN.findAll(probe).lastOrNull()?.let { fullScreen = it.groupValues[2] == "h" }
        pending = probe.takeLast(16)
        if (raw.length > keep) {
            val cut = raw.length - keep
            raw.delete(0, cut)
            dropped += cut
        }
        watchFrom?.let { from -> check(from) }
        onChunk?.invoke()
    }

    private fun check(from: Long) {
        val start = (from - dropped).coerceAtLeast(0).toInt()
        if (start > raw.length) return
        val part = raw.substring(start)
        val m = MARKER.find(part) ?: return
        val code = m.groupValues[1].toIntOrNull()
        val dir = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
        if (dir != null) cwd = dir
        val output = commandOutput(part.substring(0, m.range.first))
        val f = onFinished
        watchFrom = null
        onFinished = null
        f?.invoke(Finished(code, dir, output))
    }

    /** From now on, waits for the end of the command Kana is about to type. */
    @Synchronized
    fun watch(onEnd: (Finished) -> Unit) {
        watchFrom = seen
        onFinished = onEnd
    }

    /** Stops waiting (the user marked the command as done or it was interrupted). Returns what was printed so far. */
    @Synchronized
    fun stopWatching(): String {
        val from = watchFrom ?: return ""
        watchFrom = null
        onFinished = null
        val start = (from - dropped).coerceAtLeast(0).toInt()
        return commandOutput(raw.substring(start.coerceAtMost(raw.length)))
    }

    val watching: Boolean @Synchronized get() = watchFrom != null

    /** The end of what was printed since the command started (for a password prompt check). */
    @Synchronized
    fun sinceStart(limit: Int = 400): String {
        val from = watchFrom ?: return ""
        val start = (from - dropped).coerceAtLeast(0).toInt().coerceAtLeast(raw.length - limit * 3).coerceAtMost(raw.length)
        return plain(raw.substring(start)).takeLast(limit)
    }

    /** The last [chars] characters of the terminal as plain text – what Kana gets as „Terminal zuletzt“. */
    @Synchronized
    fun screen(chars: Int = 2000): String = plain(raw.takeLast(chars * 3).toString()).takeLast(chars)

    /** Folder from the last prompt line like `deploy@web:/var/www/app$ ` (or `~/app`), when the prompt shows one. */
    @Synchronized
    fun promptFolder(): String? = plain(raw.takeLast(600).toString()).lines().lastOrNull { it.isNotBlank() }
        ?.let { PROMPT.find(it.trimEnd())?.groupValues?.get(1) }

    companion object {
        /** Appended to every command Kana types: prints the end marker with exit code and folder. */
        const val SUFFIX = "; printf '\\033]133;D;%s;%s\\007' \"\$?\" \"\$PWD\""

        fun line(command: String): String = command.trim() + SUFFIX

        private val MARKER = Regex("""\u001B]133;D;(-?\d*)(?:;([^\u0007\u001B]*))?(?:\u0007|\u001B\\)""")
        private val ALT_SCREEN = Regex("""\u001B\[\?(1049|1047|47)([hl])""")
        private val PROMPT = Regex("""^[\w.-]+@[\w.-]+:(~?/?[^\s$#]*)\s?[$#]$""")
        private val ESCAPES = Regex("""\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)|\u001B\[[0-?]*[ -/]*[@-~]|\u001B[()][A-Za-z0-9]|\u001B[=>78MDEHc]""")

        /** Terminal text without colours and control sequences; carriage-return overwrites are resolved per line. */
        fun plain(text: String): String {
            val t = ESCAPES.replace(text, "").replace("\r\n", "\n")
            return t.split('\n').joinToString("\n") { line ->
                val l = line.substringAfterLast('\r')
                buildString { for (ch in l) if (ch == '\b') { if (isNotEmpty()) setLength(length - 1) } else if (ch == '\t' || ch >= ' ') append(ch) }
            }
        }

        /**
         * The output of the command: plain text without the echoed command line. A long line is wrapped by the shell,
         * so the echo ends with the row that holds the end of [SUFFIX] (`"$PWD"`); without it, the first row is the echo.
         */
        fun commandOutput(text: String): String {
            val lines = plain(text).split('\n')
            val head = lines.take(12)
            val end = head.indexOfFirst { SUFFIX_END in it }.takeIf { it >= 0 } ?: head.indexOfFirst { SUFFIX_ECHO in it }
            val start = if (end >= 0) end + 1 else 1
            return lines.drop(start).joinToString("\n").trim('\n')
        }

        private const val SUFFIX_ECHO = "133;D;%s"
        private const val SUFFIX_END = "\"\$PWD\""

        /** Looks like the program waits for typed input (a password or a yes/no question). */
        fun waitsForInput(textSoFar: String): Boolean {
            val last = textSoFar.trimEnd('\n').lines().lastOrNull().orEmpty()
            return Regex("""(?i)(passwor[dt]|kennwort|passphrase)[^\n]*:\s*$""").containsMatchIn(last) ||
                Regex("""(?i)(\[[yjn]/[yjn]\]|\(yes/no(/\[fingerprint\])?\)|\[y/n\])\s*\??\s*$""").containsMatchIn(last)
        }
    }
}
