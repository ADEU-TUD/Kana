package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thepytales.llmhub.ssh.ServerFiles
import io.github.thepytales.llmhub.ssh.ServerTerminal
import io.github.thepytales.llmhub.ssh.SshAuth
import io.github.thepytales.llmhub.ssh.SshKeys
import io.github.thepytales.llmhub.ssh.SshProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files of the server beside the terminal (AP-131 b, like WinSCP): the folder with name, size and rights; double
 * click opens a folder or a text file; files from the Explorer/Finder are dropped in to upload; per entry *Herunterladen*,
 * *Umbenennen*, *Löschen* (asks first). On request the folder follows `cd` in the terminal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerFilesPane(term: ServerTerminal, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val files = remember(term, term.connection) { term.openFiles() }
    var dir by remember(files) { mutableStateOf("") }
    var typed by remember(files) { mutableStateOf("") }
    var entries by remember(files) { mutableStateOf<List<ServerFiles.Entry>>(emptyList()) }
    var error by remember(files) { mutableStateOf<String?>(null) }
    var follow by remember(files) { mutableStateOf(true) }
    var toDelete by remember { mutableStateOf<Pair<ServerFiles.Entry, Int>?>(null) }
    var toRename by remember { mutableStateOf<ServerFiles.Entry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf<List<File>?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    if (files == null) {
        Box(modifier.padding(12.dp)) { Text(tr("Die Dateien des Servers sind nicht erreichbar (SFTP ist dort vielleicht aus)."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        return
    }
    val transfer by files.transfer.collectAsState()

    fun go(path: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { val abs = files.resolve(path); abs to files.list(abs) } }
            r.onSuccess { (abs, list) -> dir = abs; typed = abs; entries = list; error = null }
                .onFailure { error = tr("Ordner nicht lesbar: {0}", it.message ?: it.javaClass.simpleName) }
        }
    }
    fun refresh() = go(dir.ifEmpty { "." })
    fun io(what: suspend () -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { what() } }.onFailure { error = it.message ?: it.javaClass.simpleName }
        refresh()
    }
    fun upload(list: List<File>, overwrite: Boolean) = io { list.forEach { files.upload(it, dir, replace = overwrite) }; info = tr("{0} Datei(en) hochgeladen.", list.size) }
    fun dropped(list: List<File>) {
        if (dir.isEmpty()) return
        val existing = entries.map { it.name }.toSet()
        if (list.any { it.name in existing }) replace = list else upload(list, false)
    }

    LaunchedEffect(files) { go(term.state.value.profile?.workDir?.takeIf { it.isNotBlank() } ?: ".") }
    // Follows cd in the terminal: the folder from the prompt or the end of Kana's last command.
    LaunchedEffect(files, follow) {
        var last: String? = null
        while (follow) {
            val f = term.terminalFolder()
            if (f != null && f != last) { last = f; go(f) }
            delay(1200)
        }
    }

    Surface(modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = remember(files) { fileDrop { dropped(it) } }), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Dateien auf dem Server"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                typed, { typed = it }, singleLine = true, label = { Text(tr("Ordner")) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { follow = false; go(typed); true } else false },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { follow = false; go(ServerFiles.parent(dir)) }) { Icon(Icons.Outlined.ArrowUpward, tr("Übergeordneter Ordner")) }
                IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, tr("Neu laden")) }
                IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, tr("Neuer Ordner")) }
                IconButton(onClick = { pickFiles()?.let { dropped(it) } }) { Icon(Icons.Outlined.Upload, tr("Hochladen")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(follow, { follow = it })
                Text(tr("folgt dem Terminal"), style = MaterialTheme.typography.bodySmall)
            }
            transfer?.let { t ->
                Text(if (t.upload) tr("Lade hoch: {0}", t.name) else tr("Lade herunter: {0}", t.name), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.total > 0) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.weight(1f)) else LinearProgressIndicator(Modifier.weight(1f))
                    TextButton(onClick = { files.cancelTransfer() }) { Text(tr("Abbrechen")) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { it.path }) { e ->
                    FileRow(
                        e,
                        onOpen = {
                            if (e.isDir) { follow = false; go(e.path) }
                            else scope.launch {
                                val text = withContext(Dispatchers.IO) { runCatching { files.readText(e.path) }.getOrNull() }
                                if (text != null) editing = e.path to text else info = tr("„{0}“ ist keine Textdatei (oder größer als 2 MB) – zum Ansehen herunterladen.", e.name)
                            }
                        },
                        onDownload = { saveFileDialog(e.name)?.let { target -> io { files.download(e.path, target); info = tr("Gespeichert: {0}", target.absolutePath) } } },
                        onRename = { toRename = e },
                        onDelete = { scope.launch { toDelete = e to withContext(Dispatchers.IO) { runCatching { files.countInside(e) }.getOrDefault(1) } } },
                    )
                }
            }
            Text(tr("Dateien aus dem Explorer hierher ziehen, um sie hochzuladen."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    toDelete?.let { (e, n) ->
        ConfirmDialog(
            title = tr("„{0}“ löschen?", e.name),
            text = if (e.isDir) tr("Der Ordner wird mit allem darin gelöscht ({0} Einträge) – auf dem Server gibt es keinen Papierkorb.", n) else tr("Die Datei wird auf dem Server gelöscht – dort gibt es keinen Papierkorb."),
            confirm = tr("Endgültig löschen"),
            onConfirm = { toDelete = null; io { files.delete(e); info = tr("Gelöscht: {0}", e.name) } },
            onDismiss = { toDelete = null },
        )
    }
    toRename?.let { e ->
        TextInputDialog(title = tr("Umbenennen"), initial = e.name, label = tr("Neuer Name"), confirm = tr("Umbenennen"), onConfirm = { n -> toRename = null; io { files.rename(e.path, n) } }, onDismiss = { toRename = null })
    }
    if (newFolder) TextInputDialog(title = tr("Neuer Ordner"), initial = "", label = tr("Name"), confirm = tr("Anlegen"), onConfirm = { n -> newFolder = false; io { files.mkdir(dir, n) } }, onDismiss = { newFolder = false })
    replace?.let { list ->
        ConfirmDialog(
            title = tr("Dateien ersetzen?"),
            text = tr("Im Ordner gibt es schon: {0}. Ersetzen?", list.filter { f -> entries.any { it.name == f.name } }.joinToString(", ") { it.name }),
            confirm = tr("Ersetzen"),
            onConfirm = { replace = null; upload(list, true) },
            onDismiss = { replace = null },
        )
    }
    editing?.let { (path, text) -> RemoteTextEditor(path, text, onSave = { t -> withContext(Dispatchers.IO) { files.writeText(path, t) } }, onClose = { editing = null; refresh() }) }
}

@Composable
private fun FileRow(e: ServerFiles.Entry, onOpen: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null, Modifier.size(18.dp), tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name + if (e.isLink) " ↗" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (e.isDir) "" else ServerFiles.size(e.size) + " · ") + e.permissions, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.MoreVert, tr("Mehr zu {0}", e.name), Modifier.size(16.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    if (!e.isDir) DropdownMenuItem(text = { Text(tr("Herunterladen …")) }, onClick = { menu = false; onDownload() })
                    DropdownMenuItem(text = { Text(tr("Umbenennen")) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(tr("Löschen …"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** A text file of the server in its own window: edit and *Speichern* writes it back (owner and rights stay). */
@Composable
private fun RemoteTextEditor(path: String, initial: String, onSave: suspend (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var saved by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf<String?>(null) }
    var askClose by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberDialogState(size = DpSize(960.dp, 680.dp))
    fun save() = scope.launch {
        runCatching { onSave(text) }.onSuccess { saved = text; status = tr("Gespeichert.") }.onFailure { status = tr("Nicht gespeichert: {0}", it.message ?: it.javaClass.simpleName) }
    }
    DialogWindow(onCloseRequest = { if (text != saved) askClose = true else onClose() }, state = state, title = path) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(enabled = text != saved, onClick = { save() }) { Text(tr("Speichern")) }
                    TextButton(onClick = { if (text != saved) askClose = true else onClose() }) { Text(tr("Schließen")) }
                }
                OutlinedTextField(
                    text, { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().weight(1f).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.S && (e.isCtrlPressed || e.isMetaPressed)) { save(); true } else false
                    },
                )
                Text(tr("Strg+S speichert auf dem Server. Besitzer und Rechte der Datei bleiben."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (askClose) ConfirmDialog(
            title = tr("Änderungen verwerfen?"), text = tr("Die Änderungen sind noch nicht auf dem Server gespeichert."),
            confirm = tr("Verwerfen"), onConfirm = { askClose = false; onClose() }, onDismiss = { askClose = false },
        )
    }
}

private val androidx.compose.ui.input.key.KeyEvent.isCtrlPressed: Boolean get() = nativeKeyEvent.let { (it as? java.awt.event.KeyEvent)?.isControlDown == true }
private val androidx.compose.ui.input.key.KeyEvent.isMetaPressed: Boolean get() = nativeKeyEvent.let { (it as? java.awt.event.KeyEvent)?.isMetaDown == true }

private fun pickFiles(): List<File>? = runCatching {
    val d = java.awt.FileDialog(null as java.awt.Frame?, tr("Dateien zum Hochladen wählen"), java.awt.FileDialog.LOAD)
    d.isMultipleMode = true
    d.isVisible = true
    d.files?.toList()?.takeIf { it.isNotEmpty() }
}.getOrNull()

private fun saveFileDialog(name: String): File? = runCatching {
    val d = java.awt.FileDialog(null as java.awt.Frame?, tr("Speichern unter"), java.awt.FileDialog.SAVE)
    File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }?.let { d.directory = it.absolutePath }
    d.file = name
    d.isVisible = true
    d.file?.let { File(d.directory, it) }
}.getOrNull()

/**
 * SSH keys (AP-131 a): create a new Ed25519 pair (or take an existing key file), show and copy the public key, and – when
 * connected – put it on the server as a step for approval; afterwards Kana offers to log in with the key.
 * [forProfile]/[onCreated]: from the session form, the new key goes into the form.
 */
@Composable
internal fun KeyDialog(term: ServerTerminal, onDismiss: () -> Unit, forProfile: SshProfile? = null, onCreated: (String) -> Unit = {}) {
    val state by term.state.collectAsStateWithLifecycle()
    val profile = forProfile ?: state.profile
    val clipboard = LocalClipboardManager.current
    val existing = profile?.keyFile?.takeIf { profile.auth == SshAuth.KEY && it.isNotBlank() }
    var name by remember { mutableStateOf("lora-" + (profile?.host?.substringBefore('.')?.ifBlank { null } ?: "server")) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var keyFile by remember { mutableStateOf<String?>(existing) }
    var line by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyPass by remember { mutableStateOf("") }
    val comment = remember { "lora@" + (runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "pc") }
    LaunchedEffect(keyFile) {
        line = null
        keyFile?.let { f -> line = withContext(Dispatchers.IO) { runCatching { SshKeys.publicLineOf(File(f), term.sessions.secrets.get(profile?.id.orEmpty())) }.getOrNull() } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.VpnKey, null) },
        title = { Text(tr("SSH-Schlüssel")) },
        text = {
            Column(Modifier.widthIn(min = 460.dp, max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (keyFile == null) {
                    Text(tr("Ein neues Schlüsselpaar (Ed25519) entsteht auf diesem Gerät. Der private Schlüssel bleibt im Kana-Ordner und ist nur für dein Benutzerkonto lesbar; eine Passphrase schützt ihn zusätzlich."), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Name des Schlüssels")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(tr("Passphrase (empfohlen)")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (pass.isNotEmpty()) OutlinedTextField(pass2, { pass2 = it }, label = { Text(tr("Passphrase wiederholen")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), isError = pass2.isNotEmpty() && pass2 != pass)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = name.isNotBlank() && (pass.isEmpty() || pass == pass2), onClick = {
                            runCatching { SshKeys.create(term.dir, name, pass.ifEmpty { null }, comment) }
                                .onSuccess { c -> keyFile = c.privateKey.absolutePath; line = c.publicLine; onCreated(c.privateKey.absolutePath); error = null }
                                .onFailure { error = it.message ?: it.javaClass.simpleName }
                        }) { Text(tr("Schlüssel erzeugen")) }
                        TextButton(onClick = { pickKeyFile()?.let { keyFile = it; if (forProfile != null) onCreated(it) } }) { Text(tr("Vorhandenen wählen …")) }
                    }
                } else {
                    Text(tr("Schlüssel: {0}", File(keyFile!!).name), style = MaterialTheme.typography.titleSmall)
                    if (line == null) {
                        Text(tr("Der Schlüssel ist mit einer Passphrase geschützt – zum Lesen des öffentlichen Teils eingeben:"), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(keyPass, { keyPass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f), label = { Text(tr("Passphrase")) })
                            OutlinedButton(onClick = { runCatching { SshKeys.publicLineOf(File(keyFile!!), keyPass) }.onSuccess { line = it; error = null }.onFailure { error = it.message } }) { Text(tr("Lesen")) }
                        }
                    }
                    line?.let { l ->
                        Text(tr("Öffentlicher Schlüssel – darf weitergegeben werden (z. B. an die Administration):"), style = MaterialTheme.typography.bodySmall)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                            SelectionContainer { Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(l)) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Kopieren")) }
                            if (state.connected && forProfile == null) Button(onClick = {
                                term.offer(tr("Schlüssel auf den Server legen"), SshKeys.installStep(l), tr("Kana legt den öffentlichen Schlüssel nach deinem Klick in ~/.ssh/authorized_keys."), keyFile)
                                onDismiss()
                            }) { Text(tr("Auf den Server legen …")) }
                        }
                    }
                    TextButton(onClick = { keyFile = null; line = null }) { Text(tr("Anderen Schlüssel")) }
                }
                error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
    )
}
