package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.agent.ToolActions
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.video.Bleep
import io.github.thepytales.llmhub.video.Faces
import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.NameGuard
import io.github.thepytales.llmhub.video.VideoPlayer
import io.github.thepytales.llmhub.video.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The ways a name in the sound can be hidden (AP-128). */
internal val NAME_MODES = listOf(NameGuard.PIEP to trLater("Piepen"), NameGuard.STUMM to trLater("Stumm"), NameGuard.STIMME to trLater("Stimme verfremden"))

/**
 * Namen im Ton schützen (AP-128): names found in the transcripts of the clips (the Namensschutz plus a list of one's own,
 * e.g. the participants) as a list to check and tick – each place *Piepen · Stumm · Stimme verfremden*, *Anhören*,
 * *Entfernen*. Subtitles and the text of the video show „[Name]“. Everything happens on this device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NameGuardDialog(c: AppContainer, h: History, player: VideoPlayer, onDismiss: () -> Unit, load: suspend (VideoProject) -> Map<String, Transcript> = { projectTranscripts(c, it) }) {
    val p = h.project
    val scope = rememberCoroutineScope()
    var own by remember { mutableStateOf(p.nameList) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(p.bleeps.isNotEmpty()) }
    var noTranscript by remember { mutableStateOf(false) }

    fun search() {
        busy = true
        scope.launch {
            val transcripts = load(h.project)
            noTranscript = transcripts.isEmpty()
            val found = withContext(Dispatchers.Default) { NameGuard.find(h.project, transcripts, NameGuard.ownList(own)) }
            h.edit { it.copy(bleeps = found, nameList = own) }
            ToolActions.log(tr("Namen im Ton im Video „{0}“: {1} Stelle(n) gefunden.", h.project.title, found.size))
            busy = false
            searched = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Namen im Ton schützen")) },
        text = {
            Column(Modifier.width(600.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HintText(tr("Kana sucht Namen im Transkript der Clips – mit dem Namensschutz und deiner Liste. Jede Stelle wird im Export und in der Vorschau überpiept, stumm geschaltet oder die Stimme verfremdet; Untertitel und Text zeigen „[Name]“. Alles bleibt auf diesem Gerät."))
                OutlinedTextField(
                    own, { own = it },
                    label = { Text(tr("Eigene Namen, einer je Zeile (z. B. die Teilnehmerliste)")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = ::search) { Text(if (searched) tr("Neu suchen") else tr("Namen suchen")) }
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (noTranscript) HintText(tr("Kein Clip hat ein Transkript – erst in der Transkript-Leiste transkribieren oder zuordnen."))
                val list = h.project.bleeps
                if (searched && list.isEmpty() && !busy && !noTranscript) Text(tr("Keine Namen gefunden."))
                if (list.isNotEmpty()) {
                    HorizontalDivider()
                    Text(tr("{0} Stelle(n) · {1} geprüft", list.size, list.count { it.checked }), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NAME_MODES.forEach { (mode, label) ->
                            TextButton(onClick = { h.edit { it.copy(bleeps = it.bleeps.map { b -> b.copy(mode = mode) }) } }) { Text(tr("Alle: {0}", tr(label))) }
                        }
                        TextButton(onClick = { h.edit { it.copy(bleeps = it.bleeps.map { b -> b.copy(checked = true) }) } }) { Text(tr("Alle abhaken")) }
                    }
                    list.forEach { b -> BleepRow(h, player, b) }
                }
            }
        },
        confirmButton = { Button(onClick = { if (own != h.project.nameList) h.edit { it.copy(nameList = own) }; onDismiss() }) { Text(tr("Fertig")) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BleepRow(h: History, player: VideoPlayer, b: Bleep) {
    val p = h.project
    val at = NameGuard.timelineAt(p, b)
    fun change(f: (Bleep) -> Bleep) = h.edit { pr -> pr.copy(bleeps = pr.bleeps.map { if (it.id == b.id) f(it) else it }) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(b.checked, { v -> change { it.copy(checked = v) } })
            Column(Modifier.weight(1f)) {
                Text(b.name, style = MaterialTheme.typography.bodyLarge)
                HintText((at?.let { clock(it, true) } ?: tr("nicht im Schnitt")) + " · " + File(b.path).name)
            }
            TextButton(enabled = at != null, onClick = { at?.let { t -> player.seek(p, maxOf(0.0, t - 1.0)); player.play(p) } }) { Text(tr("Anhören")) }
            IconButton(onClick = { h.edit { pr -> pr.copy(bleeps = pr.bleeps.filter { it.id != b.id }) } }) { Icon(Icons.Outlined.Close, contentDescription = tr("Entfernen")) }
        }
        FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NAME_MODES.forEach { (mode, label) -> FilterChip(selected = b.mode == mode, onClick = { change { it.copy(mode = mode) } }, label = { Text(tr(label)) }) }
        }
    }
}

// ── Prüfliste vor dem Export ──────────────────────────────────────────────────────────────────────────────────

/** One point of the Prüfliste before the export (AP-128). */
internal data class PrivacyIssue(
    val kind: Kind,
    val text: String,
    /** Where to look (timeline), null when there is no one place. */
    val at: Double? = null,
    /** Clips with faces (for *Beheben*). */
    val clips: List<String> = emptyList(),
    /** Places of names to add (for *Beheben*). */
    val bleeps: List<Bleep> = emptyList(),
    val names: List<String> = emptyList(),
) {
    enum class Kind { FACES, FACES_UNCHECKED, NAMES_SOUND, NAMES_SUBTITLES }
}

