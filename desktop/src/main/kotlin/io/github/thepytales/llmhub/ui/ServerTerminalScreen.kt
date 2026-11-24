package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.desktop.Paths
import io.github.thepytales.llmhub.ssh.KnownHosts
import io.github.thepytales.llmhub.ssh.Risk
import io.github.thepytales.llmhub.ssh.Risks
import io.github.thepytales.llmhub.ssh.ServerAssistant
import io.github.thepytales.llmhub.ssh.ServerFlows
import io.github.thepytales.llmhub.ssh.ServerModelChoice
import io.github.thepytales.llmhub.ssh.ServerStep
import io.github.thepytales.llmhub.ssh.ServerTerminal
import io.github.thepytales.llmhub.ssh.ServerWorkspace
import io.github.thepytales.llmhub.ssh.SshAuth
import io.github.thepytales.llmhub.ssh.SshConnection
import io.github.thepytales.llmhub.ssh.SshProfile
import io.github.thepytales.llmhub.ssh.SshTty
import io.github.thepytales.llmhub.ssh.StepState
import kotlinx.coroutines.launch
import java.io.File

/** The sessions of the Server-Terminal stay for the whole app run: leaving the screen keeps tabs and connections. */
object ServerTerminals {
    @Volatile private var instance: ServerWorkspace? = null

    fun of(c: AppContainer): ServerWorkspace = instance ?: synchronized(this) {
        instance ?: ServerWorkspace(File(Paths.data, "ssh")) { ServerAssistant(c) }.also { instance = it }
    }
}

/** Colours a session can give its tab (AP-131 c) – e.g. red for the live server, so nobody confuses it with the test one. */
internal val TAB_COLORS = listOf(Color(0xFF5C7CFA), Color(0xFF2F9E44), Color(0xFFF08C00), Color(0xFFAE3EC9), Color(0xFFE03131), Color(0xFF1098AD))
internal val TAB_COLOR_NAMES = listOf(trLater("Blau"), trLater("Grün"), trLater("Orange"), trLater("Lila"), trLater("Rot"), trLater("Türkis"))

internal fun tabColor(p: SshProfile?): Color = TAB_COLORS[(p?.color ?: 0).mod(TAB_COLORS.size)]

