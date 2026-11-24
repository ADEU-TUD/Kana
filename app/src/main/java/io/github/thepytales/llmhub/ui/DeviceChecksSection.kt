package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.BuildConfig
import io.github.thepytales.llmhub.data.AppMap
import io.github.thepytales.llmhub.data.DeviceCheckStore
import io.github.thepytales.llmhub.data.DeviceChecks
import io.github.thepytales.llmhub.update.UpdatePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Seconds between *Bildschirmfoto* and the picture, to go to the place first. */
internal const val SHOT_DELAY_S = 5

/** Outlives the page: during the countdown one goes to the place, and the picture still lands at the step. */
private val shotScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Einstellungen › Prüfungen am Gerät (AP-133): the open checks of all packages, one step at a time – what to do, what
 * should happen, *Hingehen*, a note and a screenshot, then *Passt* or *Passt nicht*. Steps that passed disappear, at the
 * end the report goes out through *Problem melden*. [device] is this device unless another one is chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceChecksSection(c: AppContainer, onNavigate: (String) -> Unit, here: String = UpdatePlatform.KEY) {
    val store = c.deviceChecks
    val catalog = DeviceChecks.catalog
    val results by store.results.items.collectAsStateWithLifecycle()
    var device by rememberSaveable { mutableStateOf(here) }
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var askReset by remember { mutableStateOf(false) }

    val mine = catalog.steps.filter { it.on(device) }
    val open = store.open(device, results)
    val checked = mine.size - mine.count { store.state(it, results) == DeviceCheckStore.State.OPEN }
    val current = currentKey?.let { catalog.step(it) }?.takeIf { it.on(device) }

    Section(tr("Prüfungen am Gerät")) {
        Hint(
            tr("Was in den letzten Paketen neu ist, wird hier Schritt für Schritt am Gerät geprüft: lesen, ausprobieren, dann " +
                "*Passt* oder *Passt nicht* – mit Notiz und Bildschirmfoto. Am Ende geht ein Bericht über *Problem melden* hinaus.")
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DeviceChecks.DEVICES.forEach { (key, label) ->
                val n = catalog.steps.count { it.on(key) }
                if (n > 0 || key == here) FilterChip(
                    selected = device == key,
                    onClick = { device = key; currentKey = null },
                    label = { Text(if (key == here) tr("{0} (dieses Gerät)", label) else label) },
                )
            }
        }
        if (mine.isEmpty()) {
            Text(tr("Für dieses Gerät gibt es gerade nichts zu prüfen."))
        } else {
            Text(tr("{0} von {1} Schritten geprüft · {2} offen", checked, mine.size, open.size), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { checked.toFloat() / mine.size }, modifier = Modifier.fillMaxWidth())
            if (current == null) {
                val next = open.firstOrNull { store.state(it, results) == DeviceCheckStore.State.OPEN } ?: open.firstOrNull()
                if (next != null) Button(onClick = { currentKey = next.key }) {
                    Text(if (checked == 0) tr("Prüfung beginnen") else tr("Weiter prüfen"))
                }
            }
        }
    }

    if (current != null) {
        StepCard(c, current, device, here, onNavigate, onDone = {
            // The next step after this one that is still to do (or the first one again).
            val order = store.open(device)
            val after = order.dropWhile { it.key != current.key }.drop(1)
            currentKey = (after.firstOrNull { store.state(it) == DeviceCheckStore.State.OPEN }
                ?: order.firstOrNull { it.key != current.key && store.state(it) == DeviceCheckStore.State.OPEN })?.key
        }, onClose = { currentKey = null })
    }

    // Open packages with their steps; a click on a step opens it.
    store.openPackages(device, results).forEach { p ->
        Section("${p.id} · ${p.title}") {
            if (p.before.isNotBlank()) Hint(tr("Vorher: {0}", p.before))
            p.stepsOn(device).forEach { s -> StepRow(s, store.state(s, results), selected = s.key == current?.key) { currentKey = s.key } }
        }
    }

    val done = store.donePackages(device, results)
    if (done.isNotEmpty()) Section(tr("Erledigt")) {
        Hint(tr("Alle Schritte passen – diese Pakete sind auf diesem Gerät geprüft."))
        done.forEach { p -> Text("${p.id} · ${p.title}", style = MaterialTheme.typography.bodyMedium) }
    }

    if (checked > 0) ReportCard(c, device, onNavigate, onReset = { askReset = true })

    if (askReset) ConfirmDialog(
        title = tr("Alle Prüfungen zurücksetzen?"),
        text = tr("Ergebnisse, Notizen und Bildschirmfotos dieses Geräts werden gelöscht; alle Schritte sind wieder offen."),
        confirm = tr("Zurücksetzen"),
        onConfirm = { store.reset(); currentKey = null },
        onDismiss = { askReset = false },
    )
}

