package io.github.thepytales.llmhub.files

import java.nio.ByteBuffer

/**
 * Reads the tables of an SQLite database file (read-only, without a native library), e.g. a Citavi project
 * (AP-138 d). Supports the file format 3 with table b-trees, overflow pages and UTF-8/UTF-16 text; indexes, WAL files
 * and writing are not needed. Rows come as maps column name → value (Long, Double, String, ByteArray or null).
 */
class SqliteReader(private val bytes: ByteArray) {
    private val pageSize: Int
    private val usable: Int
    private val encoding: Int

    init {
        require(isSqlite(bytes)) { "Keine SQLite-Datenbank" }
        val ps = u16(16)
        pageSize = if (ps == 1) 65536 else ps
        usable = pageSize - (bytes[20].toInt() and 0xff)
        encoding = u32(56).toInt().takeIf { it in 1..3 } ?: 1
    }

    data class Table(val name: String, val rootPage: Int, val columns: List<String>, val rowidColumn: String?)

    /** The tables of the database (from sqlite_master). */
    val tables: List<Table> by lazy {
        rows(1).mapNotNull { (_, values) ->
            if (values.getOrNull(0) != "table") return@mapNotNull null
            val name = values.getOrNull(1) as? String ?: return@mapNotNull null
            val root = (values.getOrNull(3) as? Long)?.toInt() ?: return@mapNotNull null
            val sql = values.getOrNull(4) as? String ?: ""
            val (cols, rowid) = columns(sql)
            Table(name, root, cols, rowid)
        }
    }

    fun table(name: String): Table? = tables.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** All rows of [name] (empty if the table does not exist). */
    fun read(name: String): List<Map<String, Any?>> {
        val t = table(name) ?: return emptyList()
        return rows(t.rootPage).map { (rowid, values) ->
            t.columns.mapIndexed { i, col ->
                col to (if (col == t.rowidColumn) (values.getOrNull(i) ?: rowid) else values.getOrNull(i))
            }.toMap(LinkedHashMap())
        }
    }

