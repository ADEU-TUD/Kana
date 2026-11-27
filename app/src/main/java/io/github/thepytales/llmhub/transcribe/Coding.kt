package io.github.thepytales.llmhub.transcribe

import io.github.thepytales.llmhub.data.FileBackend
import io.github.thepytales.llmhub.data.ListStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A category of a coding scheme (qualitative content analysis after Kuckartz or Mayring). */
@Serializable
data class Code(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** #RRGGBB */
    val color: String = "#1E40AF",
    /** Definition: when the code applies. */
    val description: String = "",
    /** Anchor example. */
    val example: String = "",
    /** Main category this subcategory belongs to. */
    val parentId: String? = null,
)

@Serializable
data class Codebook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val codes: List<Code> = emptyList(),
) {
    fun code(id: String) = codes.firstOrNull { it.id == id }

    /**
     * [other] merged into this scheme (AP-136 b, a scheme back from MAXQDA): codes with the same path stay as they are,
     * new ones are added under their main code. Returns the scheme and which id of [other] became which id here.
     */
    fun merge(other: Codebook): Pair<Codebook, Map<String, String>> {
        val ids = HashMap<String, String>()
        val list = codes.toMutableList()
        fun pathOf(book: Codebook, c: Code) = book.path(c).lowercase()
        // Main codes first, so subcodes find their parent.
        other.codes.sortedBy { if (it.parentId == null) 0 else 1 }.forEach { c ->
            val parent = c.parentId?.let { ids[it] }
            val wanted = (other.code(c.parentId ?: "")?.let { p -> p.name + " › " } ?: "") + c.name
            val existing = list.firstOrNull { e -> Codebook(name = name, codes = list).let { b -> pathOf(b, e) } == wanted.lowercase() }
            if (existing != null) ids[c.id] = existing.id
            else {
                val added = c.copy(id = UUID.randomUUID().toString(), parentId = parent)
                list += added
                ids[c.id] = added.id
            }
        }
        return copy(codes = list) to ids
    }

    /** "Haupt › Sub" */
    fun path(code: Code): String = code.parentId?.let { code(it) }?.let { "${it.name} › ${code.name}" } ?: code.name

    companion object {
        val COLORS = listOf("#1E40AF", "#B91C1C", "#047857", "#B45309", "#7C3AED", "#0E7490", "#BE185D", "#4D7C0F", "#9A3412", "#334155")

        /**
         * A coding scheme from text: one code per line, "Code: Definition"; lines indented with spaces or starting
         * with "-" under a main code are its subcodes. Also reads the tables of the template „Kategoriensystem“
         * („| Kategorie | Definition | Ankerbeispiel |“).
         */
        fun parse(name: String, text: String): Codebook {
            val codes = mutableListOf<Code>()
            var parent: Code? = null
            text.lines().forEach { raw ->
                if (raw.isBlank()) return@forEach
                val line = raw.trimEnd()
                if (line.trim().startsWith("|")) {
                    val cells = line.trim().trim('|').split('|').map { it.trim() }
                    if (cells.isEmpty() || cells.all { it.matches(Regex(":?-{2,}:?")) }) return@forEach
                    if (cells[0].lowercase() in setOf("kategorie", "code", "hauptkategorie", "name")) return@forEach
                    val c = Code(name = cells[0].trim('*'), color = COLORS[codes.size % COLORS.size], description = cells.getOrElse(1) { "" }, example = cells.getOrElse(2) { "" })
                    codes += c
                    return@forEach
                }
                if (line.trim().startsWith("#")) return@forEach
                val indented = raw.startsWith("  ") || raw.startsWith("\t") || raw.trimStart().startsWith("- ") && parent != null && raw.startsWith(" ")
                val body = line.trim().removePrefix("- ").removePrefix("* ").replace(Regex("^\\d+(\\.\\d+)*[.)]?\\s+"), "")
                val label = body.substringBefore(":").trim().trim('*')
                val def = if (':' in body) body.substringAfter(':').trim() else ""
                if (label.isEmpty()) return@forEach
                val c = Code(name = label, color = COLORS[codes.size % COLORS.size], description = def, parentId = if (indented) parent?.id else null)
                codes += c
                if (!indented) parent = c
            }
            return Codebook(name = name, codes = codes)
        }
    }
}

