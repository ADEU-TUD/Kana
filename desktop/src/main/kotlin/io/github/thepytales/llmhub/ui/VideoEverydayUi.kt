package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.video.AudioClip
import io.github.thepytales.llmhub.video.CaptionStyles
import io.github.thepytales.llmhub.video.Faces
import io.github.thepytales.llmhub.video.Media
import io.github.thepytales.llmhub.video.Reframe
import io.github.thepytales.llmhub.video.SoundFix
import io.github.thepytales.llmhub.video.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Was im Alltag fehlt (AP-129): the parts of the properties for ducking, sound on one side only and even voices
 * (Stufe b). The functions are in `video/SoundFix.kt` and in the graph of `video/VideoRender.kt`.
 */

/** Ducking of a sound on A1 … (AP-129 b): a switch with one line of what it does. */
@Composable
internal fun DuckSwitch(a: AudioClip, h: History) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Leiser, wenn gesprochen wird"))
            HintText(tr("Für Musik: wird automatisch leiser, sobald im Video jemand spricht, und danach wieder lauter. Für einen Sprecherton ausschalten."))
        }
        Switch(a.duck, { v -> h.edit { pp -> SoundFix.duck(pp, v, setOf(a.id)) } })
    }
}

private val SIDES = listOf("" to trLater("Wie aufgenommen"), "links" to trLater("Links auf beide Seiten"), "rechts" to trLater("Rechts auf beide Seiten"))

/**
 * Sound on one side only (AP-129 b): the choice, and a hint with a button when the file sounds like it (a lapel
 * microphone in one input of the camera) – found in the background on the first 60 s.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundSides(path: String, channel: String, onChange: (String) -> Unit) {
    val found by produceState("", path) { value = withContext(Dispatchers.IO) { runCatching { SoundFix.oneSided(File(path)) }.getOrDefault("") } }
    if (found.isNotEmpty() && channel.isEmpty()) {
        HintText(if (found == "links") tr("Der Ton ist nur links aufgenommen – im Kopfhörer klingt das einseitig.") else tr("Der Ton ist nur rechts aufgenommen – im Kopfhörer klingt das einseitig."))
        OutlinedButton(onClick = { onChange(found) }) { Text(tr("Auf beide Seiten legen"), maxLines = 1) }
    }
    if (found.isNotEmpty() || channel.isNotEmpty()) {
        Text(tr("Seiten"), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SIDES.forEach { (k, l) -> FilterChip(selected = channel == k, onClick = { onChange(k) }, label = { Text(tr(l), maxLines = 1) }) }
        }
    }
}

/** For a clip of V1: its own sound and the sound laid out from it go together. */
@Composable
internal fun ClipSoundSides(clip: VideoClip, h: History) = SoundSides(clip.path, clip.channel) { v -> h.edit { pp -> SoundFix.setClip(pp, clip.id, v) } }

/** For a sound on A1 …. */
@Composable
internal fun AudioSoundSides(a: AudioClip, h: History) = SoundSides(a.path, a.channel) { v -> h.edit { pp -> pp.copy(audio = pp.audio.map { if (it.id == a.id) it.copy(channel = v) else it }) } }

/** „Stimmen angleichen“ of the whole film (AP-129 b). */
@Composable
internal fun LevelVoicesSwitch(h: History) {
    val p = h.project
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr("Leise und laute Stimmen angleichen"))
            HintText(tr("Fragen aus dem Saal, wer weiter weg vom Mikrofon spricht: leise Stellen werden lauter, laute leiser."))
        }
        Switch(p.sound.level, { v -> h.edit { it.copy(sound = it.sound.copy(level = v)) } })
    }
}

