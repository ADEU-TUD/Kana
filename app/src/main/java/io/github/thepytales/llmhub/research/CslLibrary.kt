package io.github.thepytales.llmhub.research

import io.github.thepytales.llmhub.net.JSON
import io.github.thepytales.llmhub.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * The citation styles the user loaded (AP-138 a): CSL files from the official collection (via the Zotero style
 * repository) or from a file of a publisher or chair. They are stored as `<id>.csl` in [dir]; the independent styles
 * that journal styles ("dependent") refer to lie in `parents/`. The eight built-in styles stay the choice on top.
 */
class CslLibrary(private val dir: File) {
    private val parents = File(dir, "parents")
    @Volatile private var cache: List<CslStyle>? = null

    /** Loaded styles, sorted by title. */
    fun list(): List<CslStyle> = cache ?: synchronized(this) {
        cache ?: (dir.listFiles { f -> f.isFile && f.name.endsWith(".csl") }.orEmpty().mapNotNull { f -> load(f) }
            .sortedBy { it.title.lowercase() }).also { cache = it }
    }

    fun get(id: String): CslStyle? = list().firstOrNull { it.id == id.removePrefix(CslStyle.KEY_PREFIX) }

    private fun load(f: File): CslStyle? = runCatching {
        val style = CslStyle.parse(f.readText(), f.nameWithoutExtension)
        if (style.dependent) {
            val parent = style.parentId?.let { File(parents, "$it.csl") }?.takeIf { it.isFile }?.let { CslStyle.parse(it.readText(), it.nameWithoutExtension) }
            parent?.let { style.withParent(it) }
        } else style
    }.getOrNull()

    /**
     * Stores the style in [xml]. A dependent style (journal) also needs its parent: [fetchParent] loads it by id
     * (from the collection). Returns the usable style.
     */
    suspend fun add(xml: String, fallbackId: String = "", fetchParent: suspend (String) -> String? = { null }): CslStyle {
        val style = CslStyle.parse(xml, fallbackId)
        val usable = if (style.dependent) {
            val parentId = style.parentId ?: throw IllegalArgumentException(tr("Der Zitierstil verweist auf keinen Hauptstil."))
            val existing = File(parents, "$parentId.csl").takeIf { it.isFile }?.readText()
            val parentXml = existing ?: fetchParent(parentId)
                ?: throw IllegalArgumentException(tr("Der Zitierstil „{0}“ baut auf „{1}“ auf, der sich nicht laden ließ.", style.title, parentId))
            val parent = CslStyle.parse(parentXml, parentId)
            if (parent.dependent) throw IllegalArgumentException(tr("Der Hauptstil „{0}“ ist selbst abhängig.", parentId))
            withContext(Dispatchers.IO) { parents.mkdirs(); File(parents, "$parentId.csl").writeText(parentXml) }
            style.withParent(parent)
        } else style
        withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, "${style.id}.csl").writeText(xml) }
        cache = null
        return usable
    }

    fun remove(id: String) {
        File(dir, "${id.removePrefix(CslStyle.KEY_PREFIX)}.csl").delete()
        cache = null
    }
}

/**
 * The official CSL collection through the Zotero style repository: the list of all styles (title, id, citation
 * format, field) and the files. The list (about 3 MB) is kept for a week.
 */