/**
 * One code on one passage of a transcript, by one coder. [from] and [to] mark a part of the passage's text (AP-136 b:
 * „Textstelle markieren“; null: the whole passage), [memo] is the coder's note on this coding.
 */
data class Coding(
    val segmentStart: Double,
    val codeId: String,
    val coder: String,
    val at: Long = System.currentTimeMillis(),
    val from: Int? = null,
    val to: Int? = null,
    val memo: String? = null,
) {
    /** The whole passage (no part of its text marked). */
    val whole: Boolean get() = from == null || to == null || to <= from

    /** The coded words of [segment]: the marked part, or the whole passage. */
    fun quote(segment: Segment): String =
        if (whole) segment.text.trim() else segment.text.substring(from!!.coerceIn(0, segment.text.length), to!!.coerceIn(0, segment.text.length)).trim()
}

/** The coding of a transcript, kept in its JSON ("coding"), so it survives edits, backups and moving devices. */
data class TranscriptCoding(val codebookId: String?, val codings: List<Coding>) {
    fun coders(): List<String> = codings.map { it.coder }.distinct().sorted()

    fun at(segment: Segment, coder: String? = null): List<Coding> =
        codings.filter { kotlin.math.abs(it.segmentStart - segment.start) < 0.05 && (coder == null || it.coder == coder) }

    /** Codes or uncodes the whole passage [segment] with [codeId] (the coder's codings of a part stay). */
    fun toggle(segment: Segment, codeId: String, coder: String): TranscriptCoding {
        val existing = at(segment, coder).firstOrNull { it.codeId == codeId && it.whole }
        return copy(codings = if (existing != null) codings - existing else codings + Coding(segment.start, codeId, coder))
    }

    /** Codes the part [from]..[to] of [segment]'s text with [codeId] (AP-136 b); the same part twice stays once. */
    fun mark(segment: Segment, codeId: String, coder: String, from: Int, to: Int, memo: String? = null): TranscriptCoding {
        val a = from.coerceIn(0, segment.text.length)
        val b = to.coerceIn(a, segment.text.length)
        if (a == b) return toggle(segment, codeId, coder)
        if (a == 0 && b == segment.text.length) return if (at(segment, coder).any { it.codeId == codeId && it.whole }) this else toggle(segment, codeId, coder)
        if (at(segment, coder).any { it.codeId == codeId && it.from == a && it.to == b }) return this
        return copy(codings = codings + Coding(segment.start, codeId, coder, from = a, to = b, memo = memo?.takeIf { it.isNotBlank() }))
    }

    /** Without [c]. */
    fun remove(c: Coding): TranscriptCoding = copy(codings = codings - c)

    /** [c] with the memo [memo] (blank: none). */
    fun withMemo(c: Coding, memo: String?): TranscriptCoding = copy(codings = codings.map { if (it == c) it.copy(memo = memo?.trim()?.takeIf { m -> m.isNotEmpty() }) else it })

    /** The codings of the code [codeId] (with its subcodes when [withSubcodes] and a [codebook] is given). */
    fun of(codeId: String, codebook: Codebook? = null, withSubcodes: Boolean = false): List<Coding> {
        val ids = if (withSubcodes && codebook != null) setOf(codeId) + codebook.codes.filter { it.parentId == codeId }.map { it.id } else setOf(codeId)
        return codings.filter { it.codeId in ids }
    }