@Composable
private fun StepRow(step: DeviceChecks.Step, state: DeviceCheckStore.State, selected: Boolean, onClick: () -> Unit) {
    val s = MaterialTheme.colorScheme
    val (icon, tint, said) = when (state) {
        DeviceCheckStore.State.PASSED -> Triple(Icons.Outlined.CheckCircle, s.primary, tr("passt"))
        DeviceCheckStore.State.FAILED -> Triple(Icons.Outlined.Cancel, s.error, tr("passt nicht"))
        DeviceCheckStore.State.OPEN -> Triple(Icons.Outlined.RadioButtonUnchecked, s.onSurfaceVariant, tr("offen"))
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp).semantics { contentDescription = "${step.title}, $said" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            step.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) s.primary else s.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (state == DeviceCheckStore.State.FAILED) Text(tr("passt nicht"), style = MaterialTheme.typography.labelMedium, color = s.error)
    }
}

/** One step: what to do, what should happen, *Hingehen*, note, screenshot, *Passt* / *Passt nicht*. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    c: AppContainer,
    step: DeviceChecks.Step,
    device: String,
    here: String,
    onNavigate: (String) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val store = c.deviceChecks
    val pkg = DeviceChecks.catalog.packages.first { it.id == step.pkg }
    val steps = pkg.stepsOn(device)
    val old = store.result(step)
    var note by rememberSaveable(step.key) { mutableStateOf(old?.note.orEmpty()) }
    var imageVersion by remember(step.key) { mutableIntStateOf(0) }
    var countdown by remember(step.key) { mutableIntStateOf(0) }
    val capture = rememberScreenCapture().takeIf { device == here }
    val pick = rememberImageBytesPicker { bytes -> if (bytes != null) { store.attach(step, bytes); imageVersion++ } }
    val place = step.place?.let { AppMap.byId(it) }

    Section(tr("{0} · Schritt {1} von {2}", pkg.id, steps.indexOf(step) + 1, steps.size)) {
        Text(step.title, style = MaterialTheme.typography.titleLarge)
        if (pkg.before.isNotBlank() && steps.firstOrNull() == step) Hint(tr("Vorher: {0}", pkg.before))
        Labeled(tr("So geht's"), step.todo)
        Labeled(tr("Das soll passieren"), step.expected)
        if (old != null) Hint(
            if (old.ok) tr("Zuletzt: passt (Kana {0})", old.version) else tr("Zuletzt: passt nicht (Kana {0})", old.version)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (place != null && device == here) OutlinedButton(onClick = { openTarget(place, onNavigate) }) { Text(tr("Hingehen")) }
            if (capture != null) OutlinedButton(
                enabled = countdown == 0,
                onClick = {
                    // Runs on in the app's scope, so one can go to the place meanwhile.
                    countdown = SHOT_DELAY_S
                    Notices.info(tr("Bildschirmfoto in {0} Sekunden – jetzt zur Stelle gehen.", SHOT_DELAY_S))
                    shotScope.launch {
                        repeat(SHOT_DELAY_S) { delay(1000); countdown-- }
                        val png = capture()
                        if (png != null) { store.attach(step, png); imageVersion++; Notices.info(tr("Bildschirmfoto ist beim Schritt „{0}“.", step.title)) }
                        else Notices.error(tr("Das Bildschirmfoto ist nicht gelungen."))
                    }
                },
            ) { Text(if (countdown > 0) tr("Foto in {0} s …", countdown) else tr("Bildschirmfoto")) }
            OutlinedButton(onClick = pick) { Text(tr("Bild anhängen …")) }
        }
        val image = remember(step.key, imageVersion) { store.imageOf(step) }
        if (image != null) {
            val bitmap = remember(image, imageVersion) { runCatching { decodeImage(image.readBytes()) }.getOrNull() }
            if (bitmap != null) Image(bitmap, contentDescription = tr("Bildschirmfoto"), modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 200.dp))
            Hint(tr("Bildschirmfoto angehängt: {0}", image.name))
        }
        OutlinedTextField(
            note, { note = it },
            label = { Text(tr("Notiz (was war anders?)")) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { store.record(step, true, note, BuildConfig.VERSION_NAME, device); onDone() }) { Text(tr("Passt")) }
            OutlinedButton(onClick = { store.record(step, false, note, BuildConfig.VERSION_NAME, device); onDone() }) { Text(tr("Passt nicht")) }
            TextButton(onClick = onDone) { Text(tr("Überspringen")) }
            if (old != null) TextButton(onClick = { store.forget(step); imageVersion++; note = "" }) { Text(tr("Wieder offen")) }
            TextButton(onClick = onClose) { Text(tr("Schließen")) }
        }
    }
    LaunchedEffect(step.key) { countdown = 0 }
}

@Composable
private fun Labeled(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(starsBold(text), style = MaterialTheme.typography.bodyLarge)
    }
}

/** `*Name*` in the list and the hints marks a name in the app: bold, without the stars. */
internal fun starsBold(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val m = Regex("\\*([^*]+)\\*").find(rest) ?: break
        append(rest.substring(0, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
        rest = rest.substring(m.range.last + 1)
    }
    append(rest)
}

@Composable
private fun Hint(text: String) {
    Text(starsBold(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The report: what did not pass first; sent through *Problem melden*, pictures as a ZIP. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(c: AppContainer, device: String, onNavigate: (String) -> Unit, onReset: () -> Unit) {
    val store = c.deviceChecks
    val results by store.results.items.collectAsStateWithLifecycle()
    val system = System.getProperty("os.name").orEmpty()
    val report = remember(results, device) { store.report(device, BuildConfig.VERSION_NAME, system) }
    val hasImages = results.any { it.image.isNotBlank() }
    val saveZip = rememberFileExport("application/zip", onDone = { ok -> if (!ok) Notices.error(tr("Die ZIP-Datei konnte nicht gespeichert werden.")) }) { out ->
        store.writeZip(out, report)
    }
    Section(tr("Bericht")) {
        Text(report.lineSequence().take(2).joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        Hint(
            if (hasImages) tr("*Problem melden* übernimmt den Bericht als Beschreibung. Die Bildschirmfotos kann eine Mail nicht von " +
                "selbst mitnehmen – als ZIP speichern und anhängen.")
            else tr("*Problem melden* übernimmt den Bericht als Beschreibung; dort per E-Mail oder als GitHub-Issue senden.")
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { ProblemDraft.text = report; onNavigate("settings/help") }) { Text(tr("Bericht über Problem melden")) }
            if (hasImages) OutlinedButton(onClick = { saveZip("pruefung-lora-${BuildConfig.VERSION_NAME}.zip") }) { Text(tr("Bericht mit Bildern als ZIP …")) }
            TextButton(onClick = onReset) { Text(tr("Alles zurücksetzen …")) }
        }
    }
}
