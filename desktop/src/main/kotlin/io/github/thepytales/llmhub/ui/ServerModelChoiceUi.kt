package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.AppTool
import io.github.thepytales.llmhub.data.DataPlace
import io.github.thepytales.llmhub.ssh.ServerModelChoice

/**
 * „Wo soll Lora im Server-Terminal denken?“ (AP-131 e): asked once on the first use of the terminal (and on request from
 * Einstellungen › Lora): the model on this PC or the Uni-Server, with the recommendation for this PC, what speaks for
 * each way and what to keep in mind. [onCancel] null: the question must be answered (first use).
 */
@Composable
internal fun ServerModelDialog(advice: ServerModelChoice.Advice, current: ServerModelChoice.Choice?, onChosen: (ServerModelChoice.Choice) -> Unit, onCancel: (() -> Unit)? = null) {
    var chosen by remember { mutableStateOf(current ?: advice.recommended) }
    AlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        icon = { Icon(Icons.Outlined.Lightbulb, null) },
        title = { Text(tr("Wo soll Lora im Server-Terminal denken?")) },
        text = {
            Column(Modifier.widthIn(min = 520.dp, max = 680.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Lora schlägt im Terminal Befehle vor und liest dafür, was das Terminal zeigt. Das kann auf diesem PC passieren oder auf dem Uni-Server der TU Dresden."), style = MaterialTheme.typography.bodyMedium)
                advice.recommended?.let { r ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                tr("Empfehlung für diesen PC: {0}", if (r == ServerModelChoice.Choice.LOCAL) tr("Modell auf diesem PC") else tr("TU-Server")),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(advice.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                } ?: Text(advice.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                listOf(advice.tu, advice.local).sortedByDescending { it.choice == advice.recommended }.forEach { o ->
                    OptionCard(o, recommended = o.choice == advice.recommended, selected = chosen == o.choice, onSelect = { if (o.possible) chosen = o.choice })
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(advice.both + " " + tr("Ändern lässt es sich jederzeit unter Einstellungen › Lora › Server-Terminal."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            val c = chosen
            Button(enabled = c != null && (if (c == ServerModelChoice.Choice.LOCAL) advice.local.possible else advice.tu.possible), onClick = { c?.let(onChosen) }) { Text(tr("Übernehmen")) }
        },
        dismissButton = onCancel?.let { cancel -> { TextButton(onClick = cancel) { Text(tr("Abbrechen")) } } },
    )
}

@Composable
private fun OptionCard(o: ServerModelChoice.Option, recommended: Boolean, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect, enabled = o.possible,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect, enabled = o.possible)
                Text(o.summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = if (o.possible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                DataPlaceChip(if (o.choice == ServerModelChoice.Choice.LOCAL) DataPlace.LOCAL else DataPlace.TU)
                if (recommended) {
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Empfohlen"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                o.good.forEach { Line(it, good = true) }
                if (o.risks.isNotEmpty()) Text(tr("Bedenke:"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
                o.risks.forEach { Line(it, good = false) }
            }
        }
    }
}

@Composable
private fun Line(text: String, good: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        if (good) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        else Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (good) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Einstellungen › Lora › Server-Terminal (AP-131 e): the same choice, shown when the tool is switched on. */
@Composable
internal fun ServerTerminalLoraSettings(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    if (AppTool.SERVER.id !in s.enabledToolIds) return
    var ask by remember { mutableStateOf(false) }
    val advice = remember(s.serverTerminalModel, s.apiKey, s.loraModel) { ServerModelChoice.advise(c) }
    val current = ServerModelChoice.of(s.serverTerminalModel)
    Section(tr("Server-Terminal")) {
        HintText(tr("Wo Lora denkt, wenn sie im Server-Terminal Befehle vorschlägt. Externe Cloud-Anbieter sind dort gesperrt."))
        listOf(advice.local, advice.tu).forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(selected = current == o.choice, enabled = o.possible, onClick = { c.settings.update { it.copy(serverTerminalModel = o.choice.key) } })
                Text(o.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                DataPlaceChip(if (o.choice == ServerModelChoice.Choice.LOCAL) DataPlace.LOCAL else DataPlace.TU)
                if (o.choice == advice.recommended) {
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Empfohlen"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (current == null) HintText(tr("Noch nicht gewählt – das Terminal fragt beim ersten Öffnen."))
        TextButton(onClick = { ask = true }) { Text(tr("Vor- und Nachteile zeigen")) }
    }
    if (ask) ServerModelDialog(advice, current, onChosen = { ch -> c.settings.update { it.copy(serverTerminalModel = ch.key) }; ask = false }, onCancel = { ask = false })
}