    companion object {
        fun of(t: Transcript): TranscriptCoding {
            val o = t.raw["coding"] as? JsonObject ?: return TranscriptCoding(null, emptyList())
            val list = (o["codings"] as? JsonArray).orEmpty().mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                Coding(
                    (c["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                    (c["code"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (c["coder"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (c["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    (c["from"] as? JsonPrimitive)?.longOrNull?.toInt(),
                    (c["to"] as? JsonPrimitive)?.longOrNull?.toInt(),
                    (c["memo"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            return TranscriptCoding((o["codebook"] as? JsonPrimitive)?.contentOrNull, list)
        }
    }
}

fun Transcript.withCoding(coding: TranscriptCoding): Transcript = copy(
    raw = JsonObject(raw + ("coding" to buildJsonObject {
        coding.codebookId?.let { put("codebook", it) }
        put("codings", buildJsonArray {
            coding.codings.forEach { c ->
                add(buildJsonObject {
                    put("start", c.segmentStart); put("code", c.codeId); put("coder", c.coder); put("at", c.at)
                    if (!c.whole) { put("from", c.from!!); put("to", c.to!!) }
                    c.memo?.let { put("memo", it) }
                })
            }
        })
    }))
)

/**
 * Auswertung über mehrere Interviews (AP-136 b): how often each code occurs in which interview, and where – the
 * „Code-Matrix“ and „Retrieval“ of MAXQDA, on the transcripts of a project.
 */
object InterviewAnalysis {
    /** One coded place: interview, passage, coding. */
    data class Hit(val transcript: Transcript, val segment: Segment, val coding: Coding)

    /** Per code of [book] the number of codings in each interview (same order as [items]). */
    fun counts(items: List<Pair<Transcript, TranscriptCoding>>, book: Codebook): Map<String, List<Int>> =
        book.codes.associate { code -> code.id to items.map { (_, coding) -> coding.codings.count { it.codeId == code.id } } }

    /** The places of [codeId] (with its subcodes when [withSubcodes]) in all interviews, in order. */
    fun hits(items: List<Pair<Transcript, TranscriptCoding>>, book: Codebook, codeId: String, withSubcodes: Boolean = true): List<Hit> =
        items.flatMap { (t, coding) ->
            val wanted = coding.of(codeId, book, withSubcodes).toSet()
            t.segments.flatMap { s -> coding.at(s).filter { it in wanted }.map { Hit(t, s, it) } }
        }

    /** The matrix as Markdown table (Code | Interview 1 | … | Summe), to copy into a document. */
    fun table(items: List<Pair<Transcript, TranscriptCoding>>, book: Codebook): String = buildString {
        val counts = counts(items, book)
        append("| Code | ").append(items.joinToString(" | ") { it.first.title.replace("|", "/") }).append(" | Summe |\n")
        append("| --- |").append(" --- |".repeat(items.size)).append(" --- |\n")
        book.codes.forEach { c ->
            val row = counts[c.id].orEmpty()
            append("| ").append(book.path(c).replace("|", "/")).append(" | ").append(row.joinToString(" | ")).append(" | ").append(row.sum()).append(" |\n")
        }
    }

    /** The transcript an imported source belongs to: same title, ignoring case, „(pseudonymisiert)“ and a file ending. */
    fun matchSource(name: String, transcripts: List<Transcript>): Transcript? {
        fun norm(s: String) = s.lowercase().replace(Regex("\\.(txt|docx|rtf|pdf)$"), "").replace(Regex("\\((pseudonymisiert|anonymisiert)\\)"), "").replace(Regex("\\s+"), " ").trim()
        val n = norm(name)
        return transcripts.firstOrNull { norm(it.title) == n } ?: transcripts.firstOrNull { n.isNotEmpty() && (norm(it.title).contains(n) || n.contains(norm(it.title))) }
    }
}

/**
 * Kana schlägt Codes vor (AP-136 b): the language model reads one passage with the definitions of the codebook and names
 * the codes that fit – only as suggestions, a person decides (Grundsatz „KI entscheidet nicht“).
 */
object CodeSuggest {
    fun prompt(book: Codebook, passage: String): List<io.github.thepytales.llmhub.data.ChatMessage> = listOf(
        io.github.thepytales.llmhub.data.ChatMessage(
            role = "system",
            content = "Du hilfst bei der qualitativen Inhaltsanalyse. Du bekommst ein Kategoriensystem (Code: Definition) und einen " +
                "Abschnitt eines Interviews. Antworte nur mit den Namen der Codes, die auf den Abschnitt passen – je Zeile einer, genau " +
                "wie im Kategoriensystem geschrieben, höchstens drei, ohne Begründung. Passt keiner, antworte „keiner“.",
        ),
        io.github.thepytales.llmhub.data.ChatMessage(
            role = "user",
            content = "Kategoriensystem:\n" + book.codes.joinToString("\n") { c ->
                "- " + book.path(c) + (if (c.description.isNotBlank()) ": " + c.description else "") + (if (c.example.isNotBlank()) " (Beispiel: " + c.example + ")" else "")
            } + "\n\nAbschnitt:\n" + passage.trim(),
        ),
    )

    /** The codes of [book] named in [answer] (by name or path, case and list marks ignored), at most three. */
    fun parse(answer: String, book: Codebook): List<Code> {
        val lines = answer.lines().map { it.trim().trimStart('-', '*', '•', ' ').trim().trim('„', '“', '"', '*').trim() }.filter { it.isNotEmpty() }
        return lines.mapNotNull { l ->
            book.codes.firstOrNull { c -> c.name.equals(l, true) || book.path(c).equals(l, true) }
                ?: book.codes.firstOrNull { c -> l.contains(c.name, ignoreCase = true) && c.name.length >= 4 }
        }.distinct().take(3)
    }
}

class CodebookStore(dir: File) : ListStore<Codebook>(FileBackend(File(dir, "codebooks.json")), Codebook.serializer(), { it.id })

/** Agreement of two coders, per code (present/absent per passage) and overall. */
object Intercoder {
    data class CodeAgreement(val code: Code, val both: Int, val onlyA: Int, val onlyB: Int, val neither: Int) {
        val n get() = both + onlyA + onlyB + neither
        val percent get() = if (n == 0) 0.0 else (both + neither).toDouble() / n
        /** Cohen's kappa for this code (null if not computable, e.g. nobody used it). */
        val kappa: Double?
            get() {
                if (n == 0) return null
                val po = percent
                val pa = (both + onlyA).toDouble() / n
                val pb = (both + onlyB).toDouble() / n
                val pe = pa * pb + (1 - pa) * (1 - pb)
                return if (pe >= 1.0) null else (po - pe) / (1 - pe)
            }
    }

    data class Result(val coderA: String, val coderB: String, val passages: Int, val perCode: List<CodeAgreement>) {
        /** Share of passages where both chose exactly the same codes. */
        val exactAgreement: Double get() = if (passages == 0) 0.0 else perCodeExact / passages.toDouble()
        internal var perCodeExact = 0
        val meanKappa: Double? get() = perCode.mapNotNull { it.kappa }.takeIf { it.isNotEmpty() }?.average()
    }

    fun interpret(k: Double?): String = when {
        k == null -> "nicht berechenbar"
        k < 0 -> "keine Übereinstimmung"
        k < 0.21 -> "gering"
        k < 0.41 -> "ausreichend"
        k < 0.61 -> "mittelmäßig"
        k < 0.81 -> "beachtlich"
        else -> "(fast) vollkommen"
    }

    fun compare(t: Transcript, coding: TranscriptCoding, codebook: Codebook, a: String, b: String): Result {
        // Only passages that at least one of the two coded count as units (as in MAXQDA's segment comparison).
        val units = t.segments.filter { s -> coding.at(s, a).isNotEmpty() || coding.at(s, b).isNotEmpty() }
        var exact = 0
        val per = codebook.codes.map { code ->
            var both = 0; var onlyA = 0; var onlyB = 0; var neither = 0
            units.forEach { s ->
                val x = coding.at(s, a).any { it.codeId == code.id }
                val y = coding.at(s, b).any { it.codeId == code.id }
                when {
                    x && y -> both++
                    x -> onlyA++
                    y -> onlyB++
                    else -> neither++
                }
            }
            CodeAgreement(code, both, onlyA, onlyB, neither)
        }.filter { it.both + it.onlyA + it.onlyB > 0 }
        units.forEach { s -> if (coding.at(s, a).map { it.codeId }.toSet() == coding.at(s, b).map { it.codeId }.toSet()) exact++ }
        return Result(a, b, units.size, per).also { it.perCodeExact = exact }
    }

    fun report(r: Result): String = buildString {
        append("Intercoder-Übereinstimmung ${r.coderA} / ${r.coderB}: ${r.passages} codierte Abschnitte, ")
        append("identische Codierung in ${"%.0f".format(r.exactAgreement * 100)} %. ")
        r.meanKappa?.let { append("Mittleres Cohens Kappa über die Codes: ${"%.2f".format(it)} (${interpret(it)}).") }
        append("\n\n| Code | beide | nur ${r.coderA} | nur ${r.coderB} | Übereinstimmung | Kappa |\n| --- | --- | --- | --- | --- | --- |\n")
        r.perCode.forEach { c ->
            append("| ${c.code.name} | ${c.both} | ${c.onlyA} | ${c.onlyB} | ${"%.0f".format(c.percent * 100)} % | ${c.kappa?.let { "%.2f".format(it) } ?: "–"} |\n")
        }
    }
}

/**
 * REFI-QDA project exchange (.qdpx, standard 1.0): transcripts as text sources with the codebook, codings (of whole
 * passages or marked parts), memos and comments. MAXQDA, ATLAS.ti, NVivo and QDA Miner import it; [read] takes a
 * project or a codebook (.qdc) from them back (AP-136 b).
 */
object RefiQda {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
    private fun guid() = UUID.randomUUID().toString().uppercase()

    /** The text of the source: one paragraph per passage ("Sprecher: Text"), and where each passage lies in it (code points). */
    fun sourceText(t: Transcript): Pair<String, List<IntRange>> {
        val sb = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        t.segments.forEach { s ->
            val prefix = (if (t.diarized) t.speakerName(s.speaker) + ": " else "") + "#${TranscriptExport.clock(s.start)}# "
            sb.append(prefix)
            val start = sb.codePointCount(0, sb.length)
            sb.append(s.text.trim())
            val end = sb.codePointCount(0, sb.length)
            ranges += start until end
            sb.append("\n")
        }
        return sb.toString() to ranges
    }

    /** Where [c] lies in the source text of its passage [s] (which starts at [range]), in code points. */
    internal fun position(s: Segment, c: Coding, range: IntRange): IntRange {
        if (c.whole) return range
        val text = s.text
        val lead = text.length - text.trimStart().length
        val trimmed = text.trim()
        fun cp(charIndex: Int): Int {
            val i = (charIndex - lead).coerceIn(0, trimmed.length)
            return trimmed.codePointCount(0, i)
        }
        val a = range.first + cp(c.from!!)
        val b = range.first + cp(c.to!!)
        return a until maxOf(a + 1, b).coerceAtMost(range.last + 1)
    }

    fun write(out: java.io.OutputStream, t: Transcript, coding: TranscriptCoding, codebook: Codebook?) = writeProject(out, t.title, listOf(t to coding), codebook)

    /**
     * Several interviews in one project (AP-136 b: „drei Interviews mit Codes in MAXQDA“): one text source each, one
     * codebook, the codings of every coder apart, memos of codings and comments of passages as notes.
     */
    fun writeProject(out: java.io.OutputStream, name: String, items: List<Pair<Transcript, TranscriptCoding>>, codebook: Codebook?) {
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        val users = (items.flatMap { it.second.coders() }.distinct().sorted().ifEmpty { listOf("Kana") }).associateWith { guid() }
        val defaultUser = users.values.first()
        val codeGuids = codebook?.codes?.associate { it.id to guid() }.orEmpty()
        val texts = mutableListOf<Pair<String, String>>()
        val notes = StringBuilder()
        fun note(title: String, text: String, user: String): String {
            val g = guid()
            notes.append("<Note guid=\"$g\" name=\"${esc(title)}\" creatingUser=\"$user\" creationDateTime=\"$now\">")
            notes.append("<PlainTextContent>${esc(text)}</PlainTextContent></Note>\n")
            return g
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<Project name=\"${esc(name)}\" origin=\"Kana\" creatingUserGUID=\"$defaultUser\" creationDateTime=\"$now\" ")
            append("xmlns=\"urn:QDA-XML:project:1.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
            append("<Users>\n")
            users.forEach { (n, g) -> append("<User guid=\"$g\" name=\"${esc(n)}\"/>\n") }
            append("</Users>\n")
            if (codebook != null && codebook.codes.isNotEmpty()) {
                append("<CodeBook>\n<Codes>\n")
                fun codeXml(c: Code) {
                    append("<Code guid=\"${codeGuids[c.id]}\" name=\"${esc(c.name)}\" isCodable=\"true\" color=\"${c.color}\">")
                    val desc = listOf(c.description, c.example.takeIf { it.isNotBlank() }?.let { "Ankerbeispiel: $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
                    if (desc.isNotBlank()) append("<Description>${esc(desc)}</Description>")
                    codebook.codes.filter { it.parentId == c.id }.forEach { codeXml(it) }
                    append("</Code>\n")
                }
                codebook.codes.filter { it.parentId == null || codebook.code(it.parentId) == null }.forEach { codeXml(it) }
                append("</Codes>\n</CodeBook>\n")
            }
            append("<Sources>\n")
            items.forEach { (t, coding) ->
                val sourceGuid = guid()
                val (text, ranges) = sourceText(t)
                texts += sourceGuid to text
                append("<TextSource guid=\"$sourceGuid\" name=\"${esc(t.title)}\" plainTextPath=\"internal://$sourceGuid.txt\" creatingUser=\"$defaultUser\" creationDateTime=\"$now\">\n")
                t.segments.forEachIndexed { i, s ->
                    val here = coding.at(s).filter { codeGuids.containsKey(it.codeId) }
                    // One selection per coder and marked part, so each coder's codings stay apart (intercoder checks in MAXQDA).
                    here.groupBy { it.coder to (it.from to it.to) }.forEach { (key, list) ->
                        val user = users[key.first] ?: defaultUser
                        val r = position(s, list.first(), ranges[i])
                        append("<PlainTextSelection guid=\"${guid()}\" name=\"${esc(list.first().quote(s).take(40))}\" startPosition=\"${r.first}\" endPosition=\"${r.last + 1}\" creatingUser=\"$user\" creationDateTime=\"$now\">\n")
                        list.forEach { cd -> append("<Coding guid=\"${guid()}\" creatingUser=\"$user\" creationDateTime=\"$now\"><CodeRef targetGUID=\"${codeGuids[cd.codeId]}\"/></Coding>\n") }
                        list.mapNotNull { it.memo?.takeIf { m -> m.isNotBlank() }?.let { m -> it to m } }.forEach { (cd, m) ->
                            val code = codebook?.code(cd.codeId)?.name.orEmpty()
                            append("<NoteRef targetGUID=\"${note("Memo $code ${TranscriptExport.clock(s.start)}".replace("  ", " "), m, user)}\"/>\n")
                        }
                        append("</PlainTextSelection>\n")
                    }
                    // Comments on passages, as before: notes on the whole passage.
                    s.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                        val r = ranges[i]
                        append("<PlainTextSelection guid=\"${guid()}\" name=\"${esc("Kommentar " + TranscriptExport.clock(s.start))}\" startPosition=\"${r.first}\" endPosition=\"${r.last + 1}\" creatingUser=\"$defaultUser\" creationDateTime=\"$now\">")
                        append("<NoteRef targetGUID=\"${note("Kommentar ${TranscriptExport.clock(s.start)}", comment, defaultUser)}\"/></PlainTextSelection>\n")
                    }
                }
                append("</TextSource>\n")
            }
            append("</Sources>\n")
            if (notes.isNotEmpty()) append("<Notes>\n").append(notes).append("</Notes>\n")
            append("</Project>\n")
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("project.qde"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            texts.forEach { (g, text) ->
                zip.putNextEntry(ZipEntry("Sources/$g.txt"))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** Coded passages as table (CSV with semicolons, for Excel, SPSS, R): one row per coding, several interviews. */
    fun csv(t: Transcript, coding: TranscriptCoding, codebook: Codebook?): String = csv(listOf(t to coding), codebook)

    fun csv(items: List<Pair<Transcript, TranscriptCoding>>, codebook: Codebook?): String = buildString {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val many = items.size > 1
        append(if (many) "Interview;" else "").append("Zeit;Sprecher;Codierer:in;Code;Text;Memo\n")
        items.forEach { (t, coding) ->
            t.segments.forEach { s ->
                coding.at(s).forEach { c ->
                    val name = codebook?.code(c.codeId)?.let { codebook.path(it) } ?: c.codeId
                    val cells = listOf(TranscriptExport.clock(s.start), t.speakerName(s.speaker), c.coder, name, c.quote(s), c.memo.orEmpty())
                    append((if (many) listOf(t.title) + cells else cells).joinToString(";") { q(it) }).append("\n")
                }
            }
        }
    }

    // ── Import (AP-136 b): a project or codebook of MAXQDA, ATLAS.ti, NVivo … ─────────────────────────────

    /** One marked place of an imported source: code points [start]..[end] of its text, with codes, coder and memo. */
    data class Selection(val start: Int, val end: Int, val codeIds: List<String>, val coder: String, val memo: String?)

    data class Source(val name: String, val text: String, val selections: List<Selection>)

    /** What a .qdpx (or .qdc) contains: the codebook (with new ids) and the text sources with their codings. */
    data class Imported(val codebook: Codebook?, val sources: List<Source>)

    /** Reads a REFI-QDA project (.qdpx, a zip) or codebook (.qdc, XML). */
    fun read(bytes: ByteArray, fileName: String = "Projekt"): Imported {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val files = HashMap<String, ByteArray>()
        if (isZip) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                generateSequence { z.nextEntry }.forEach { e -> if (!e.isDirectory) files[e.name.replace('\\', '/')] = z.readBytes() }
            }
        }
        val xmlBytes = if (isZip) files["project.qde"] ?: files.entries.firstOrNull { it.key.endsWith(".qde") }?.value ?: error("Kein project.qde in der Datei") else bytes
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setExpandEntityReferences(false) }
        }
        val doc = f.newDocumentBuilder().parse(xmlBytes.inputStream())
        val root = doc.documentElement
        fun children(e: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> =
            (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? org.w3c.dom.Element }.filter { it.tagName.substringAfter(':') == tag }
        fun all(e: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> {
            val nl = e.getElementsByTagName("*")
            return (0 until nl.length).mapNotNull { nl.item(it) as? org.w3c.dom.Element }.filter { it.tagName.substringAfter(':') == tag }
        }
        val users = all(root, "User").associate { it.getAttribute("guid") to it.getAttribute("name").ifBlank { "Codierer:in" } }
        // Codes with their nesting; ids of this app.
        val codes = mutableListOf<Code>()
        val byGuid = HashMap<String, String>()
        fun addCode(e: org.w3c.dom.Element, parent: String?) {
            val c = Code(
                name = e.getAttribute("name").ifBlank { "Code" },
                color = e.getAttribute("color").takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.uppercase() ?: Codebook.COLORS[codes.size % Codebook.COLORS.size],
                description = children(e, "Description").firstOrNull()?.textContent?.trim().orEmpty(),
                parentId = parent,
            )
            codes += c
            byGuid[e.getAttribute("guid")] = c.id
            children(e, "Code").forEach { addCode(it, c.id) }
        }
        val codeBookEl = if (root.tagName.substringAfter(':') == "CodeBook") root else all(root, "CodeBook").firstOrNull()
        codeBookEl?.let { cb -> children(cb, "Codes").forEach { cs -> children(cs, "Code").forEach { addCode(it, null) } } }
        val notes = all(root, "Note").associate { n -> n.getAttribute("guid") to (children(n, "PlainTextContent").firstOrNull()?.textContent?.trim().orEmpty()) }
        val sources = all(root, "TextSource").mapNotNull { src ->
            val path = src.getAttribute("plainTextPath")
            val text = children(src, "PlainTextContent").firstOrNull()?.textContent ?: run {
                val key = when {
                    path.startsWith("internal://") -> "Sources/" + path.removePrefix("internal://")
                    else -> path.substringAfterLast('/')
                }
                (files[key] ?: files.entries.firstOrNull { it.key.endsWith("/" + key.substringAfterLast('/')) }?.value)?.toString(Charsets.UTF_8)
            } ?: return@mapNotNull null
            val selections = children(src, "PlainTextSelection").mapNotNull { sel ->
                val a = sel.getAttribute("startPosition").toIntOrNull() ?: return@mapNotNull null
                val b = sel.getAttribute("endPosition").toIntOrNull() ?: return@mapNotNull null
                val codings = children(sel, "Coding")
                val ids = codings.flatMap { c -> children(c, "CodeRef").mapNotNull { byGuid[it.getAttribute("targetGUID")] } }
                if (ids.isEmpty()) return@mapNotNull null
                val user = codings.firstOrNull()?.getAttribute("creatingUser")?.takeIf { it.isNotBlank() } ?: sel.getAttribute("creatingUser")
                val memo = children(sel, "NoteRef").mapNotNull { notes[it.getAttribute("targetGUID")] }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
                Selection(a, b, ids, users[user] ?: "Codierer:in", memo)
            }
            Source(src.getAttribute("name").ifBlank { fileName }, text, selections)
        }
        val name = (codeBookEl?.getAttribute("name")?.takeIf { it.isNotBlank() } ?: root.getAttribute("name").ifBlank { fileName.substringBeforeLast('.') })
        return Imported(if (codes.isEmpty()) null else Codebook(name = name, codes = codes), sources)
    }

    /** How an imported source was put onto a transcript: how many codings found their place, how many did not. */
    data class Placed(val coding: TranscriptCoding, val placed: Int, val missed: Int)

    /**
     * Puts the codings of [src] onto the transcript [t]: by position when the source is this transcript's own export (or
     * unchanged), otherwise by finding the coded words in its passages. A coded place over several passages is split.
     */
    fun place(t: Transcript, src: Source, coding: TranscriptCoding): Placed {
        val (ownText, ranges) = sourceText(t)
        val same = ownText.trimEnd() == src.text.trimEnd()
        val cps = src.text.codePoints().toArray()
        fun slice(a: Int, b: Int) = String(cps, a.coerceIn(0, cps.size), (b.coerceIn(0, cps.size) - a.coerceIn(0, cps.size)).coerceAtLeast(0))
        var result = coding
        var placed = 0
        var missed = 0
        src.selections.forEach { sel ->
            var hit = false
            if (same) {
                t.segments.forEachIndexed { i, s ->
                    val r = ranges[i]
                    val a = maxOf(sel.start, r.first)
                    val b = minOf(sel.end, r.last + 1)
                    if (a >= b) return@forEachIndexed
                    val text = s.text
                    val lead = text.length - text.trimStart().length
                    val trimmed = text.trim()
                    val from = lead + trimmed.offsetByCodePoints(0, (a - r.first).coerceIn(0, trimmed.codePointCount(0, trimmed.length)))
                    val to = lead + trimmed.offsetByCodePoints(0, (b - r.first).coerceIn(0, trimmed.codePointCount(0, trimmed.length)))
                    sel.codeIds.forEach { id -> result = result.mark(s, id, sel.coder, from, to, sel.memo) }
                    hit = true
                }
            } else {
                val words = slice(sel.start, sel.end).trim()
                val norm = words.replace(Regex("\\s+"), " ")
                if (norm.isNotEmpty()) t.segments.firstOrNull { norm in it.text.replace(Regex("\\s+"), " ") || words in it.text }?.let { s ->
                    val i = s.text.indexOf(words).takeIf { it >= 0 } ?: s.text.replace(Regex("\\s+"), " ").indexOf(norm)
                    if (i >= 0) {
                        sel.codeIds.forEach { id -> result = result.mark(s, id, sel.coder, i, (i + words.length).coerceAtMost(s.text.length), sel.memo) }
                        hit = true
                    }
                }
            }
            if (hit) placed += sel.codeIds.size else missed += sel.codeIds.size
        }
        return Placed(result, placed, missed)
    }
}
