package io.github.thepytales.llmhub.docs.text

/**
 * Listen-Optionen (AP-126 d, like Word's „Nummerierung definieren“ and Docs' „Listenoptionen“): the form of the numbers
 * (1. · 1) · a. · a) · A. · i. · I. · 1.1.1.; ids in lower case – `au` is A., `iu` is I. – as the marker only takes those) or the bullet (• – ○ ■ ✓ ➢), a new start („neu beginnen bei 5“) and a list
 * that goes on from the one before („Nummerierung fortsetzen“). The paragraph marker holds them (`lf=a;ls=5`, `ls=w`);
 * the editor, the PDF and the Word file count the same way with [Counter].
 */
object ListStyles {
    /** Forms of numbers: id to how they read. „m“ is the mehrstufige Liste (1., 1.1., 1.1.1.). */
    val NUMBERS = listOf(
        "1" to "1. 2. 3.", "1p" to "1) 2) 3)", "a" to "a. b. c.", "ap" to "a) b) c)", "au" to "A. B. C.",
        "i" to "i. ii. iii.", "iu" to "I. II. III.", "m" to "1. 1.1. 1.1.1.",
    )

    /** Bullets: id to the sign. */
    val BULLETS = listOf("s" to "•", "d" to "–", "o" to "○", "q" to "■", "h" to "✓", "p" to "➢")

    fun known(id: String) = NUMBERS.any { it.first == id } || BULLETS.any { it.first == id }
    fun isNumberForm(id: String?) = id != null && NUMBERS.any { it.first == id }
    fun isBulletForm(id: String?) = id != null && BULLETS.any { it.first == id }

    /** i, ii, iii … (1 to 3999; others as digits). */
    fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var x = n
        return buildString { v.forEachIndexed { i, k -> while (x >= k) { append(s[i]); x -= k } } }
    }

    /** a … z, aa, ab … (as Word counts on after z). */
    fun alpha(n: Int): String {
        if (n < 1) return n.toString()
        var x = n
        val sb = StringBuilder()
        while (x > 0) { x--; sb.append('a' + x % 26); x /= 26 }
        return sb.reverse().toString()
    }

    /** The number [n] at [level] in form [form] (counters of the levels above for „m“). */
    fun number(form: String, counters: IntArray, level: Int): String {
        val n = counters[level]
        return when (form) {
            "1p" -> "$n)"
            "a" -> alpha(n) + "."
            "ap" -> alpha(n) + ")"
            "au" -> alpha(n).uppercase() + "."
            "i" -> roman(n) + "."
            "iu" -> roman(n).uppercase() + "."
            "m" -> (0..level).joinToString(".") { counters[it].coerceAtLeast(1).toString() } + "."
            else -> "$n."
        }
    }

    fun bullet(form: String): String = BULLETS.firstOrNull { it.first == form }?.second ?: "•"

    /** The editor's own forms without Listen-Optionen (as before AP-126 d): 1. / a. by level, • ◦ ▪. */
    fun editorNumber(counters: IntArray, level: Int): String = number(if (level % 3 == 1) "a" else "1", counters, level)
    fun editorBullet(level: Int): String = when (level % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }

    /** Whether [p] says anything about its list (else the exports keep their own marks). */
    fun hasOptions(p: ParaFormat) = p.listFormat != null || p.listStart != null || p.listContinue

    /**
     * Counts the items of the lists of a document in order: [item] for each list item, [end] when a paragraph that is no
     * list item comes. A list that goes on („weiter“) starts from the numbers the list before ended with.
     */
    class Counter {
        private var n = IntArray(6)
        private var last = IntArray(6)
        private var inList = false
        private var numbered = false

        /** A paragraph that is no list item: the list ends. */
        fun end() {
            if (inList && numbered) last = n.copyOf()
            inList = false
            numbered = false
        }

        /**
         * The next item: its label ([forms] null: the editor's forms, else only when the item has Listen-Optionen –
         * null means the export's own mark). A bullet list after a numbered one at level 0 starts anew.
         */
        fun item(isNumbered: Boolean, level: Int, p: ParaFormat, editor: Boolean): String? {
            val lvl = level.coerceIn(0, 5)
            if (!inList || lvl == 0 && isNumbered != numbered && !p.soft) {
                if (inList && numbered) last = n.copyOf()
                n = IntArray(6)
                if (isNumbered && p.listContinue) n = last.copyOf()
                inList = true
            }
            if (lvl == 0) numbered = isNumbered
            if (p.soft) return null
            for (k in lvl + 1 until 6) n[k] = 0
            if (!isNumbered) {
                val form = p.listFormat?.takeIf { isBulletForm(it) }
                return when {
                    form != null -> bullet(form)
                    editor -> editorBullet(lvl)
                    else -> null
                }
            }
            p.listStart?.let { n[lvl] = (it - 1).coerceAtLeast(0) }
            n[lvl]++
            val form = p.listFormat?.takeIf { isNumberForm(it) }
            return when {
                form != null -> number(form, n, lvl)
                editor -> editorNumber(n, lvl)
                hasOptions(p) -> number("1", n, lvl)
                else -> null
            }
        }
    }
}
