package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.desktop.DesktopActions
import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.video.AudioSync
import io.github.thepytales.llmhub.video.Ducking
import io.github.thepytales.llmhub.video.Faces
import io.github.thepytales.llmhub.video.Highlights
import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.MultiCam
import io.github.thepytales.llmhub.video.Reframe
import io.github.thepytales.llmhub.video.SubtitleStyles
import io.github.thepytales.llmhub.video.TextCut
import io.github.thepytales.llmhub.video.VideoLora
import io.github.thepytales.llmhub.video.VideoProject
import io.github.thepytales.llmhub.video.VideoTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/*
 * Video: was im Alltag fehlt (AP-129) – the dialogs and settings for the gaps of the Lückenliste (docs/video-luecken.md):
 * Musik leiser beim Sprechen, Hochformat mit Bildausschnitt, der der Person folgt, Mehrkamera-Schnitt nach Sprecher,
 * Untertitel-Stile, Höhepunkte als Clip, Vorlagen für Titel und Abspann.
 */

/** Ducking in the sound settings of the project: on/off and how much quieter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DuckingSetting(h: History) {
    val p = h.project
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Musik leiser, wenn gesprochen wird"))
            HintText(tr("Musik und Ton auf den Tonspuren, die nicht mit einem Clip verknüpft sind, werden beim Sprechen leiser und danach wieder lauter."))
        }
        Switch(p.sound.duck, { v -> h.edit { it.copy(sound = it.sound.copy(duck = v)) } })
    }
    if (p.sound.duck) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Ducking.LEVELS.forEach { (db, label) ->
            FilterChip(selected = kotlin.math.abs(p.sound.duckDb - db) < 0.5f, onClick = { h.edit { it.copy(sound = it.sound.copy(duckDb = db)) } }, label = { Text(tr(label)) })
        }
    }
}

/** The look of the subtitles in the project settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleStyleSetting(h: History) {
    val p = h.project
    Text(tr("Aussehen"), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SubtitleStyles.STYLES.forEach { (k, label) ->
            FilterChip(selected = SubtitleStyles.of(p.subtitleStyle) == k, onClick = { h.edit { it.copy(subtitleStyle = k, burnSubtitles = if (k != SubtitleStyles.STANDARD) true else it.burnSubtitles) } }, label = { Text(tr(label)) })
        }
    }
    HintText(
        when (SubtitleStyles.of(p.subtitleStyle)) {
            SubtitleStyles.GROSS -> tr("Groß und mit Kontur in der unteren Bildmitte – gut lesbar auf dem Handy ohne Ton.")
            SubtitleStyles.WOERTER -> tr("Wenige Wörter auf einmal, das gesprochene Wort hervorgehoben – wie in kurzen Clips üblich.")
            else -> tr("Ruhig im Kasten am unteren Rand – für Vorlesungen und Lehrvideos.")
        },
    )
}

/** Hochformat oder Quadrat mit Bildausschnitt, der der Person folgt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReframeDialog(h: History, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf(Reframe.FORMATS.first()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val missing = remember { Faces.missing() }
    val reframed = h.project.clips.any { it.fx.aspect > 0.05f }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(tr("Bildausschnitt folgt der Person")) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HintText(tr("Macht aus Querformat-Aufnahmen ein Video im Hochformat oder Quadrat. Der Ausschnitt folgt dem Gesicht der Person im Bild – ruhig wie mit einer Kamerafrau, nicht bei jedem Nicken. Alles auf diesem PC."))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Reframe.FORMATS.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(tr(f.label)) }) }
                }
                if (missing != null) HintText(missing + " " + tr("Ohne Gesichtserkennung bleibt der Ausschnitt in der Mitte; verschieben lässt er sich unter Effekte › Zoom und Schwenk."))
                if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !running, onClick = {
                running = true
                scope.launch {
                    val p = h.project
                    val keys = runCatching { Reframe.scan(p, format, { Media.probe(it) }) { progress = it } }.getOrElse { Notices.error(it.message ?: tr("Die Gesichtserkennung ließ sich nicht starten.")); emptyMap() }
                    val info = withContext(Dispatchers.IO) { p.clips.map { it.path }.distinct().associateWith { Media.probe(File(it)) } }
                    h.edit { pp -> Reframe.apply(pp, format, keys) { c -> info[c.path]?.let { i -> if (i.height > 0) i.width.toDouble() / i.height else null } } }
                    running = false
                    Notices.info(tr("Jetzt {0}: {1} Clip(s) folgen der Person. Mit Strg+Z rückgängig.", format.id, keys.size))
                    onDismiss()
                }
            }) { if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text(tr("Umstellen")) }
        },
        dismissButton = {
            Row {
                if (reframed) TextButton(onClick = { h.edit { Reframe.undo(it) }; onDismiss() }, enabled = !running) { Text(tr("Zurück ins Querformat")) }
                TextButton(onClick = onDismiss, enabled = !running) { Text(tr("Abbrechen")) }
            }
        },
    )
}

/** Höhepunkte aus einer langen Aufnahme als neuer Clip (ein neues Projekt). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighlightsDialog(c: AppContainer, h: History, onDismiss: () -> Unit, load: suspend (VideoProject) -> Map<String, Transcript> = { projectTranscripts(c, it) }) {
    var seconds by remember { mutableStateOf(60) }
    var wish by remember { mutableStateOf("") }
    var format by remember { mutableStateOf<Reframe.Format?>(Reframe.FORMATS.first()) }
    var style by remember { mutableStateOf(SubtitleStyles.WOERTER) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hasTranscript = h.project.clips.any { it.path in h.project.transcripts }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(tr("Höhepunkte als Clip")) },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasTranscript) HintText(tr("Dafür braucht ein Clip ein Transkript: unter Untertitel › Aus der Transkription eines zuordnen."))
                HintText(tr("Lora liest das Transkript und wählt die stärksten Stellen. Daraus entsteht ein neues Projekt – dieses bleibt, wie es ist. Der Text geht an das Standardmodell, Namen pseudonymisiert."))
                Text(tr("Länge"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Highlights.LENGTHS.forEach { s -> FilterChip(selected = seconds == s, onClick = { seconds = s }, label = { Text(if (s < 60) "$s s" else if (s % 60 == 0) "${s / 60} min" else String.format(Locale.GERMANY, "%.1f min", s / 60.0)) }) }
                }
                OutlinedTextField(wish, { wish = it }, minLines = 1, modifier = Modifier.fillMaxWidth().typing(), label = { Text(tr("Worauf achten? (optional)")) }, placeholder = { Text(tr("z. B. „das Beispiel mit dem Experiment“")) })
                Text(tr("Format"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = format == null, onClick = { format = null }, label = { Text(tr("Wie das Projekt")) })
                    Reframe.FORMATS.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.id) }) }
                }
                Text(tr("Untertitel"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubtitleStyles.STYLES.forEach { (k, l) -> FilterChip(selected = style == k, onClick = { style = k }, label = { Text(tr(l)) }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = hasTranscript && !running, onClick = {
                running = true; error = null
                scope.launch {
                    val result = runCatching {
                        val p = h.project
                        val ts = load(p)
                        val chosen = Highlights.pick(c, Highlights.passages(p, ts), seconds, wish)
                        var q = Highlights.project(p, chosen, ts, format, style)
                        format?.let { f ->
                            val keys = runCatching { Reframe.scan(q, f, { Media.probe(it) }) }.getOrDefault(emptyMap())
                            val info = withContext(Dispatchers.IO) { q.clips.map { it.path }.distinct().associateWith { Media.probe(File(it)) } }
                            q = Reframe.apply(q, f, keys) { cl -> info[cl.path]?.let { i -> if (i.height > 0) i.width.toDouble() / i.height else null } }
                        }
                        withContext(Dispatchers.IO) { VideoHub.store.save(q) }
                    }
                    running = false
                    result.onSuccess { q ->
                        Notices.info(tr("„{0}“ angelegt ({1}).", q.title, clock(q.duration)))
                        onDismiss()
                        VideoLora.openRequest.value = q.id
                    }.onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            }) { if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text(tr("Clip erstellen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text(tr("Abbrechen")) } },
    )
}

/** Mehrkamera-Schnitt nach Sprecher: a second camera, synchronised over the sound, shown when its person speaks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MultiCamDialog(c: AppContainer, h: History, selected: String?, onDismiss: () -> Unit, load: suspend (VideoProject) -> Map<String, Transcript> = { projectTranscripts(c, it) }) {
    val p = h.project
    val main = p.clips.firstOrNull { it.id == selected } ?: p.clips.firstOrNull()
    var second by remember { mutableStateOf(p.media.map { it.path }.firstOrNull { it != main?.path && !Media.isStill(File(it)) } ?: p.layers.firstOrNull()?.path) }
    var offset by remember { mutableStateOf<Double?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    val cameraOf = remember { mutableStateMapOf<Int, Int>() }
    var byLoudness by remember { mutableStateOf(false) }
    var minShot by remember { mutableStateOf(2.0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(main?.path) {
        transcript = main?.let { m -> load(p)[m.path] }
        val t = transcript
        if (t != null && t.diarized) t.speakers.keys.sorted().forEachIndexed { i, s -> cameraOf.putIfAbsent(s, i % 2) } else byLoudness = true
    }
    LaunchedEffect(second) {
        offset = null
        val m = main ?: return@LaunchedEffect
        val s = second ?: return@LaunchedEffect
        syncing = true
        offset = withContext(Dispatchers.IO) { AudioSync.offsetOf(File(m.path), File(s)) }
        syncing = false
        if (offset == null) error = tr("Die beiden Aufnahmen ließen sich nicht über den Ton synchronisieren – haben beide Kameras Ton?") else error = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Mehrkamera nach Sprecher")) },
        text = {
            Column(Modifier.width(580.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (main == null) { HintText(tr("Zuerst die Aufnahme der Hauptkamera in die Zeitleiste legen.")); return@Column }
                main.let { MultiCam.problem(it) }?.let { HintText(it) }
                HintText(tr("Zwei Kameras filmen dasselbe Gespräch. Die App legt sie über den Ton übereinander und schneidet zu der Kamera, deren Person gerade spricht. Der Ton der Hauptkamera läuft durch."))
                Text(tr("Hauptkamera: {0}", main.name), style = MaterialTheme.typography.titleSmall)
                Text(tr("Zweite Kamera"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (p.media.map { it.path } + p.layers.map { it.path }).distinct().filter { it != main.path && !Media.isStill(File(it)) }.forEach { path ->
                        FilterChip(selected = second == path, onClick = { second = path }, label = { Text(File(path).name, maxLines = 1) })
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { DesktopActions.pickFiles(tr("Zweite Kamera auswählen"), multiple = false, extensions = VIDEO_MEDIA).firstOrNull() }?.let { second = it.absolutePath }
                        }
                    }) { Text(tr("Datei wählen …"), maxLines = 1) }
                }
                when {
                    syncing -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  " + tr("Synchronisiere über den Ton …")) }
                    offset != null -> HintText(tr("Synchron: die zweite Kamera liegt {0} s versetzt.", String.format(Locale.GERMANY, "%+.2f", offset)))
                }
                Text(tr("Wann welche Kamera?"), style = MaterialTheme.typography.labelLarge)
                val t = transcript
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !byLoudness, enabled = t?.diarized == true, onClick = { byLoudness = false }, label = { Text(tr("Nach Sprecher (Transkript)")) })
                    FilterChip(selected = byLoudness, onClick = { byLoudness = true }, label = { Text(tr("Nach Lautstärke der Mikrofone")) })
                }
                if (!byLoudness && t != null) t.speakers.keys.sorted().forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t.speakerName(s), modifier = Modifier.weight(1f))
                        FilterChip(selected = (cameraOf[s] ?: 0) == 0, onClick = { cameraOf[s] = 0 }, label = { Text(tr("Hauptkamera")) })
                        FilterChip(selected = cameraOf[s] == 1, onClick = { cameraOf[s] = 1 }, label = { Text(tr("Zweite Kamera")) })
                    }
                }
                if (byLoudness) HintText(tr("Jede Kamera steht bei der Person, die sie filmt: Ist ihr Mikrofon deutlich lauter, wird sie gezeigt."))
                if (t == null) HintText(tr("Ohne Transkript nur nach Lautstärke. Mit Sprechererkennung in der Transkription geht es nach Sprecher."))
                Text(tr("Kürzeste Einstellung"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.5, 2.0, 3.0, 5.0).forEach { s -> FilterChip(selected = minShot == s, onClick = { minShot = s }, label = { Text(String.format(Locale.GERMANY, "%.1f s", s)) }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = main != null && MultiCam.problem(main) == null && second != null && offset != null, onClick = {
                val m = main!!
                val off = offset!!
                scope.launch {
                    val shots = withContext(Dispatchers.Default) {
                        val t = transcript
                        if (!byLoudness && t != null) MultiCam.fromSpeakers(t, m.inSec, m.outSec, cameraOf.toMap(), minShot)
                        else {
                            val a = withContext(Dispatchers.IO) { AudioSync.envelope(File(m.path)) }
                            val b = withContext(Dispatchers.IO) { AudioSync.envelope(File(second!!)) }
                            if (a == null || b == null) null else MultiCam.fromLoudness(a, b, AudioSync.RATE, off, m.inSec, m.outSec, minShot = minShot)
                        }
                    }
                    if (shots == null) { error = tr("Der Ton der Aufnahmen ließ sich nicht lesen."); return@launch }
                    h.edit { MultiCam.apply(it, m.id, second!!, off, shots) }
                    Notices.info(tr("{0} Einstellungen, {1}× zur zweiten Kamera. Mit Strg+Z rückgängig.", shots.size, shots.count { it.camera == 1 }))
                    onDismiss()
                }
            }) { Text(tr("Schneiden")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** Vorlagen für Titel und Abspann. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoTemplateDialog(c: AppContainer, h: History, onDismiss: () -> Unit, load: suspend (VideoProject) -> Map<String, Transcript> = { projectTranscripts(c, it) }) {
    var template by remember { mutableStateOf(VideoTemplates.TEMPLATES.first()) }
    val values = remember { mutableStateMapOf<String, String>() }
    var speakers by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    LaunchedEffect(Unit) { speakers = runCatching { VideoTemplates.speakers(TextCut.build(h.project, load(h.project))) }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Vorlage für Titel und Abspann")) },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VideoTemplates.TEMPLATES.forEach { t -> FilterChip(selected = template == t, onClick = { template = t }, label = { Text(tr(t.label)) }) }
                }
                HintText(tr(template.hint) + " " + tr("Im TUD-Design; alles lässt sich danach in der Zeitleiste ändern."))
                template.fields.forEach { f ->
                    OutlinedTextField(values[f.key].orEmpty(), { values[f.key] = it }, label = { Text(tr(f.label)) }, placeholder = { Text(tr(f.example)) }, singleLine = f.key != "abspann", modifier = Modifier.fillMaxWidth().typing())
                }
                if (template.speakers) HintText(
                    if (speakers.isEmpty()) tr("Ohne Transkript mit Sprechererkennung gibt es keine Bauchbinden – sie lassen sich über Titel › Bauchbinde einzeln setzen.")
                    else tr("Bauchbinden für: {0}. Namen wie „Sprecher 2“ lassen sich danach ändern – oder vorher in der Transkription.", speakers.joinToString(", ") { it.first }),
                )
            }
        },
        confirmButton = {
            Button(enabled = template.fields.any { !values[it.key].isNullOrBlank() } || (template.speakers && speakers.isNotEmpty()), onClick = {
                h.edit { VideoTemplates.apply(it, template, values.toMap(), speakers) }
                Notices.info(tr("Vorlage „{0}“ eingesetzt. Mit Strg+Z rückgängig.", tr(template.label)))
                onDismiss()
            }) { Text(tr("Einsetzen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}