class CslRepository(private val http: OkHttpClient, private val cacheDir: File) {
    data class Entry(val title: String, val name: String, val dependent: Boolean, val format: String, val fields: List<String>)

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", LiteratureSources.USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw IllegalStateException(tr("{0} antwortet mit HTTP {1}", "zotero.org", resp.code))
                else -> resp.body?.string()
            }
        }
    }

    private suspend fun index(): List<Entry> {
        val file = File(cacheDir, "styles-index.json")
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3_600_000L
        val text = if (fresh) file.readText() else (get(INDEX_URL)?.also { t -> withContext(Dispatchers.IO) { cacheDir.mkdirs(); file.writeText(t) } }
            ?: file.takeIf { it.isFile }?.readText() ?: throw IllegalStateException(tr("Die Stil-Sammlung ist nicht erreichbar.")))
        return parseIndex(text)
    }

    /** Styles whose title or id contains every word of [query] (journals, publishers, universities …). */
    suspend fun search(query: String, limit: Int = 40): List<Entry> = rank(index(), query, limit)

    /** The CSL file of style [name] (id in the collection). */
    suspend fun download(name: String): String? = get("https://www.zotero.org/styles/" + CslStyle.slug(name))

    companion object {
        const val INDEX_URL = "https://www.zotero.org/styles-files/styles.json"

        fun parseIndex(text: String): List<Entry> = (runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val cat = o["categories"] as? JsonObject
            Entry(
                title = s("title"), name = s("name"),
                dependent = (o["dependent"] as? JsonPrimitive)?.intOrNull == 1,
                format = (cat?.get("format") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                fields = (cat?.get("fields") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            ).takeIf { it.name.isNotBlank() }
        }

        fun rank(all: List<Entry>, query: String, limit: Int): List<Entry> {
            val words = Reference.normalize(query).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            return all.filter { e ->
                val hay = Reference.normalize(e.title + " " + e.name)
                words.all { it in hay }
            }.sortedWith(compareBy<Entry>({ !Reference.normalize(it.title).startsWith(words.first()) }, { it.dependent }, { it.title.length }))
                .take(limit)
        }

        /** Formats of the collection in plain words. */
        fun formatLabel(f: String) = when (f) {
            "author-date" -> tr("Autor-Jahr")
            "numeric" -> tr("nummeriert")
            "note" -> tr("Fußnoten")
            "author" -> tr("Autor")
            "label" -> tr("Kürzel")
            else -> f
        }
    }
}

/**
 * The style chosen for a reference list or a document: a built-in [CitationStyle] or a loaded CSL style. The key is
 * the enum name or `csl:<id>` (settings, marker in documents).
 */
object CitationStyles {
    /** Set by the app container; tests set their own. */
    @Volatile var library: CslLibrary? = null

    data class Choice(val key: String, val label: String, val style: CitationStyle, val csl: CslStyle?) {
        val german: Boolean get() = csl?.german ?: style.german
    }

    fun resolve(key: String?, fallback: CitationStyle = CitationStyle.APA7_DE): Choice {
        val k = key?.trim().orEmpty()
        if (k.startsWith(CslStyle.KEY_PREFIX)) {
            library?.get(k)?.let { return Choice(it.key, it.title, it.fallback, it) }
        }
        val st = CitationStyle.parse(k) ?: fallback
        return Choice(st.name, st.label, st, null)
    }

    /** A style named by Kana or the user: built-in names ("apa", "ieee"), the key, or id/title of a loaded CSL style. */
    fun find(name: String?): Choice? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        if (n.startsWith(CslStyle.KEY_PREFIX)) return library?.get(n)?.let { Choice(it.key, it.title, it.fallback, it) }
        val slug = CslStyle.slug(n)
        library?.list().orEmpty().firstOrNull { it.id == slug || CslStyle.slug(it.title) == slug }?.let { return Choice(it.key, it.title, it.fallback, it) }
        CitationStyle.parse(n)?.let { return Choice(it.name, it.label, it, null) }
        return library?.list().orEmpty().firstOrNull { slug.length >= 4 && slug in CslStyle.slug(it.title) }?.let { Choice(it.key, it.title, it.fallback, it) }
    }

    /** The built-in styles first, then the loaded ones. */
    fun choices(): List<Choice> = CitationStyle.entries.map { Choice(it.name, it.label, it, null) } +
        library?.list().orEmpty().map { Choice(it.key, it.title + " (CSL)", it.fallback, it) }
}
