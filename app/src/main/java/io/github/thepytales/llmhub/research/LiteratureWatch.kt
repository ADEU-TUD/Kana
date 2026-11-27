package io.github.thepytales.llmhub.research

import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.Repeat
import io.github.thepytales.llmhub.data.ScheduledTask
import io.github.thepytales.llmhub.ui.tr
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** A stored search of the Literatur-Wächter (AP-138 e). */
@Serializable
data class LitWatchSpec(
    val query: String,
    /** Names of [LitSource] entries. */
    val sources: List<String> = listOf("OPENALEX", "CROSSREF"),
    /** List of the collection the new works go into. */
    val list: String = "",
    /** ISO 639-1 codes (empty = all). */
    val languages: List<String> = emptyList(),
    /** Keys of works already shown (not shown again). */
    val seen: List<String> = emptyList(),
    /** Day of the last run (ISO); the next run looks for works published since then. */
    val lastRun: String? = null,
)

/**
 * Literatur-Wächter (AP-138 e): only when the user asks for it – a scheduled task that looks once a week for new
 * works of a stored search and puts them as a list into the collection and a result chat. Kana does not interrupt:
 * no notification by default, the result waits in „Geplante Aufgaben“ and in the list.
 */
object LiteratureWatch {
    const val MAX_SEEN = 800

    fun listName(query: String) = tr("Wächter: {0}", query.trim().take(60))

    /** A weekly task (Monday at [hour]) for [query]. */
    fun task(query: String, model: String, sources: List<LitSource>, languages: List<String> = emptyList(), weekday: Int = 1, hour: Int = 7, list: String? = null): ScheduledTask {
        val q = query.trim()
        return ScheduledTask(
            name = tr("Literatur-Wächter: {0}", q).take(80),
            prompt = tr("Neue Literatur zu „{0}“ suchen (einmal pro Woche).", q),
            model = model,
            repeat = Repeat.DAYS, days = listOf(weekday.coerceIn(1, 7)), hour = hour.coerceIn(0, 23), minute = 0,
            webTools = false, documentTools = false, notify = false,
            litWatch = LitWatchSpec(q, sources.map { it.name }.ifEmpty { listOf("OPENALEX", "CROSSREF") }, list?.ifBlank { null } ?: listName(q), languages),
        )
    }

    data class Found(val fresh: List<Reference>, val spec: LitWatchSpec)

    /** Searches what appeared since the last run (on the first run: the last [firstDays] days) and was not seen yet. */
    suspend fun search(sources: LiteratureSources, spec: LitWatchSpec, known: Set<String>, today: LocalDate = LocalDate.now(), firstDays: Long = 14): Found {
        val since = spec.lastRun?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.minusDays(1) ?: today.minusDays(firstDays)
        val filter = SearchFilter(fromDate = since.toString(), languages = spec.languages.toSet())
        val chosen = spec.sources.mapNotNull { LitSource.parse(it) }.ifEmpty { listOf(LitSource.OPENALEX) }
        val results = mutableListOf<Reference>()
        var failures = 0
        chosen.forEach { src ->
            val hits = runCatching {
                when (src) {
                    LitSource.OPENALEX -> sources.openAlexSearch(spec.query, 25, filter)
                    LitSource.CROSSREF -> sources.crossrefSearch(spec.query, 20, filter)
                    LitSource.ERIC -> sources.ericSearch(spec.query, 20, filter)
                    LitSource.EUROPEPMC -> sources.europePmcSearch(spec.query, 20, filter)
                    LitSource.ARXIV -> sources.arxivSearch(spec.query, 20, filter)
                    LitSource.DOAJ -> sources.doajSearch(spec.query, 20, filter)
                    LitSource.DNB -> sources.dnbSearch(spec.query, 15, filter)
                    LitSource.SLUB -> sources.slubSearch(spec.query, 15, filter)
                }
            }.onFailure { failures++ }.getOrDefault(emptyList())
            results += hits
        }
        if (failures == chosen.size) throw IllegalStateException(tr("Keine Datenbank erreichbar – der Wächter versucht es beim nächsten Mal wieder."))
        val seen = spec.seen.toSet() + known
        val fresh = results.filter { it.title.isNotBlank() }
            // Only works of the period (some databases ignore the date filter for undated records).
            .filter { r -> r.year.toIntOrNull()?.let { it >= since.year } ?: true }
            .distinctBy { it.dedupeKey }
            .filter { it.dedupeKey !in seen }
        val newSeen = (spec.seen + fresh.map { it.dedupeKey }).takeLast(MAX_SEEN)
        return Found(fresh, spec.copy(seen = newSeen, lastRun = today.toString()))
    }

    /** One run of [task]: new works go into the list; returns the text of the result chat. */
    suspend fun run(c: AppContainer, task: ScheduledTask): String {
        val spec = task.litWatch ?: throw IllegalStateException(tr("Diese Aufgabe hat keine gespeicherte Suche."))
        val known = c.literature.current.map { it.dedupeKey }.toSet()
        val found = search(c.researchTools.sources(null), spec, known)
        c.tasks.update(task.id) { it.copy(litWatch = found.spec) }
        if (found.fresh.isNotEmpty()) c.literature.addAll(found.fresh, spec.list)
        return report(spec, found.fresh)
    }

    fun report(spec: LitWatchSpec, fresh: List<Reference>): String = buildString {
        if (fresh.isEmpty()) {
            append(tr("Keine neuen Werke zu „{0}“ seit der letzten Suche.", spec.query))
            return@buildString
        }
        append(tr("{0} neue Werke zu „{1}“ – gespeichert in der Liste „{2}“ (Literatur › Sammlung):", fresh.size, spec.query, spec.list))
        append("\n")
        fresh.take(40).forEach { r ->
            append("\n- ").append(r.short())
            val link = r.doiUrl.ifBlank { r.openAccessUrl.ifBlank { r.url } }
            if (link.isNotBlank()) append(" – ").append(link)
        }
        if (fresh.size > 40) append("\n\n").append(tr("… und {0} weitere in der Liste.", fresh.size - 40))
    }
}
