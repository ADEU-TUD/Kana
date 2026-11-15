package io.github.thepytales.llmhub.docs

/** Counts of a document's Markdown for *Datei › Details* (AP-126 b): the text as it is read, without the markup. */
data class DocCounts(
    val words: Int, val chars: Int, val charsNoSpaces: Int, val paragraphs: Int, val images: Int, val tables: Int, val notes: Int,
) {
    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}’'.,\\-]*")

        fun of(markdown: String): DocCounts {
            var images = 0
            var tables = 0
            var paragraphs = 0
            val text = StringBuilder()
            var inTable = false
            for (raw in markdown.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> { inTable = false; continue }
                    DocMarkup.isComment(line) || line == DocMarkup.TOC || DocMarkup.isPageBreak(line) -> continue
                    DocMarkup.image(line) != null -> { images++; continue }
                    line.startsWith("|") -> {
                        if (!inTable) { tables++; inTable = true }
                        if (Regex("^\\|?\\s*:?-{2,}").containsMatchIn(line)) continue
                        text.append(line.trim('|').replace('|', ' ')).append('\n')
                        continue
                    }
                }
                paragraphs++
                // Markup out: list marks, headings, emphasis, links, HTML marks, paragraph markers.
                val plain = line
                    .replace(Regex("<!--.*?-->"), "")
                    .replace(Regex("<[^>]+>"), "")
                    .replace(Regex("^(#{1,6}\\s+|[-*+]\\s+(\\[[ xX]]\\s+)?|\\d+[.)]\\s+|>\\s*)"), "")
                    .replace(Regex("\\[\\^[^]]+]:?"), "")
                    .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
                    .replace(Regex("(\\*\\*|__|\\*|_|~~|`)"), "")
                text.append(plain).append('\n')
            }
            val notes = Regex("^\\[\\^[^]]+]:", RegexOption.MULTILINE).findAll(markdown).count()
            val t = text.toString().trimEnd()
            return DocCounts(
                words = WORD.findAll(t).count(),
                chars = t.replace("\n", "").length,
                charsNoSpaces = t.count { !it.isWhitespace() },
                paragraphs = paragraphs - notes,
                images = images, tables = tables, notes = notes,
            )
        }
    }
}