/**
 * Format wechseln (AP-129 c), under the format chips of the project: clips with black bars in the frame fill it with
 * one click – in the middle, or following the person (faces found on this computer). Filled clips go back with bars.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatFill(h: History) {
    val p = h.project
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val barred by produceState(emptyList<VideoClip>(), p.clips, p.width, p.height) {
        value = withContext(Dispatchers.IO) { runCatching { Reframe.barred(p, { Media.probe(it) }) }.getOrDefault(emptyList()) }
    }
    val filled = p.clips.filter { it.fx.fill }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    if (barred.isNotEmpty()) HintText(tr("{0} Clip(s) haben ein anderes Format und stehen mit schwarzen Balken im Bild.", barred.size))
    if (barred.isEmpty() && filled.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (barred.isNotEmpty()) Tip(tr("Die Clips füllen das Bild; was übersteht, wird abgeschnitten (Mitte)")) {
            OutlinedButton(onClick = { h.edit { pp -> pp.copy(clips = pp.clips.map { c -> if (barred.any { it.id == c.id }) c.copy(fx = c.fx.copy(fill = true)) else c }) } }, enabled = busy == null) {
                Text(tr("Bild füllen"), maxLines = 1)
            }
        }
        val targets = (barred + filled).distinctBy { it.id }
        Tip(tr("Der Ausschnitt folgt der Person im Bild – die Gesichter werden auf diesem Computer gesucht")) {
            OutlinedButton(enabled = busy == null && targets.isNotEmpty(), onClick = {
                Faces.missing()?.let { Notices.error(it); return@OutlinedButton }
                busy = 0f
                scope.launch {
                    var found = 0
                    val paths = mutableMapOf<String, List<io.github.thepytales.llmhub.video.Key>>()
                    targets.forEachIndexed { k, c ->
                        val keys = runCatching { Reframe.find(c) { f -> busy = (k + f) / targets.size } }.getOrElse { e -> Notices.error(e.message ?: tr("Die Gesichtserkennung ist fehlgeschlagen.")); busy = null; return@launch }
                        if (keys.size > 1 || keys.any { abs(it.x - 0.5f) > 0.02f }) found++
                        paths[c.id] = keys
                    }
                    h.edit { pp -> paths.entries.fold(pp) { q, (id, keys) -> Reframe.apply(q, id, keys) } }
                    busy = null
                    Notices.info(if (found == 0) tr("Keine Person gefunden – der Ausschnitt bleibt in der Mitte.") else tr("Der Ausschnitt folgt jetzt der Person in {0} Clip(s). Bitte in der Vorschau prüfen.", found))
                }
            }) {
                if (busy != null) androidx.compose.material3.CircularProgressIndicator(progress = { busy ?: 0f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(tr("Der Person folgen"), maxLines = 1)
            }
        }
        if (filled.isNotEmpty()) TextButton(onClick = { h.edit { Reframe.unfill(it) } }, enabled = busy == null) { Text(tr("Wieder mit Balken"), maxLines = 1) }
    }
}

/** Look of burned-in subtitles (AP-129 c): the TUD subtitles (standard), „Groß“ or „Wort für Wort“ for short clips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleStyleChips(h: History) {
    val p = h.project
    Text(tr("Aussehen"), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CaptionStyles.STYLES.forEach { (k, l) ->
            FilterChip(selected = p.subtitleStyle == k || (k == CaptionStyles.TUD && CaptionStyles.STYLES.none { it.first == p.subtitleStyle }), onClick = { h.edit { it.copy(subtitleStyle = k) } }, label = { Text(tr(l), maxLines = 1) })
        }
    }
    HintText(when (p.subtitleStyle) {
        CaptionStyles.BIG -> tr("Große, fette Schrift in der unteren Bildmitte – gut lesbar auf dem Handy, auch ohne Ton.")
        CaptionStyles.WORDS -> tr("Wenige Wörter auf einmal, das gesprochene Wort gelb – wie in kurzen Clips üblich.")
        else -> tr("Weiß auf dunklem Kasten unten in der Mitte, im TUD-Design.")
    })
}

/**
 * „Kurzclip aus Höhepunkten“ (AP-129 c): Kana picks the strongest passages of the transcript for a clip of 30–90 s; a
 * new project is made from them (the original stays) and opened – in the format of a short clip, following the person.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighlightsDialog(c: io.github.thepytales.llmhub.AppContainer, h: History, onDismiss: () -> Unit) {
    var wish by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var seconds by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(60) }
    var format by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("hoch") }
    var follow by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var running by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val p = h.project
    val hasTranscript = p.clips.any { it.path in p.transcripts }
    AlertDialog(
        onDismissRequest = { if (running == null) onDismiss() },
        title = { Text(tr("Kurzclip aus Höhepunkten")) },
        text = {
            Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("Kana liest das Transkript und wählt die stärksten Stellen. Daraus entsteht ein neues Projekt – „{0}“ bleibt, wie es ist. Der Text geht an das Standardmodell, Namen pseudonymisiert.", p.title))
                if (!hasTranscript) HintText(tr("Ohne zugeordnetes Transkript sucht Kana eins mit dem Namen der Aufnahme – sonst zuerst unter Untertitel › Aus der Transkription zuordnen."))
                OutlinedTextField(wish, { wish = it }, minLines = 2, modifier = Modifier.fillMaxWidth().typing(), label = { Text(tr("Worum soll es gehen? (leer: Kana wählt)")) }, placeholder = { Text(tr("z. B. „Die Kernaussage zum Klimamodell“")) })
                Text(tr("Länge"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 90).forEach { s -> FilterChip(selected = seconds == s, onClick = { seconds = s }, label = { Text(tr("{0} s", s)) }) }
                }
                Text(tr("Format"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (Reframe.FORMATS.filter { it.id != "quer" }.map { it.id to tr(it.label) } + ("" to tr("Wie das Projekt"))).forEach { (k, l) ->
                        FilterChip(selected = format == k, onClick = { format = k }, label = { Text(l, maxLines = 1) })
                    }
                }
                if (format.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(follow, { follow = it })
                    Text(tr("Ausschnitt folgt der Person (Gesichter auf diesem Computer suchen)"), style = MaterialTheme.typography.bodySmall)
                }
                running?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = running == null && p.clips.isNotEmpty(), onClick = {
                error = null
                running = tr("Kana liest das Transkript …")
                scope.launch {
                    val result = runCatching {
                        val ts = io.github.thepytales.llmhub.video.VideoLora.transcripts(c, p)
                        val passages = io.github.thepytales.llmhub.video.Highlights.passages(p, ts)
                        if (passages.isEmpty()) error(tr("Für die Clips gibt es kein Transkript – zuerst unter Untertitel › Aus der Transkription zuordnen."))
                        val chosen = io.github.thepytales.llmhub.video.Highlights.choose(c, passages, wish, seconds)
                        running = tr("Kurzclip wird angelegt …")
                        var q = withContext(Dispatchers.IO) { io.github.thepytales.llmhub.video.Highlights.build(p, chosen, ts, { Media.probe(it) }, Reframe.format(format)) }
                        if (follow && format.isNotEmpty() && Faces.missing() == null) {
                            running = tr("Gesichter werden gesucht …")
                            for (clip in q.clips.filter { it.fx.fill }) q = Reframe.apply(q, clip.id, Reframe.find(clip))
                        }
                        withContext(Dispatchers.IO) { VideoHub.store.save(q) }
                    }
                    running = null
                    result.onSuccess { q ->
                        io.github.thepytales.llmhub.video.VideoLora.lastId = q.id
                        io.github.thepytales.llmhub.video.VideoLora.openRequest.value = q.id
                        Notices.info(tr("Kurzclip „{0}“ angelegt ({1}) – das Original bleibt unverändert.", q.title, clock(q.duration)))
                        onDismiss()
                    }.onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            }) {
                if (running != null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(tr("Kurzclip erstellen"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = running == null) { Text(tr("Abbrechen")) } },
    )
}

/**
 * „Interview: nach Sprecher schneiden“ (AP-129 d): the main camera (a clip of V1) and the other cameras (from the
 * media pool or a file), one of them on request the wide shot; the cameras are synchronised over their sound and the
 * clip is cut by who speaks. Strg+Z takes it back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MultiCamDialog(h: History, onDismiss: () -> Unit) {
    val p = h.project
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val usable = p.clips.filter { !it.fx.freeze && !it.fx.reverse && abs(it.speed - 1.0) < 1e-6 && !Media.isStill(File(it.path)) }
    var mainId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(usable.maxByOrNull { it.length }?.id) }
    val main = usable.firstOrNull { it.id == mainId }
    var extra by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(listOf<String>()) }
    val candidates = (p.media.map { it.path } + p.layers.map { it.path } + extra).distinct()
        .filter { it != main?.path && it.substringAfterLast('.').lowercase() in VIDEO_MEDIA && !Media.isStill(File(it)) }
    var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    var wide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    var running by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(tr("Interview: nach Sprecher schneiden")) },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(tr("Für Gespräche mit zwei oder drei Kameras: Wer spricht, ist im Bild. Die Kameras werden über den Ton synchronisiert; der Ton kommt durchgehend von der Hauptkamera (und ihrem Ansteckmikrofon). Danach ist jede Einstellung ein normaler Clip – zum Nachbessern."))
                if (main == null) { HintText(tr("Zuerst die Aufnahme der Hauptkamera als Clip in die Zeitleiste legen.")); return@Column }
                Text(tr("Hauptkamera"), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { menu = true }, enabled = usable.size > 1) { Text(tr("Clip {0}: {1}", p.clips.indexOf(main) + 1, File(main.path).name), maxLines = 1) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        usable.forEach { c -> androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Clip {0}: {1}", p.clips.indexOf(c) + 1, File(c.path).name)) }, onClick = { mainId = c.id; menu = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(main.path in wide, { v -> wide = if (v) wide + main.path else wide - main.path })
                    Text(tr("Die Hauptkamera zeigt beide (Totale)"), style = MaterialTheme.typography.bodySmall)
                }
                Text(tr("Weitere Kameras"), style = MaterialTheme.typography.labelLarge)
                if (candidates.isEmpty()) HintText(tr("Noch keine weitere Aufnahme im Projekt – über „Kamera hinzufügen …“ wählen."))
                candidates.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(path in chosen, { v -> chosen = if (v) chosen + path else chosen - path })
                        Text(File(path).name, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        if (path in chosen) FilterChip(selected = path in wide, onClick = { wide = if (path in wide) wide - path else wide + path }, label = { Text(tr("Totale"), maxLines = 1) })
                    }
                }
                TextButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { io.github.thepytales.llmhub.desktop.DesktopActions.pickFiles(tr("Weitere Kamera wählen"), multiple = true, extensions = VIDEO_MEDIA) }
                        extra = extra + f.map { it.absolutePath }
                        chosen = chosen + f.map { it.absolutePath }
                    }
                }) { Text(tr("Kamera hinzufügen …"), maxLines = 1) }
                if (chosen.size + 1 > 1 && (chosen + main.path).all { it in wide }) HintText(tr("Mindestens eine Kamera muss auf eine Person gerichtet sein."))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !running && main != null && chosen.isNotEmpty(), onClick = {
                val m = main ?: return@Button
                running = true; error = null
                scope.launch {
                    val result = runCatching { io.github.thepytales.llmhub.video.MultiCam.run(h.project, m.id, chosen.map { File(it) }, wide) }
                    running = false
                    result.onSuccess { (q, text) ->
                        h.edit { q }
                        Notices.info(tr("Nach Sprecher geschnitten: {0}. Mit Strg+Z rückgängig.", text))
                        onDismiss()
                    }.onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            }) {
                if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(tr("Synchronisieren und schneiden"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text(tr("Abbrechen")) } },
    )
}


/**
 * Properties of a Hervorhebung (AP-129 e): kind, colour, where and how large (shares of the picture, quick places in
 * a 3 × 3 grid), the short label and its time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalloutProperties(h: History, o: io.github.thepytales.llmhub.video.TextOverlay, pos: Double, onDelete: () -> Unit) {
    fun change(key: String?, f: (io.github.thepytales.llmhub.video.TextOverlay) -> io.github.thepytales.llmhub.video.TextOverlay) =
        h.edit(key) { pp -> pp.copy(overlays = pp.overlays.map { if (it.id == o.id) f(it) else it }) }
    PropTitle(tr("Hervorhebung: {0}", tr(io.github.thepytales.llmhub.video.Callouts.label(o.style) ?: o.style))) {
        TipIconButton(Icons.Outlined.Delete, tr("Löschen") + " (Entf)", onDelete, size = 32.dp)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        io.github.thepytales.llmhub.video.Callouts.STYLES.forEach { (k, l) -> FilterChip(selected = o.style == k, onClick = { change(null) { it.copy(style = k) } }, label = { Text(tr(l), maxLines = 1) }) }
    }
    Text(tr("Farbe"), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        io.github.thepytales.llmhub.video.Callouts.COLORS.forEach { (hex, l) ->
            FilterChip(selected = io.github.thepytales.llmhub.video.Callouts.colorOf(o).equals(hex, true), onClick = { change(null) { it.copy(color = hex) } }, label = { Text(tr(l), maxLines = 1) })
        }
    }
    OutlinedTextField(o.text, { t -> change("ct-${o.id}") { it.copy(text = t) } }, label = { Text(tr("Beschriftung (optional)")) }, placeholder = { Text(tr("z. B. „Hier klicken“")) }, modifier = Modifier.fillMaxWidth().typing(), singleLine = true)
    PropSection("callout.place", tr("Lage und Größe"), summary = "${Math.round(o.x * 100)} % · ${Math.round(o.y * 100)} %") {
        Text(tr("Schnell an eine Stelle"), style = MaterialTheme.typography.labelMedium)
        // A 3 × 3 grid: top left … bottom right.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(0.25f, 0.5f, 0.75f).forEach { y ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(0.22f, 0.5f, 0.78f).forEach { x ->
                        val on = abs(o.x - x) < 0.02f && abs(o.y - y) < 0.02f
                        FilterChip(selected = on, onClick = { change(null) { it.copy(x = x, y = y) } }, label = { Text("●", style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        }
        @Composable
        fun share(label: String, value: Float, key: String, range: ClosedFloatingPointRange<Float>, set: (io.github.thepytales.llmhub.video.TextOverlay, Float) -> io.github.thepytales.llmhub.video.TextOverlay) {
            Text("$label ${Math.round(value * 100)} %", style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.Slider(value = value, onValueChange = { v -> change(key) { set(it, v) } }, valueRange = range)
        }
        if (o.style == io.github.thepytales.llmhub.video.Callouts.ARROW) {
            share(tr("Spitze waagerecht"), o.x, "cx-${o.id}", 0f..1f) { it, v -> it.copy(x = v) }
            share(tr("Spitze senkrecht"), o.y, "cy-${o.id}", 0f..1f) { it, v -> it.copy(y = v) }
            share(tr("Länge nach links"), o.w, "cw-${o.id}", -0.5f..0.5f) { it, v -> it.copy(w = v) }
            share(tr("Länge nach unten"), o.h, "ch-${o.id}", -0.5f..0.5f) { it, v -> it.copy(h = v) }
        } else {
            share(tr("Mitte waagerecht"), o.x, "cx-${o.id}", 0f..1f) { it, v -> it.copy(x = v) }
            share(tr("Mitte senkrecht"), o.y, "cy-${o.id}", 0f..1f) { it, v -> it.copy(y = v) }
            share(tr("Breite"), o.w, "cw-${o.id}", 0.03f..1f) { it, v -> it.copy(w = v) }
            share(tr("Höhe"), o.h, "ch-${o.id}", 0.03f..1f) { it, v -> it.copy(h = v) }
        }
    }
    PropSection("callout.time", tr("Zeit"), summary = clock(o.durationSec, true)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondsField(tr("Beginn (s)"), o.startSec, "cst-${o.id}", Modifier.weight(1f)) { v -> change("cst-${o.id}") { it.copy(startSec = v.coerceAtLeast(0.0)) } }
            SecondsField(tr("Dauer (s)"), o.durationSec, "cd-${o.id}", Modifier.weight(1f)) { v -> change("cd-${o.id}") { it.copy(durationSec = v.coerceAtLeast(0.5)) } }
        }
        OutlinedButton(onClick = { change(null) { it.copy(startSec = pos) } }) { Text(tr("An den Abspielkopf"), maxLines = 1) }
    }
}