/**
 * Server-Terminal (AP-118, AP-131): sessions in tabs like in a browser; per tab the saved sessions (not connected) or
 * the terminal (JediTerm), the files of the server (SFTP, on request) and Kana's column, where every command waits
 * for a click and then runs in the terminal. [terminalView] draws the terminal (tests replace it with a stand-in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerTerminalScreen(
    c: AppContainer,
    onBack: () -> Unit,
    workspace: ServerWorkspace = ServerTerminals.of(c),
    terminalView: @Composable (ServerTerminal, Modifier) -> Unit = { t, m -> JediTerminal(t, m) },
    where: ServerAssistant.Where? = null,
    filesAtStart: Boolean = false,
) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    // Where Kana thinks (AP-131 e): asked once; afterwards Einstellungen › Kana or „Ändern“ in Kana's column.
    val advice = remember(settings.serverTerminalModel, settings.apiKey, settings.loraModel) { ServerModelChoice.advise(c) }
    val place = where ?: remember(settings.serverTerminalModel, settings.apiKey, settings.loraModel) { ServerAssistant(c).where() }
    var changePlace by remember { mutableStateOf(false) }
    val tabs by workspace.tabs.collectAsStateWithLifecycle()
    val active by workspace.active.collectAsStateWithLifecycle()
    val term = tabs[active.coerceIn(0, tabs.lastIndex)]
    val state by term.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showFiles by remember(term) { mutableStateOf(filesAtStart) }
    var keyDialog by remember { mutableStateOf(false) }
    var broadcast by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text(tr("Server-Terminal")) },
                actions = {
                    if (state.connected) {
                        TextButton(onClick = { showFiles = !showFiles }) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (showFiles) tr("Dateien ausblenden") else tr("Dateien"))
                        }
                        TextButton(onClick = { keyDialog = true }) {
                            Icon(Icons.Outlined.VpnKey, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Schlüssel"))
                        }
                    }
                    if (tabs.count { it.state.value.connected } >= 2) TextButton(onClick = { broadcast = true }) {
                        Icon(Icons.Outlined.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("An mehrere senden"))
                    }
                    if (state.connected) TextButton(onClick = { TerminalWidgets.drop(term); term.disconnect() }) { Text(tr("Trennen")) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SessionTabs(workspace, tabs, active)
            state.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.connected) SessionsPane(term, Modifier.fillMaxSize())
            else Row(Modifier.fillMaxSize()) {
                if (showFiles) ServerFilesPane(term, Modifier.width(300.dp).fillMaxHeight())
                terminalView(term, Modifier.weight(1.5f).fillMaxHeight().padding(8.dp))
                LoraServerPane(c, term, Modifier.weight(1f).widthIn(min = 340.dp).fillMaxHeight(), place, onChangePlace = { changePlace = true })
            }
        }
    }
    state.hostKey?.let { p ->
        HostKeyDialog(p.result, p.host, p.port, onTrust = { replace -> scope.launch { term.acceptHostKey(replace) } }, onCancel = { term.rejectHostKey() })
    }
    if (settings.serverTerminalModel.isBlank() && advice.anyPossible) {
        ServerModelDialog(advice, null, onChosen = { ch -> c.settings.update { it.copy(serverTerminalModel = ch.key) } })
    } else if (changePlace) {
        ServerModelDialog(advice, ServerModelChoice.of(settings.serverTerminalModel), onChosen = { ch -> c.settings.update { it.copy(serverTerminalModel = ch.key) }; changePlace = false }, onCancel = { changePlace = false })
    }
    if (keyDialog) KeyDialog(term, onDismiss = { keyDialog = false })
    if (broadcast) BroadcastDialog(workspace, onDismiss = { broadcast = false })
}

/** The tabs: one per session with its colour, ✕ to close, + for another server (AP-131 c). */
@Composable
private fun SessionTabs(workspace: ServerWorkspace, tabs: List<ServerTerminal>, active: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { i, t ->
                val s by t.state.collectAsStateWithLifecycle()
                val chosen = i == active
                Surface(
                    onClick = { workspace.select(i) },
                    shape = MaterialTheme.shapes.small,
                    color = if (chosen) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (s.connected) tabColor(s.profile) else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.profile?.takeIf { s.connected || s.busy != null }?.label ?: tr("Neue Sitzung"),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
                        )
                        IconButton(onClick = { TerminalWidgets.drop(t); workspace.close(i) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.Close, tr("Tab schließen"), Modifier.size(16.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { workspace.add() }) { Icon(Icons.Outlined.Add, tr("Weitere Sitzung in neuem Tab")) }
        }
    }
}

/** Saved sessions on the left, the form of the chosen one on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionsPane(term: ServerTerminal, modifier: Modifier) {
    val state by term.state.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf(term.sessions.all()) }
    var edit by remember { mutableStateOf(state.profile ?: list.firstOrNull() ?: SshProfile()) }
    val scope = rememberCoroutineScope()
    Row(modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Gespeicherte Sitzungen"), style = MaterialTheme.typography.titleSmall)
            list.forEach { p ->
                val chosen = p.id == edit.id
                Surface(
                    onClick = { edit = p; term.clearError() },
                    shape = MaterialTheme.shapes.small,
                    color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp), tint = tabColor(p))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.bodyMedium)
                            Text("${p.user}@${p.host}:${p.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // One click opens it (AP-131 a) – with a saved password or a key without passphrase right away.
                        IconButton(enabled = p.problems().isEmpty() && state.busy == null, onClick = { edit = p; scope.launch { term.connect(p) } }) {
                            Icon(Icons.Outlined.PlayArrow, tr("Öffnen"), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { edit = SshProfile() }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text(tr("Neue Sitzung")) }
            Spacer(Modifier.size(8.dp))
            Text(
                tr("Wie PuTTY: Host, Port, eigener Benutzer, Schlüssel oder Passwort. Passwörter bleiben auf diesem Gerät und gehen nie an ein Modell."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).widthIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SessionForm(term, edit, onChange = { edit = it })
            var secret by remember(edit.id) { mutableStateOf("") }
            var keep by remember(edit.id) { mutableStateOf(false) }
            val saved = remember(edit.id, list) { term.sessions.secrets.get(edit.id) != null }
            OutlinedTextField(
                secret, { secret = it },
                label = { Text(if (edit.auth == SshAuth.PASSWORD) tr("Passwort") else tr("Passphrase des Schlüssels (falls gesetzt)")) },
                placeholder = { if (saved) Text(tr("gespeichert – leer lassen")) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(keep, { keep = it })
                Text(tr("Verschlüsselt auf diesem Gerät speichern"), style = MaterialTheme.typography.bodySmall)
                if (saved) TextButton(onClick = { term.sessions.secrets.forget(edit.id); list = term.sessions.all() }) { Text(tr("Gespeichertes vergessen")) }
            }
            state.error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val problems = edit.problems()
                Button(enabled = problems.isEmpty() && state.busy == null, onClick = {
                    term.sessions.save(edit); list = term.sessions.all()
                    scope.launch { term.connect(edit, secret, keep) }
                }) { Text(tr("Verbinden")) }
                OutlinedButton(enabled = problems.isEmpty(), onClick = { term.sessions.save(edit); list = term.sessions.all() }) { Text(tr("Speichern")) }
                if (list.any { it.id == edit.id }) TextButton(onClick = { term.sessions.delete(edit.id); list = term.sessions.all(); edit = list.firstOrNull() ?: SshProfile() }) { Text(tr("Löschen")) }
            }
            edit.problems().takeIf { it.isNotEmpty() && edit.host.isNotBlank() }?.let { Text(it.joinToString(" · ") { p -> tr(p) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionForm(term: ServerTerminal, p: SshProfile, onChange: (SshProfile) -> Unit) {
    var newKey by remember { mutableStateOf(false) }
    Text(if (p.host.isBlank()) tr("Neue Sitzung") else p.label, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(p.name, { onChange(p.copy(name = it)) }, label = { Text(tr("Name (frei)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(p.host, { onChange(p.copy(host = it.trim())) }, label = { Text(tr("Host oder IP-Adresse")) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(p.port.toString(), { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { onChange(p.copy(port = it)) } }, label = { Text(tr("Port")) }, singleLine = true, modifier = Modifier.width(96.dp))
    }
    OutlinedTextField(p.user, { onChange(p.copy(user = it.trim())) }, label = { Text(tr("Benutzer")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(p.auth == SshAuth.KEY, { onChange(p.copy(auth = SshAuth.KEY)) }, label = { Text(tr("Schlüssel")) })
        FilterChip(p.auth == SshAuth.PASSWORD, { onChange(p.copy(auth = SshAuth.PASSWORD)) }, label = { Text(tr("Passwort")) })
    }
    if (p.auth == SshAuth.KEY) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(p.keyFile, { onChange(p.copy(keyFile = it)) }, label = { Text(tr("Schlüsseldatei (OpenSSH oder PuTTY .ppk)")) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { pickKeyFile()?.let { onChange(p.copy(keyFile = it)) } }) { Text(tr("Durchsuchen")) }
        }
        TextButton(onClick = { newKey = true }) { Icon(Icons.Outlined.VpnKey, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Neuen Schlüssel erzeugen")) }
    }
    OutlinedTextField(p.workDir, { onChange(p.copy(workDir = it.trim())) }, label = { Text(tr("Ordner der Anwendung (z. B. /var/www/app)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(p.webUser, { onChange(p.copy(webUser = it.trim())) }, label = { Text(tr("Benutzer des Webservers")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(tr("Farbe im Tab"), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TAB_COLORS.forEachIndexed { i, col ->
            FilterChip(
                p.color == i, { onChange(p.copy(color = i)) }, label = { Text(tr(TAB_COLOR_NAMES[i])) },
                leadingIcon = { Box(Modifier.size(10.dp).background(col, CircleShape)) },
            )
        }
    }
    if (newKey) KeyDialog(term, onDismiss = { newKey = false }, forProfile = p, onCreated = { file -> onChange(p.copy(auth = SshAuth.KEY, keyFile = file)) })
}

internal fun pickKeyFile(): String? = runCatching {
    val d = java.awt.FileDialog(null as java.awt.Frame?, tr("Schlüsseldatei wählen"), java.awt.FileDialog.LOAD)
    File(System.getProperty("user.home"), ".ssh").takeIf { it.isDirectory }?.let { d.directory = it.absolutePath }
    d.isVisible = true
    d.file?.let { File(d.directory, it).absolutePath }
}.getOrNull()

/** Short wishes a click away – what colleagues ask most (AP-131 d). */
private val SERVER_WISHES = listOf(
    trLater("Wie viel Speicherplatz ist noch frei?"),
    trLater("Zeig die letzten Fehler im Log der Anwendung"),
    trLater("Läuft Apache ohne Fehler?"),
    trLater("Wann läuft das Zertifikat ab?"),
)

/**
 * Kana's column (AP-118, AP-131 d): say what should happen in the terminal; Kana says in a sentence what she wants to
 * do and shows the next command with its risk. Only after *Ausführen* she types it into the terminal and presses Enter,
 * then looks at the result: she says what came out or proposes the next command – again with a click.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoraServerPane(c: AppContainer, term: ServerTerminal, modifier: Modifier, where: ServerAssistant.Where = remember { ServerAssistant(c).where() }, onChangePlace: () -> Unit = {}) {
    val state by term.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var wish by remember(term) { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val canAsk = where.model != null && state.busy == null
    fun ask(text: String) {
        if (text.isBlank() || !canAsk) return
        scope.launch { term.propose(text) }
        wish = ""
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Kana"), style = MaterialTheme.typography.titleSmall)
                state.profile?.let { p ->
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).background(tabColor(p), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("hilft in {0}", p.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Datenschutz: always visible where the proposals come from and what leaves the device.
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = if (where.onDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(where.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onChangePlace) { Text(tr("Lokal oder TU-Server ändern")) }
                }
            }
            OutlinedTextField(
                wish, { wish = it }, label = { Text(tr("Was soll im Terminal passieren?")) },
                placeholder = { Text(tr("z. B. Wie viel Speicher ist frei? · Deploy wie immer")) },
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    // Enter asks Kana, Umschalt+Enter makes a new line.
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) { ask(wish); true } else false
                },
                minLines = 2,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = wish.isNotBlank() && canAsk, onClick = { ask(wish) }) { Text(tr("Kana fragen")) }
            }
            if (state.steps.isEmpty()) {
                if (where.model != null) {
                    Text(tr("Zum Beispiel"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SERVER_WISHES.forEach { e -> AssistChip(enabled = canAsk, onClick = { ask(tr(e)) }, label = { Text(tr(e)) }) }
                    }
                }
                Text(tr("Feste Abläufe"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ServerFlows.ALL.forEach { f -> AssistChip(onClick = { term.load(f) }, label = { Text(tr(f.title)) }) }
                }
            }
            state.error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.keySwitch?.let { key ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("Dein Schlüssel liegt jetzt auf dem Server."), style = MaterialTheme.typography.titleSmall)
                        Text(tr("Soll sich die Sitzung künftig mit dem Schlüssel „{0}“ statt mit dem Passwort anmelden? Ein gespeichertes Passwort wird dann vergessen.", File(key).name), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { term.switchToKey() }) { Text(tr("Mit Schlüssel anmelden")) }
                            TextButton(onClick = { term.dismissKeySwitch() }) { Text(tr("Später")) }
                        }
                    }
                }
            }
            if (state.steps.isNotEmpty() || state.note.isNotBlank()) {
                HorizontalDivider()
                state.flowTitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                if (state.note.isNotBlank()) LoraSays(state.note)
                val next = state.steps.indexOfFirst { it.state == StepState.OPEN || it.state == StepState.FAILED }
                val running = state.steps.indexOfFirst { it.state == StepState.RUNNING }
                if (state.steps.any { it.state == StepState.OPEN }) Text(
                    tr("Jeder Befehl läuft erst nach deinem Klick – im Terminal, wo du alles siehst."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.steps.forEachIndexed { i, s ->
                    StepCard(i, s, isNext = i == next && running < 0, term, state.waitsForInput && i == running)
                    if (s.state == StepState.FAILED && where.model != null && state.goal == null) TextButton(enabled = state.busy == null, onClick = {
                        scope.launch { term.propose(tr("Der Schritt „{0}“ ist fehlgeschlagen (Exit {1}). Was ist die Ursache, und was ist der nächste Schritt?", s.command, s.exitCode ?: "?"), s.output) }
                    }) { Text(tr("Kana fragen")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { term.cancel() }) { Text(if (state.steps.any { it.state == StepState.OPEN }) tr("Abbrechen") else tr("Fertig")) }
                }
            }
            state.sent?.let { sent ->
                var open by remember(sent) { mutableStateOf(false) }
                TextButton(onClick = { open = !open }) { Text(if (open) tr("Gesendetes ausblenden") else tr("Was an den Server ging")) }
                if (open) SelectionContainer { Text(sent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) tr("Protokoll ausblenden") else tr("Protokoll")) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(term.readLog())) }) { Text(tr("Protokoll kopieren")) }
            }
            if (showLog) Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                SelectionContainer { Text(term.readLog().ifBlank { tr("Noch keine Einträge.") }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** What Kana says: her plan before the steps, or the result after them. */
@Composable
private fun LoraSays(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp)) {
            Text(tr("Kana:"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(6.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

/**
 * One step. The next open one is the question „Kana möchte …“ with *Ausführen* (typed into the terminal with Enter),
 * *Bearbeiten*, *Überspringen*; a running one shows that it runs, whether it waits for input, *Strg+C* and *Ist fertig*.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(index: Int, s: ServerStep, isNext: Boolean, term: ServerTerminal, waitsForInput: Boolean) {
    val scope = rememberCoroutineScope()
    var editing by remember(s.command) { mutableStateOf(false) }
    var text by remember(s.command) { mutableStateOf(s.command) }
    var showOutput by remember(s.state) { mutableStateOf(s.state == StepState.FAILED) }
    val riskColor = when (s.risk) {
        Risk.READ -> MaterialTheme.colorScheme.onSurfaceVariant
        Risk.CHANGE -> MaterialTheme.colorScheme.tertiary
        Risk.RISKY -> MaterialTheme.colorScheme.error
    }
    val asking = isNext && (s.state == StepState.OPEN || s.state == StepState.FAILED)
    OutlinedCard(
        Modifier.fillMaxWidth(),
        border = BorderStroke(if (asking) 2.dp else 1.dp, if (asking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                if (asking) Text(tr("Kana möchte:") + " ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (s.risk == Risk.RISKY) Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp), tint = riskColor)
                Text(tr(s.risk.label), color = riskColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    when (s.state) {
                        StepState.OPEN -> ""
                        StepState.RUNNING -> tr("läuft …")
                        StepState.DONE -> tr("fertig") + (s.exitCode?.let { " · Exit $it" } ?: "")
                        StepState.FAILED -> tr("Fehler") + (s.exitCode?.let { " · Exit $it" } ?: "")
                        StepState.SKIPPED -> tr("übersprungen")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.state == StepState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.what.isNotBlank() && (asking || s.state == StepState.OPEN || s.state == StepState.RUNNING)) Text(s.what, style = MaterialTheme.typography.bodyMedium)
            if (editing) OutlinedTextField(text, { text = it }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            else Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                SelectionContainer { Text(s.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp)) }
            }
            if (s.safety.isNotBlank() && (asking || s.state == StepState.OPEN)) Text(tr("Sicherheit: {0}", s.safety), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.state == StepState.RUNNING) {
                if (waitsForInput) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Keyboard, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Das Terminal wartet auf deine Eingabe (z. B. Passwort oder Ja/Nein) – tippe sie dort ein. Kana sieht sie nicht."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (term.tty != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { term.interrupt(index) }) { Text(tr("Abbrechen (Strg+C)")) }
                    TextButton(onClick = { term.markDone(index) }) { Text(tr("Ist fertig")) }
                }
            }
            if (s.output.isNotBlank() && s.state != StepState.RUNNING) {
                TextButton(onClick = { showOutput = !showOutput }) { Text(if (showOutput) tr("Ausgabe ausblenden") else tr("Ausgabe zeigen")) }
                if (showOutput) Box(Modifier.fillMaxWidth().heightIn(max = 160.dp).background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(s.output.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp)) }
                }
            }
            if (s.state == StepState.OPEN || s.state == StepState.FAILED) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing) {
                    Button(onClick = { term.edit(index, text); editing = false }) { Text(tr("Übernehmen")) }
                    TextButton(onClick = { editing = false; text = s.command }) { Text(tr("Zurück")) }
                } else {
                    Button(
                        enabled = isNext,
                        onClick = {
                            // In the terminal when it is open (visible, cd stays, sudo asks there); otherwise in the background.
                            if (term.tty != null) { if (term.runInTerminal(index)) TerminalWidgets.focus(term) } else scope.launch { term.run(index) }
                        },
                        colors = if (s.risk == Risk.RISKY) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                    ) { Text(if (s.state == StepState.FAILED) tr("Nochmal") else tr("Ausführen")) }
                    TextButton(onClick = { editing = true }) { Text(tr("Bearbeiten")) }
                    TextButton(onClick = { term.skip(index) }) { Text(tr("Überspringen")) }
                    if (term.tty != null) TextButton(enabled = isNext, onClick = { term.typeIntoTerminal(index); TerminalWidgets.focus(term) }) { Text(tr("Nur eintragen")) }
                }
            }
        }
    }
}

/**
 * The terminals of the sessions: one JediTerm per connection that survives switching tabs and leaving the screen
 * (closing it would end the shell); closed when the tab is closed or the session disconnects.
 */
internal object TerminalWidgets {
    private class Open(val conn: SshConnection, val widget: com.jediterm.terminal.ui.JediTermWidget)
    private val open = java.util.WeakHashMap<ServerTerminal, Open>()

    @Synchronized
    fun of(term: ServerTerminal): Result<com.jediterm.terminal.ui.JediTermWidget>? {
        val conn = term.connection ?: return null
        open[term]?.let { o -> if (o.conn === conn) return Result.success(o.widget); runCatching { o.widget.close() } }
        return runCatching {
            val w = com.jediterm.terminal.ui.JediTermWidget(100, 30, com.jediterm.terminal.ui.settings.DefaultSettingsProvider())
            val tty = SshTty(conn.shell(100, 30), conn.profile.label)
            w.setTtyConnector(tty)
            w.start()
            term.attach(tty)
            open[term] = Open(conn, w)
            w
        }
    }

    @Synchronized
    fun drop(term: ServerTerminal) {
        open.remove(term)?.let { runCatching { it.widget.close() } }
        term.detach()
    }

    /** Puts the keyboard into the terminal (after Kana typed a command – a password prompt can be answered at once). */
    fun focus(term: ServerTerminal) {
        val w = synchronized(this) { open[term]?.widget } ?: return
        javax.swing.SwingUtilities.invokeLater { runCatching { w.terminalPanel.requestFocusInWindow() } }
    }
}

/** The real terminal: JediTerm with the SSH shell of the session's connection; resized with the window. */
@Composable
fun JediTerminal(term: ServerTerminal, modifier: Modifier) {
    val state by term.state.collectAsStateWithLifecycle()
    val opened = remember(term, state.connected, term.connection) { TerminalWidgets.of(term) } ?: return
    val widget = opened.getOrNull()
    if (widget == null) {
        Text(tr("Das Terminal ließ sich nicht öffnen: {0}", opened.exceptionOrNull()?.message ?: "?"), modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    SwingPanel(factory = { widget }, modifier = modifier)
}

/** New key: show the fingerprint and ask. Changed key: warn and require an explicit replace after comparing. */
@Composable
private fun HostKeyDialog(result: KnownHosts.Result, host: String, port: Int, onTrust: (replace: Boolean) -> Unit, onCancel: () -> Unit) {
    val changed = result as? KnownHosts.Result.Changed
    var compared by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(if (changed != null) Icons.Outlined.Warning else Icons.Outlined.Lock, null) },
        // The circle behind the symbol in the same colour (AP-123): red when the server's key changed.
        iconContentColor = if (changed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = { Text(if (changed != null) tr("Achtung: Der Server-Schlüssel hat sich geändert") else tr("Diesem Server vertrauen?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (port == 22) host else "$host:$port", style = MaterialTheme.typography.titleSmall)
                if (changed != null) {
                    Text(tr("Kana kennt für diesen Server einen anderen Schlüssel. Das passiert nach einer Neuinstallation – oder wenn jemand die Verbindung abfängt. Verbinde dich nur, wenn die Administration den neuen Schlüssel bestätigt hat."), color = MaterialTheme.colorScheme.error)
                    Text(tr("Bisher: {0}", changed.previous.joinToString(", ")), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(tr("Jetzt: {0}", result.fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(compared, { compared = it })
                        Text(tr("Ich habe den neuen Fingerabdruck verglichen."), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(tr("Erste Verbindung zu diesem Server. Vergleiche den Fingerabdruck mit dem, den die Administration nennt (ssh-keygen -lf auf dem Server):"))
                    Text(result.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    (result as? KnownHosts.Result.New)?.let { Text(tr("Typ: {0}", it.keyType), style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
        confirmButton = {
            if (changed != null) OutlinedButton(enabled = compared, onClick = { onTrust(true) }) { Text(tr("Schlüssel ersetzen"), color = if (compared) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
            else Button(onClick = { onTrust(false) }) { Text(tr("Vertrauen und verbinden")) }
        },
        dismissButton = { if (changed != null) Button(onClick = onCancel) { Text(tr("Abbrechen")) } else TextButton(onClick = onCancel) { Text(tr("Abbrechen")) } },
    )
}

/**
 * „An mehrere senden“ (AP-131 c): one line into several sessions – the user ticks each session; a second question
 * names them all and the command with its risk before anything is typed.
 */
@Composable
private fun BroadcastDialog(workspace: ServerWorkspace, onDismiss: () -> Unit) {
    val targets = remember { workspace.connected() }
    var line by remember { mutableStateOf("") }
    val chosen = remember { mutableStateOf(setOf<ServerTerminal>()) }
    var confirm by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<Int?>(null) }
    if (confirm) {
        val names = targets.filter { it in chosen.value }.joinToString(", ") { it.state.value.profile?.label.orEmpty() }
        ConfirmDialog(
            title = tr("Wirklich in {0} Sitzungen ausführen?", chosen.value.size),
            text = tr("Diese Zeile wird in jede Sitzung eingetippt und sofort ausgeführt ({0}):", tr(Risks.of(line).label)) + "\n\n" + line.trim() + "\n\n" + tr("Sitzungen: {0}", names),
            confirm = tr("In {0} Sitzungen ausführen", chosen.value.size),
            danger = Risks.of(line) == Risk.RISKY,
            onConfirm = { sent = workspace.broadcast(line, targets.filter { it in chosen.value }); confirm = false },
            onDismiss = { confirm = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Eine Zeile an mehrere Sitzungen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(min = 420.dp)) {
                OutlinedTextField(line, { line = it.replace("\n", "") }, label = { Text(tr("Befehl")) }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                Text(tr("An diese Sitzungen:"), style = MaterialTheme.typography.labelMedium)
                targets.forEach { t ->
                    val p = t.state.value.profile
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t in chosen.value, { on -> chosen.value = if (on) chosen.value + t else chosen.value - t })
                        Box(Modifier.size(10.dp).background(tabColor(p), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(p?.label.orEmpty())
                    }
                }
                sent?.let { Text(tr("In {0} Sitzungen ausgeführt.", it), color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { Button(enabled = line.isNotBlank() && chosen.value.isNotEmpty(), onClick = { confirm = true }) { Text(tr("Senden …")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
    )
}