/**
 * What the Prüfliste finds in [p]: faces in clips that are not pixelated (a few pictures of each clip through the face
 * detection of AP-54 – or a note that it is missing), names in the sound without a place, names in the subtitles.
 */
internal suspend fun privacyIssues(p: VideoProject, transcripts: Map<String, Transcript>, faces: Boolean = true): List<PrivacyIssue> = withContext(Dispatchers.Default) {
    val out = mutableListOf<PrivacyIssue>()
    val starts = p.starts()
    if (faces) {
        val open = p.clips.withIndex().filter { (_, c) -> c.fx.regions.isEmpty() && File(c.path).isFile && Media.probe(File(c.path))?.hasVideo == true }
        if (open.isNotEmpty()) {
            val d = runCatching { Faces.detector() }.getOrNull()
            if (d == null) out += PrivacyIssue(PrivacyIssue.Kind.FACES_UNCHECKED, Faces.missing() ?: tr("Die Gesichtserkennung fehlt – Gesichter nicht geprüft."))
            else {
                val withFaces = open.filter { (_, c) ->
                    listOf(0.15, 0.5, 0.85).any { f ->
                        val img = Media.frameAwt(File(c.path), c.inSec + (c.outSec - c.inSec) * f, 480)
                        img != null && runCatching { d.detect(img) }.getOrDefault(emptyList()).isNotEmpty()
                    }
                }
                if (withFaces.isNotEmpty()) out += PrivacyIssue(
                    PrivacyIssue.Kind.FACES, tr("Gesichter in {0} Clip(s), die nicht verpixelt sind", withFaces.size),
                    at = starts[withFaces.first().index], clips = withFaces.map { it.value.id },
                )
            }
        }
    }
    if (transcripts.isNotEmpty()) {
        val found = NameGuard.find(p, transcripts, NameGuard.ownList(p.nameList))
        val fresh = found.filter { b -> p.bleeps.none { it.path == b.path && it.fromSec < b.toSec && it.toSec > b.fromSec } }
        if (fresh.isNotEmpty()) out += PrivacyIssue(
            PrivacyIssue.Kind.NAMES_SOUND, tr("{0} Name(n) im Ton ohne Piepen: {1}", fresh.size, fresh.map { it.name }.distinct().take(5).joinToString(", ")),
            at = NameGuard.timelineAt(p, fresh.first()), bleeps = found,
        )
    }
    val own = NameGuard.ownList(p.nameList)
    val inSubs = p.subtitles.withIndex().filter { (_, s) -> NameGuard.names(s.text, own).any { n -> NameGuard.mask(s.text, listOf(n)) != s.text } }
    if (inSubs.isNotEmpty()) out += PrivacyIssue(
        PrivacyIssue.Kind.NAMES_SUBTITLES, tr("Namen in {0} Untertitel(n)", inSubs.size),
        at = inSubs.first().value.startSec, names = inSubs.flatMap { NameGuard.names(it.value.text, own) }.distinct(),
    )
    out
}

