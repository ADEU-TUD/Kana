package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.research.CitationStyles
import io.github.thepytales.llmhub.research.CslRepository
import io.github.thepytales.llmhub.research.CslStyle
import io.github.thepytales.llmhub.research.Person
import io.github.thepytales.llmhub.research.RefType
import io.github.thepytales.llmhub.research.Reference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sample works for the preview of a style. */
internal object StyleSamples {
    val article = Reference(
        id = "sample-a", type = RefType.ARTICLE, authors = listOf(Person("Praetorius", "Anna-Katharina"), Person("Klieme", "Eckhard")),
        year = "2018", title = "Generic dimensions of teaching quality", container = "ZDM Mathematics Education", volume = "50", issue = "3",
        pages = "407-417", doi = "10.1007/s11858-018-0918-4",
    )
    val book = Reference(
        id = "sample-b", type = RefType.BOOK, authors = listOf(Person("Helmke", "Andreas")), year = "2009",
        title = "Unterrichtsqualität und Lehrerprofessionalität", edition = "2", publisher = "Klett-Kallmeyer", place = "Seelze",
    )
}

/**
 * Choice of the citation style: the eight built-in styles, the loaded CSL styles and „Weitere Stile …“, which opens
 * the search in the official collection (AP-138 a). [selected] and [onSelect] use the key (enum name or `csl:<id>`).
 */
@Composable
fun CitationStyleField(c: AppContainer, label: String, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var version by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val choices = remember(version) { CitationStyles.choices() }
    val more = "\u0000more"
    ChoiceField(label, choices.map { it.key to it.label } + (more to tr("Weitere Stile (CSL) …")), CitationStyles.resolve(selected).key, { k ->
        if (k == more) searching = true else onSelect(k)
    }, modifier)
    if (searching) CslSearchDialog(c, onDismiss = { searching = false }) { st ->
        searching = false
        version++
        onSelect(st.key)
    }
}

/** Einstellungen › Literatur: the loaded CSL styles, search in the collection, own file. */
@Composable
fun CslStylesSection(c: AppContainer, defaultKey: String, onDefault: (String) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val styles = remember(version) { c.cslStyles.list() }
    val importFile = rememberFileImport(arrayOf("*/*")) { input ->
        val xml = input.readBytes().toString(Charsets.UTF_8)
        runCatching { c.cslStyles.add(xml) { id -> c.cslRepository.download(id) } }
            .onSuccess { Notices.info(tr("Zitierstil „{0}“ geladen", it.title)); version++ }
            .onFailure { Notices.error(it.message ?: tr("Zitierstil lässt sich nicht laden")) }
    }
    HintText(tr("Neben den eingebauten Stilen lassen sich beliebige Zitierstile im CSL-Format laden – aus der offiziellen Sammlung (über 10 000 Stile von Verlagen, Zeitschriften und Hochschulen) oder als Datei vom Verlag oder Lehrstuhl."))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { searching = true }) { Text(tr("In der Sammlung suchen")) }
        OutlinedButton(onClick = importFile) { Text(tr("Datei laden (.csl)")) }
    }
    if (styles.isEmpty()) HintText(tr("Noch keine eigenen Stile geladen."))
    styles.forEach { st ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.title, style = MaterialTheme.typography.bodyMedium)
                HintText(CslRepository.formatLabel(st.format) + " · " + st.bibliography(listOf(StyleSamples.article)).firstOrNull()?.second.orEmpty().replace("*", ""))
            }
            if (defaultKey != st.key) TextButton(onClick = { onDefault(st.key) }) { Text(tr("Als Standard")) }
            else StatusPill(tr("Standard"), PillTone.SUCCESS)
            IconButton(onClick = {
                val xml = st.xml
                c.cslStyles.remove(st.id)
                if (defaultKey == st.key) onDefault(CitationStyles.resolve(null).key)
                version++
                Notices.undo(tr("Zitierstil „{0}“ entfernt", st.title), restore = {
                    scope.launch { runCatching { c.cslStyles.add(xml) { id -> c.cslRepository.download(id) } }; version++ }
                })
            }) { Icon(Icons.Outlined.Delete, contentDescription = tr("Entfernen")) }
        }
    }
    if (searching) CslSearchDialog(c, onDismiss = { searching = false }) { searching = false; version++ }
}

/** Search in the official CSL collection (Zotero style repository) with a preview of each style before loading. */
@Composable
fun CslSearchDialog(c: AppContainer, onDismiss: () -> Unit, onLoaded: (CslStyle) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CslRepository.Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        busy = true
        runCatching { c.cslRepository.search(query) }
            .onSuccess { results = it; error = null }
            .onFailure { error = it.message ?: tr("Die Stil-Sammlung ist nicht erreichbar.") }
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Zitierstil aus der Sammlung")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(tr("Zeitschrift, Verlag, Fach oder Hochschule")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HintText(tr("Die offizielle Sammlung der Citation Style Language (über zotero.org/styles). Gesucht wird nur der Name des Stils."))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!busy && query.trim().length >= 2 && results.isEmpty() && error == null) HintText(tr("Kein Stil gefunden."))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.name }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = loading == null) {
                                loading = e.name
                                scope.launch {
                                    runCatching {
                                        val xml = c.cslRepository.download(e.name) ?: throw IllegalStateException(tr("Stil „{0}“ nicht gefunden", e.title))
                                        c.cslStyles.add(xml, e.name) { id -> c.cslRepository.download(id) }
                                    }.onSuccess { Notices.info(tr("Zitierstil „{0}“ geladen", it.title)); onLoaded(it) }
                                        .onFailure { error = it.message }
                                    loading = null
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                HintText(listOfNotNull(CslRepository.formatLabel(e.format), e.fields.take(2).joinToString(", ").ifBlank { null }).joinToString(" · "))
                            }
                            if (loading == e.name) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
    )
}
