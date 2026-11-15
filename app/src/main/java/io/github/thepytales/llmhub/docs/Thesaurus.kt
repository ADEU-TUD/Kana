package io.github.thepytales.llmhub.docs

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Thesaurus (AP-126 e, like Word's *Überprüfen › Thesaurus*): German synonyms from OpenThesaurus (www.openthesaurus.de,
 * LGPL), loaded once onto the device (about 1,6 MB) and looked up there – no word leaves the device while writing.
 * The file has one group of words with the same meaning per line, separated by „;“; notes stand in brackets
 * („abhauen (ugs.)“).
 */
object Thesaurus {
    const val URL_TEXT = "https://www.openthesaurus.de/export/OpenThesaurus-Textversion.zip"
    const val SOURCE = "OpenThesaurus (www.openthesaurus.de, LGPL)"

    /** A word of a group: [shown] with its notes, [word] what goes into the text. */
    data class Entry(val shown: String, val word: String)

    class Index(lines: List<List<Entry>>) {
        private val groups = lines
        private val byWord = HashMap<String, MutableList<Int>>().also { m ->
            lines.forEachIndexed { i, g -> g.forEach { e -> m.getOrPut(key(e.word)) { mutableListOf() } += i } }
        }

        val size: Int get() = groups.size

        /**
         * The groups with [word] (without the word itself), the best first; also a word ending (Häuser → Haus, schnellen →
         * schnell) when the word itself is not there.
         */
        fun lookup(word: String): List<List<Entry>> {
            val w = key(word)
            val hits = byWord[w] ?: stems(w).firstNotNullOfOrNull { byWord[it] } ?: return emptyList()
            return hits.map { i -> groups[i].filter { key(it.word) != w } }.filter { it.isNotEmpty() }.distinct().take(12)
        }
    }

    private fun key(w: String) = w.trim().lowercase()

    /** Simple endings of German words (enough to find the base form of most inflected words). */
    private fun stems(w: String): List<String> = listOf("en", "em", "er", "es", "e", "n", "s").filter { w.length > it.length + 2 && w.endsWith(it) }.map { w.dropLast(it.length) } +
        listOfNotNull(w.replace("ä", "a").replace("ö", "o").replace("ü", "u").takeIf { it != w }?.let { it.removeSuffix("er").removeSuffix("e") })

    /** The word of an entry without its notes: „(sich) hinwegsetzen (über)“ → „hinwegsetzen“, „abhauen (ugs.)“ → „abhauen“. */
    fun clean(shown: String): String = shown.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

    /** Reads the text version (lines with „;“, „#“ comments). */
    fun parse(text: String): Index = Index(
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            line.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { Entry(it, clean(it)) }.filter { it.word.isNotEmpty() }
        }.filter { it.size > 1 }.toList(),
    )

    @Volatile private var cache: Pair<File, Index>? = null

    fun file(dir: File) = File(dir, "thesaurus/openthesaurus.txt")

    fun installed(dir: File) = file(dir).isFile && file(dir).length() > 20

    /** The index of the installed thesaurus (read once), or null. */
    fun index(dir: File): Index? {
        val f = file(dir)
        cache?.takeIf { it.first == f }?.let { return it.second }
        if (!installed(dir)) return null
        return parse(f.readText()).also { cache = f to it }
    }

    /** Loads the thesaurus from OpenThesaurus onto the device (blocking; [progress] 0…1). */
    fun download(dir: File, progress: (Double) -> Unit = {}) {
        val con = URL(URL_TEXT).openConnection() as HttpURLConnection
        con.connectTimeout = 20_000
        con.readTimeout = 60_000
        con.setRequestProperty("User-Agent", "ADEU-LLM")
        if (con.responseCode != 200) { con.disconnect(); error("HTTP ${con.responseCode}") }
        val total = con.contentLengthLong.takeIf { it > 0 } ?: 1_600_000L
        val bytes = con.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > 40_000_000) error("zu groß")
                progress((out.size().toDouble() / total).coerceAtMost(0.99))
            }
            out.toByteArray()
        }
        install(dir, bytes)
    }

    /** Takes the text version out of the ZIP of OpenThesaurus (or a plain text file) and keeps it. */
    fun install(dir: File, bytes: ByteArray) {
        val text = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                var found: String? = null
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name.endsWith("openthesaurus.txt")) { found = z.readBytes().decodeToString(); break }
                }
                found
            } ?: error("Keine Thesaurus-Datei im Archiv")
        } else bytes.decodeToString()
        require(parse(text).size > 0) { "Kein Thesaurus" }
        val f = file(dir)
        f.parentFile?.mkdirs()
        f.writeText(text)
        cache = null
    }
}

/**
 * Zwei Fassungen vergleichen (AP-126 e, like Word's *Überprüfen › Vergleichen*): the paragraphs of an older and a newer
 * text, what stayed, what changed (word by word), what came and what went.
 */
object DocCompare {
    enum class Kind { SAME, CHANGED, ADDED, REMOVED }

    data class Change(val kind: Kind, val old: String, val new: String)

    /** The paragraphs of [markdown] as plain text (setting lines and markers left out). */
    fun paragraphs(markdown: String): List<String> = DocBlocks.split(DocMarkup.stripComments(markdown))
        .map { b -> b.lines().joinToString("\n") { l -> io.github.thepytales.llmhub.ui.inlineSegments(l.trim().replace(Regex("^(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")).joinToString("") { it.text } }.trim() }
        .filter { it.isNotBlank() }

    /** The differences of [old] and [new] paragraph by paragraph; a changed paragraph keeps both texts. */
    fun diff(old: String, new: String): List<Change> {
        val a = paragraphs(old)
        val b = paragraphs(new)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = mutableListOf<Change>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { out += Change(Kind.SAME, a[i], b[j]); i++; j++ }
                // Neighbours that are no match on either side and look alike: one changed paragraph.
                i < a.size && j < b.size && dp[i][j] == dp[i + 1][j + 1] && similar(a[i], b[j]) -> { out += Change(Kind.CHANGED, a[i], b[j]); i++; j++ }
                j < b.size && (i >= a.size || dp[i][j + 1] >= dp[i + 1][j]) -> { out += Change(Kind.ADDED, "", b[j]); j++ }
                else -> { out += Change(Kind.REMOVED, a[i], ""); i++ }
            }
        }
        return out
    }

    /** Two paragraphs share at least a third of their words. */
    private fun similar(x: String, y: String): Boolean {
        val wx = x.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val wy = y.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        if (wx.isEmpty() || wy.isEmpty()) return false
        return (wx intersect wy).size * 3 >= minOf(wx.size, wy.size)
    }
}