/**
 * Die Prüfliste vor dem Export (AP-128): each point with *Zeigen* and *Beheben*; [onOpen] tells the dialog how many
 * points are open (its button then reads *Trotzdem exportieren*). *Für dieses Projekt nicht mehr prüfen* switches it off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivacyCheck(
    c: AppContainer,
    p: VideoProject,
    onEdit: ((VideoProject) -> VideoProject) -> Unit,
    onShow: (Double) -> Unit,
    onNames: () -> Unit,
    onOpen: (List<PrivacyIssue>?) -> Unit,
) {
    var issues by remember { mutableStateOf<List<PrivacyIssue>?>(null) }
    var round by remember { mutableStateOf(0) }
    var fixing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(round, p.bleeps, p.subtitles, p.clips) {
        issues = null
        onOpen(null)
        val found = privacyIssues(p, projectTranscripts(c, p))
        issues = found
        onOpen(found)
    }
    Text(tr("Datenschutz vor dem Export"), style = MaterialTheme.typography.labelLarge)
    val list = issues
    when {
        list == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            HintText(tr("Prüfe Gesichter, Namen im Ton und in Untertiteln …"))
        }
        list.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(tr("Keine Gesichter und Namen gefunden, die noch offen sind."), style = MaterialTheme.typography.bodySmall)
        }
        else -> list.forEach { issue ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(issue.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                issue.at?.let { t -> TextButton(onClick = { onShow(t) }) { Text(tr("Zeigen")) } }
                if (issue.kind != PrivacyIssue.Kind.FACES_UNCHECKED) TextButton(enabled = !fixing, onClick = {
                    when (issue.kind) {
                        PrivacyIssue.Kind.NAMES_SOUND -> {
                            onEdit { it.copy(bleeps = issue.bleeps) }
                            ToolActions.log(tr("Namen im Ton im Video „{0}“: {1} Stelle(n) überpiept.", p.title, issue.bleeps.size))
                            onNames()
                        }
                        PrivacyIssue.Kind.NAMES_SUBTITLES -> onEdit { pr -> pr.copy(subtitles = pr.subtitles.map { s -> s.copy(text = NameGuard.mask(s.text, issue.names)) }) }
                        PrivacyIssue.Kind.FACES -> {
                            fixing = true
                            scope.launch {
                                var n = 0
                                for (id in issue.clips) {
                                    val clip = p.clips.firstOrNull { it.id == id } ?: continue
                                    runCatching { Faces.find(clip) }.onSuccess { found -> n += found.size; onEdit { Faces.apply(it, id, found) } }
                                }
                                ToolActions.log(tr("Anonymisierung im Video „{0}“: {1} Gesicht(er) vor dem Export verpixelt.", p.title, n))
                                fixing = false
                                round++
                            }
                        }
                        else -> {}
                    }
                    if (issue.kind != PrivacyIssue.Kind.FACES) round++
                }) { Text(tr("Beheben")) }
            }
        }
    }
    if (fixing) HintText(tr("Suche Gesichter und verpixle sie …"))
    TextButton(onClick = { onEdit { it.copy(privacyCheck = false) }; onOpen(emptyList()) }) { Text(tr("Für dieses Projekt nicht mehr prüfen")) }
}
