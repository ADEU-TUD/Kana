package io.github.thepytales.llmhub.research

import io.github.thepytales.llmhub.docs.ScanRaster

/**
 * ISBN per Kamera (AP-138 f): reads EAN-13 barcodes (the ISBN on the back of a book, 978/979…) from a photo – on the
 * device, without a library. Scans many rows (and the turned picture), finds the guard bars and decodes the digits
 * by their bar widths; only codes with a correct check digit count. DOIs and ISBNs as text: [identifiers].
 */
object Barcodes {
    private val L = listOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val G = L.map { code -> code.map { if (it == '0') '1' else '0' }.joinToString("").reversed() }
    private val R = L.map { code -> code.map { if (it == '0') '1' else '0' }.joinToString("") }
    private val FIRST = listOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")

    /** Run widths of the four bars/spaces of each code word, normalized to 7 modules. */
    private fun widths(code: String): IntArray {
        val out = IntArray(4)
        var i = 0
        var k = 0
        while (i < 7) {
            var n = 0
            val ch = code[i]
            while (i < 7 && code[i] == ch) { n++; i++ }
            out[k++] = n
        }
        return out
    }

    private val L_W = L.map(::widths)
    private val G_W = G.map(::widths)
    private val R_W = R.map(::widths)

    fun checkDigitOk(digits: String): Boolean {
        if (digits.length != 13 || !digits.all(Char::isDigit)) return false
        val sum = digits.take(12).mapIndexed { i, ch -> (ch - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10 == digits[12] - '0'
    }

    /** All EAN-13 codes found in [img], most frequent first. */
    fun ean13(img: ScanRaster): List<String> {
        val votes = HashMap<String, Int>()
        listOf(img, img.rotated(1)).forEach { r ->
            val step = (r.height / 60).coerceAtLeast(1)
            var y = r.height / 10
            while (y < r.height * 9 / 10) {
                val gray = IntArray(r.width) { x -> luma(r[x, y]) }
                decodeRow(gray)?.let { votes[it] = (votes[it] ?: 0) + 1 }
                decodeRow(gray.reversedArray())?.let { votes[it] = (votes[it] ?: 0) + 1 }
                y += step
            }
        }
        return votes.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** The ISBN of a book barcode (978/979), if one is in the picture. */
    fun isbn(img: ScanRaster): String? = ean13(img).firstOrNull { it.startsWith("978") || it.startsWith("979") }

    private fun luma(argb: Int): Int = ((argb shr 16 and 0xff) * 299 + (argb shr 8 and 0xff) * 587 + (argb and 0xff) * 114) / 1000

    /** Decodes one scan line (grey values); null if no valid EAN-13 is on it. */
    fun decodeRow(gray: IntArray): String? {
        if (gray.size < 95) return null
        // Threshold between dark and light, locally in windows of the line (uneven light on photos).
        val win = (gray.size / 8).coerceAtLeast(16)
        val bits = BooleanArray(gray.size) { x ->
            val a = (x - win).coerceAtLeast(0)
            val b = (x + win).coerceAtMost(gray.size - 1)
            var min = 255
            var max = 0
            var i = a
            while (i <= b) { val v = gray[i]; if (v < min) min = v; if (v > max) max = v; i += 2 }
            if (max - min < 40) false else gray[x] < (min + max) / 2
        }
        // Runs: alternating dark/light lengths, starting with the first dark run.
        val runs = ArrayList<Int>()
        val dark = ArrayList<Boolean>()
        var x = 0
        while (x < bits.size) {
            val v = bits[x]
            var n = 0
            while (x < bits.size && bits[x] == v) { n++; x++ }
            runs += n
            dark += v
        }
        for (s in runs.indices) {
            if (!dark[s] || s + 59 > runs.size) continue
            // Start guard: bar, space, bar of one module each, after a quiet zone.
            val m = (runs[s] + runs[s + 1] + runs[s + 2]) / 3.0
            if (m < 1.0 || !near(runs[s], m) || !near(runs[s + 1], m) || !near(runs[s + 2], m)) continue
            if (s > 0 && runs[s - 1] < m * 4) continue
            decodeAt(runs, s + 3)?.let { return it }
        }
        return null
    }

    private fun near(w: Int, m: Double) = w in (m * 0.4).toInt()..(m * 1.9 + 1).toInt()

    private fun decodeAt(runs: List<Int>, start: Int): String? {
        var p = start
        val left = StringBuilder()
        val parity = StringBuilder()
        repeat(6) {
            val w = runs.subList(p, p + 4)
            val (d, set) = best(w, listOf(L_W, G_W)) ?: return null
            left.append(d)
            parity.append(if (set == 0) 'L' else 'G')
            p += 4
        }
        // Middle guard: five runs of one module.
        val mid = runs.subList(p, p + 5)
        val mm = mid.sum() / 5.0
        if (mid.any { !near(it, mm) }) return null
        p += 5
        val right = StringBuilder()
        repeat(6) {
            if (p + 4 > runs.size) return null
            val (d, _) = best(runs.subList(p, p + 4), listOf(R_W)) ?: return null
            right.append(d)
            p += 4
        }
        val first = FIRST.indexOf(parity.toString()).takeIf { it >= 0 } ?: return null
        val code = "$first$left$right"
        return code.takeIf(::checkDigitOk)
    }

    /** The digit whose bar widths fit [w] best (and from which set), or null if none fits well. */
    private fun best(w: List<Int>, sets: List<List<IntArray>>): Pair<Int, Int>? {
        val total = w.sum().toDouble()
        if (total <= 0) return null
        val norm = w.map { it * 7.0 / total }
        var bestErr = Double.MAX_VALUE
        var bestDigit = -1
        var bestSet = -1
        sets.forEachIndexed { si, set ->
            set.forEachIndexed { d, ref ->
                var err = 0.0
                for (i in 0 until 4) err += kotlin.math.abs(norm[i] - ref[i])
                if (err < bestErr) { bestErr = err; bestDigit = d; bestSet = si }
            }
        }
        return if (bestErr < 1.6) bestDigit to bestSet else null
    }

    private val DOI = Regex("\\b(10\\.\\d{4,9}/[^\\s\"<>]+)", RegexOption.IGNORE_CASE)
    private val ISBN = Regex("(?:ISBN(?:-1[03])?:?\\s*)?((?:97[89][-\\s]?)?\\d{1,5}[-\\s]?\\d{1,7}[-\\s]?\\d{1,7}[-\\s]?[\\dX])\\b", RegexOption.IGNORE_CASE)

    /** DOIs and ISBNs in recognized text (photo of a title page or the first page of an article). */
    fun identifiers(text: String): Pair<List<String>, List<String>> {
        val dois = DOI.findAll(text.replace(Regex("(?<=10\\.\\d{4})\\s+(?=/)"), "")).map { it.groupValues[1].trimEnd('.', ',', ';', ')', ']') }.distinct().toList()
        val isbns = ISBN.findAll(text).map { it.groupValues[1].replace(Regex("[-\\s]"), "").uppercase() }
            .filter { it.length == 13 && checkDigitOk(it) || it.length == 10 && isbn10Ok(it) }.distinct().toList()
        return dois to isbns
    }

    fun isbn10Ok(s: String): Boolean {
        if (s.length != 10) return false
        val sum = s.mapIndexed { i, ch -> (if (ch == 'X' && i == 9) 10 else ch.digitToIntOrNull() ?: return false) * (10 - i) }.sum()
        return sum % 11 == 0
    }
}