    // ── B-tree ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun rows(root: Int): List<Pair<Long, List<Any?>>> {
        val out = mutableListOf<Pair<Long, List<Any?>>>()
        val stack = ArrayDeque<Int>().apply { add(root) }
        val seen = HashSet<Int>()
        while (stack.isNotEmpty()) {
            val page = stack.removeLast()
            if (!seen.add(page) || page < 1 || (page.toLong() - 1) * pageSize >= bytes.size) continue
            val base = (page - 1) * pageSize
            val header = if (page == 1) base + 100 else base
            val type = bytes[header].toInt() and 0xff
            val cells = u16(header + 3)
            when (type) {
                0x0D -> for (i in 0 until cells) {
                    val off = base + u16(header + 8 + 2 * i)
                    runCatching { out += leafCell(off) }
                }
                0x05 -> {
                    // Children in order: the cells' left pointers, then the right-most pointer (stack: reverse).
                    val children = (0 until cells).map { i -> u32(base + u16(header + 12 + 2 * i)).toInt() } + u32(header + 8).toInt()
                    children.asReversed().forEach { stack.add(it) }
                }
                else -> {}
            }
        }
        return out
    }

    private fun leafCell(off: Int): Pair<Long, List<Any?>> {
        var p = off
        val (payload, n1) = varint(p); p += n1
        val (rowid, n2) = varint(p); p += n2
        val total = payload.toInt()
        val x = usable - 35
        val local = if (total <= x) total else {
            val m = ((usable - 12) * 32 / 255) - 23
            val k = m + ((total - m) % (usable - 4))
            if (k <= x) k else m
        }
        val data = ByteArray(total)
        System.arraycopy(bytes, p, data, 0, local)
        if (local < total) {
            var next = u32(p + local).toInt()
            var at = local
            while (next != 0 && at < total) {
                val start = (next - 1) * pageSize
                val n = minOf(usable - 4, total - at)
                System.arraycopy(bytes, start + 4, data, at, n)
                at += n
                next = u32(start).toInt()
            }
        }
        return rowid to record(data)
    }

    private fun record(d: ByteArray): List<Any?> {
        val (headerSize, n0) = varint(d, 0)
        var h = n0
        val types = mutableListOf<Long>()
        while (h < headerSize) {
            val (t, n) = varint(d, h); h += n
            types += t
        }
        var p = headerSize.toInt()
        return types.map { t ->
            when {
                t == 0L -> null
                t in 1..6 -> {
                    val len = intArrayOf(0, 1, 2, 3, 4, 6, 8)[t.toInt()]
                    var v = d[p].toLong() // sign from the first byte
                    for (i in 1 until len) v = (v shl 8) or (d[p + i].toLong() and 0xff)
                    p += len
                    v
                }
                t == 7L -> ByteBuffer.wrap(d, p, 8).double.also { p += 8 }
                t == 8L -> 0L
                t == 9L -> 1L
                t >= 12 && t % 2 == 0L -> {
                    val len = ((t - 12) / 2).toInt()
                    d.copyOfRange(p, p + len).also { p += len }
                }
                t >= 13 -> {
                    val len = ((t - 13) / 2).toInt()
                    val s = String(d, p, len, when (encoding) { 2 -> Charsets.UTF_16LE; 3 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 })
                    p += len
                    s
                }
                else -> null
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun u16(o: Int) = ((bytes[o].toInt() and 0xff) shl 8) or (bytes[o + 1].toInt() and 0xff)
    private fun u32(o: Int): Long = ((bytes[o].toLong() and 0xff) shl 24) or ((bytes[o + 1].toLong() and 0xff) shl 16) or
        ((bytes[o + 2].toLong() and 0xff) shl 8) or (bytes[o + 3].toLong() and 0xff)

    private fun varint(o: Int) = varint(bytes, o)

    companion object {
        fun isSqlite(b: ByteArray) = b.size >= 100 && String(b, 0, 15, Charsets.US_ASCII) == "SQLite format 3"

        /** SQLite variable-length integer: value and number of bytes. */
        fun varint(b: ByteArray, o: Int): Pair<Long, Int> {
            var v = 0L
            for (i in 0 until 8) {
                val x = b[o + i].toInt() and 0xff
                v = (v shl 7) or (x and 0x7f).toLong()
                if (x and 0x80 == 0) return v to i + 1
            }
            return ((v shl 8) or (b[o + 8].toLong() and 0xff)) to 9
        }

        /** Column names of a CREATE TABLE statement and the INTEGER PRIMARY KEY column (stored as rowid). */
        fun columns(sql: String): Pair<List<String>, String?> {
            val open = sql.indexOf('(')
            val close = sql.lastIndexOf(')')
            if (open < 0 || close <= open) return emptyList<String>() to null
            val body = sql.substring(open + 1, close)
            val parts = mutableListOf<String>()
            var depth = 0
            var quote: Char? = null
            val cur = StringBuilder()
            body.forEach { ch ->
                when {
                    quote != null -> { cur.append(ch); if (ch == quote || quote == '[' && ch == ']') quote = null }
                    ch == '"' || ch == '`' || ch == '\'' || ch == '[' -> { quote = ch; cur.append(ch) }
                    ch == '(' -> { depth++; cur.append(ch) }
                    ch == ')' -> { depth--; cur.append(ch) }
                    ch == ',' && depth == 0 -> { parts += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotBlank()) parts += cur.toString()
            val constraint = Regex("^(CONSTRAINT|PRIMARY|UNIQUE|CHECK|FOREIGN)\\b", RegexOption.IGNORE_CASE)
            var rowid: String? = null
            val names = parts.map { it.trim() }.filter { it.isNotEmpty() && !constraint.containsMatchIn(it) }.map { def ->
                val name = Regex("^(\"[^\"]+\"|`[^`]+`|\\[[^]]+]|'[^']+'|\\S+)").find(def)!!.value.trim('"', '`', '[', ']', '\'')
                if (Regex("^\\S+\\s+INTEGER\\s+PRIMARY\\s+KEY", RegexOption.IGNORE_CASE).containsMatchIn(def.replace(Regex("^(\"[^\"]+\"|`[^`]+`|\\[[^]]+])"), "x"))) rowid = name
                name
            }
            return names to rowid
        }
    }
}
