package io.github.thepytales.llmhub.research

import io.github.thepytales.llmhub.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Volltexte finden (AP-138 c): looks for a free copy (Open Access) of a work and stores the PDF in the Kana-Ordner
 * under „Literatur/Volltexte“ – named „Autor Jahr – Titel.pdf“. The entry of the collection remembers the file.
 */
object FullTexts {
    const val FOLDER = "Literatur/Volltexte"
    private const val MAX_BYTES = 80L * 1024 * 1024

    sealed interface Outcome {
        /** The PDF was stored. */
        data class Stored(val file: File, val relative: String, val from: LiteratureSources.OaLocation) : Outcome
        /** Only a page with the free text was found (no PDF to download), e.g. a repository page. */
        data class PageOnly(val url: String, val from: LiteratureSources.OaLocation) : Outcome
        data object NotFound : Outcome
    }

    /** „Helmke 2009 – Unterrichtsqualität und Lehrerprofessionalität.pdf“ */
    fun fileName(r: Reference): String {
        val who = r.creators.firstOrNull()?.family?.let { if (r.creators.size > 2) "$it et al." else if (r.creators.size == 2) it + " & " + r.creators[1].family else it }.orEmpty()
        val title = r.title.ifBlank { r.raw }.substringBefore(": ").take(80)
        val base = listOf(listOf(who, r.year).filter { it.isNotBlank() }.joinToString(" "), title).filter { it.isNotBlank() }.joinToString(" – ")
        return base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "Volltext" } + ".pdf"
    }

    /** Looks for [r] and stores the first PDF that can be downloaded in [root]/Literatur/Volltexte. */
    suspend fun fetch(sources: LiteratureSources, http: OkHttpClient, r: Reference, root: File): Outcome {
        val locations = sources.openAccessLocations(r)
        if (locations.isEmpty()) return Outcome.NotFound
        val dir = File(root, FOLDER)
        for (l in locations.filter { it.pdfUrl.isNotBlank() }) {
            val bytes = runCatching { download(http, l.pdfUrl) }.getOrNull() ?: continue
            val file = withContext(Dispatchers.IO) {
                dir.mkdirs()
                var f = File(dir, fileName(r))
                var n = 2
                while (f.exists() && f.length() != bytes.size.toLong()) f = File(dir, fileName(r).removeSuffix(".pdf") + " ($n).pdf").also { n++ }
                f.writeBytes(bytes)
                f
            }
            return Outcome.Stored(file, root.toPath().relativize(file.toPath()).toString().replace('\\', '/'), l)
        }
        return locations.firstOrNull { it.landingUrl.isNotBlank() }?.let { Outcome.PageOnly(it.landingUrl, it) } ?: Outcome.NotFound
    }

    /** The PDF behind [url], or null if the address does not give one (login page, HTML, too large). */
    suspend fun download(http: OkHttpClient, url: String): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", LiteratureSources.USER_AGENT).header("Accept", "application/pdf,*/*;q=0.5").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            if (body.contentLength() > MAX_BYTES) return@use null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var tooLarge = false
            body.byteStream().use { input ->
                while (!tooLarge) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    tooLarge = out.size() > MAX_BYTES
                }
            }
            val bytes = out.toByteArray()
            if (tooLarge || !isPdf(bytes)) null else bytes
        }
    }

    fun isPdf(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        return "%PDF-" in head
    }

    /** Short line for the user about where the text came from. */
    fun describe(l: LiteratureSources.OaLocation): String {
        val version = when (l.version) {
            "publishedVersion" -> tr("Verlagsfassung")
            "acceptedVersion" -> tr("angenommene Fassung")
            "submittedVersion" -> tr("Preprint")
            else -> ""
        }
        return listOf(l.host, version, l.license.uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
