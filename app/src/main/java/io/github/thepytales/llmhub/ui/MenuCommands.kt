package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.data.AppMap

/**
 * Befehle des Werkzeugs in Strg+K (AP-135 a, Analyse Q-01) – wie „Menüs durchsuchen“ in Google Docs: every entry of the
 * menus of the open editor is findable and runnable by its name and by words people use for it („Seitenränder“ finds
 * *Seite einrichten …*).
 *
 * The menus stay where they are written (no second list to keep up to date): while Strg+K is open ([recording]), every
 * menu marked with `commands = "…"` ([DropdownMenu]) composes its entries once more, invisibly, and [DropdownMenuItem]
 * and [MenuRow] report themselves to the [ToolCommands] of the editor instead of drawing a row. So Strg+K shows exactly
 * what the menus show – the same names, the same „✓“, greyed out where the menu is greyed out – and runs the same code.
 * Context menus of one object (a clip, a cell under the mouse) are not marked: without their object they mean nothing.
 */
object EditorCommands {
    /** True while Strg+K is open: the marked menus of the open editor report their entries. */
    var recording by mutableStateOf(false)

    /** The tools on screen, the innermost last (a document inside the Dateien screen …). */
    private val open = mutableStateListOf<ToolCommands>()

    /** The open editor's commands, or null where no editor is open. */
    val current: ToolCommands? get() = open.lastOrNull()

    internal fun opened(t: ToolCommands) { open.remove(t); open.add(t) }
    internal fun closed(t: ToolCommands) { open.remove(t) }

    /**
     * Words people search for that the menus do not use. The key is a part of the menu entry (lower case, as in
     * [AppMap.norm]), the value the words that find it as well. German and English, since the entries are translated.
     */
    private val SYNONYMS: List<Pair<String, String>> = listOf(
        "seite einrichten" to "seitenränder seitenrand ränder rand papierformat papier a4 a5 hochformat querformat ausrichtung margins page setup paper",
        "seitenlayout" to "seitenfarbe wasserzeichen hintergrund page color watermark",
        "spalte links einfügen" to "spalte einfügen neue spalte spalte hinzufügen insert column add column",
        "spalte rechts einfügen" to "spalte einfügen neue spalte spalte hinzufügen insert column add column",
        "zeile darüber einfügen" to "zeile einfügen neue zeile zeile hinzufügen insert row add row",
        "zeile darunter einfügen" to "zeile einfügen neue zeile zeile hinzufügen insert row add row",
        "untertitel" to "subtitles captions srt vtt untertitelung",
        "aus der transkription" to "untertitel erzeugen untertitel automatisch",
        "suchen und ersetzen" to "finden ersetzen find replace",
        "rechtschreibung" to "rechtschreibprüfung korrektur tippfehler spelling spell check",
        "exportieren" to "speichern als pdf word docx herunterladen export save as",
        "herunterladen als" to "speichern als exportieren export save as download",
        "drucken" to "ausdrucken drucker print",
        "inhaltsverzeichnis" to "verzeichnis gliederung toc table of contents",
        "fußnote" to "anmerkung footnote",
        "seitenumbruch" to "neue seite umbruch page break",
        "seitenzahlen" to "seitennummer nummerierung page numbers",
        "zeilenabstand" to "abstand zeilen line spacing",
        "kopf" to "kopfzeile fußzeile header footer",
        "kommentar" to "anmerkung notiz comment",
        "verlauf" to "rückgängig schritte history undo",
        "rückgängig" to "zurück undo",
        "bild" to "foto grafik image picture",
        "tabelle" to "table",
        "formel" to "gleichung mathe equation formula",
        "diagramm" to "chart grafik schaubild",
        "folie" to "slide seite",
        "neue folie" to "folie hinzufügen folie einfügen add slide new slide",
        "titel" to "überschrift bauchbinde title lower third",
        "teilen" to "schneiden split cut",
        "ton" to "audio lautstärke sound",
        "export" to "exportieren speichern video speichern mp4 rendern",
        "seiten drehen" to "drehen rotate",
        "zusammenfügen" to "zusammenführen kombinieren merge combine",
        "schwärzen" to "unkenntlich redact",
        "sprecher" to "speaker",
    )

    /** The extra words for a menu entry (from [SYNONYMS]). */
    fun synonyms(title: String): String {
        val t = AppMap.norm(title)
        return normed.filter { (key, _) -> key in t }.joinToString(" ") { it.second }
    }

    private val normed by lazy { SYNONYMS.map { (k, v) -> AppMap.norm(k) to AppMap.norm(v) } }

    /**
     * The commands for [query], best first: all words in the entry, its menu or its synonyms; the entry itself before
     * its menu, beginnings before middles, entries that can run now before greyed-out ones.
     */
    fun search(tool: ToolCommands, query: String, max: Int = 8): List<MenuCommand> {
        val words = AppMap.norm(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return tool.unique().filter { cmd ->
            val hay = AppMap.norm(cmd.title + " " + cmd.path + " " + cmd.keywords + " " + synonyms(cmd.title))
            words.all { it in hay }
        }.sortedWith(compareBy<MenuCommand>(
            { !it.enabled },
            { cmd ->
                val t = AppMap.norm(cmd.title)
                val q = words.joinToString(" ")
                when {
                    t == q || t.startsWith(q) -> 0
                    words.all { it in t } -> 1
                    q in AppMap.norm(synonyms(cmd.title) + " " + cmd.keywords) -> 2
                    else -> 3
                }
            },
            { it.title.length },
        )).take(max)
    }
}

/** One entry of an editor's menu, as Strg+K shows it: name, menu („Einfügen“), keys, greyed out or not, what it does. */
class MenuCommand internal constructor() {
    var title: String = ""
        internal set
    var path: String = ""
        internal set
    var keys: String = ""
        internal set
    var enabled: Boolean = true
        internal set

    /** More words that find it (the tooltip of a button, what a mode of the PDF-Werkzeugkasten contains). */
    var keywords: String = ""
        internal set
    internal var action: () -> Unit = {}

    /** Carries out the entry as a click in the menu would. */
    fun run() = action()

    override fun toString() = "$path › $title"
}

/** The commands of one open editor ([area] is its name in Strg+K: „Dokument“, „Tabelle“, „Video“ …). */
class ToolCommands(area: String, keys: KeyGroups = emptyList()) {
    /** The editor's name in Strg+K; a screen that shows several kinds (Dokument, Tabelle, Präsentation) sets it. */
    var area by mutableStateOf(area)

    /** Its keys for Strg+/ (AP-135 c, [ToolKeys]). */
    var keys by mutableStateOf(keys)

    /** What Strg+P prints here (AP-135 d, [PrintableHere]); null where there is nothing to print. */
    @Volatile
    var printable: Printable? = null

    internal val entries = mutableStateListOf<MenuCommand>()

    /** The entries without doubles (the same name in the same menu once, a name of the menu bar before a toolbar's). */
    fun unique(): List<MenuCommand> = entries.filter { it.title.isNotBlank() }.distinctBy { AppMap.norm(it.path + "|" + it.title.removePrefix("✓ ")) }

    /** All entries with their menu, for tests and the overview. */
    val all: List<MenuCommand> get() = unique()
}

/** The editor whose menus report while Strg+K is open. */
internal val LocalToolCommands = staticCompositionLocalOf<ToolCommands?> { null }

/** Set while a marked menu composes its entries for Strg+K: the name of the menu. */
internal val LocalMenuRecording = compositionLocalOf<String?> { null }

/** Set while an entry's text is read for Strg+K: [Text] writes into it instead of drawing. */
internal val LocalTextCapture = staticCompositionLocalOf<StringBuilder?> { null }

/**
 * Marks [content] as the editor [area] for Strg+K: its marked menus report their entries while Strg+K is open. Put once
 * around the editor (the innermost wins when editors are nested). [keys] are its keys for Strg+/ (AP-135 c).
 */
@Composable
fun ToolCommandsArea(area: String, keys: KeyGroups = emptyList(), content: @Composable () -> Unit) {
    val tool = remember { ToolCommands(area, keys) }
    DisposableEffect(tool) {
        EditorCommands.opened(tool)
        onDispose { EditorCommands.closed(tool) }
    }
    CompositionLocalProvider(LocalToolCommands provides tool) { content() }
}

/**
 * Composes a marked menu's entries once more, invisibly, while Strg+K is open (called by [DropdownMenu]). Nothing of it
 * is drawn or seen by screen readers and tests: entries report themselves, everything else is clipped to nothing.
 */
@Composable
internal fun RecordMenu(name: String, content: @Composable ColumnScope.() -> Unit) {
    if (!EditorCommands.recording || LocalToolCommands.current == null) return
    val parent = LocalMenuRecording.current
    val path = if (parent.isNullOrBlank()) name else "$parent › $name"
    CompositionLocalProvider(LocalMenuRecording provides path) {
        Column(Modifier.size(0.dp).clipToBounds().clearAndSetSemantics { }) { content() }
    }
}

/** Reports one entry while its menu is recorded; false when nothing is recorded (then the entry is drawn as usual). */
@Composable
internal fun recordEntry(title: String, keys: String, enabled: Boolean, onClick: () -> Unit, keywords: String = ""): Boolean {
    val path = LocalMenuRecording.current ?: return false
    val tool = LocalToolCommands.current ?: return false
    val entry = remember { MenuCommand() }
    entry.title = title.trim()
    entry.path = path
    entry.keys = keys
    entry.enabled = enabled
    entry.keywords = keywords
    entry.action = onClick
    DisposableEffect(tool, entry) {
        tool.entries.add(entry)
        onDispose { tool.entries.remove(entry) }
    }
    return true
}

/**
 * Reports an entry that is no menu row – a button of a bar, a mode of the PDF-Werkzeugkasten – to Strg+K while it is
 * open ([path] is where it sits: „Leiste“, „Seiten“ …). Outside Strg+K it does nothing.
 */
@Composable
fun ToolCommand(title: String, path: String, onClick: () -> Unit, enabled: Boolean = true, keys: String = "", keywords: String = "") {
    if (!EditorCommands.recording || LocalToolCommands.current == null || LocalMenuRecording.current != null) return
    CompositionLocalProvider(LocalMenuRecording provides path) { recordEntry(title, keys, enabled, onClick, keywords) }
}

/** The text a composable writes with [Text] (for the names of menu entries given as `text = { Text(…) }`). */
@Composable
internal fun captureText(content: (@Composable () -> Unit)?): String {
    if (content == null) return ""
    val sb = StringBuilder()
    CompositionLocalProvider(LocalTextCapture provides sb) { content() }
    return sb.toString().trim()
}

/**
 * Material's menu entry (every menu of this package uses it instead of Material's): drawn as usual, and inside a menu
 * marked for Strg+K ([DropdownMenu] with `commands`) reported as a command instead (AP-135 a).
 */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
) {
    if (LocalMenuRecording.current != null) {
        val title = captureText(text)
        val keys = captureText(trailingIcon)
        if (recordEntry(title, keys, enabled, onClick)) return
    }
    androidx.compose.material3.DropdownMenuItem(
        text = text, onClick = onClick, modifier = modifier, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        enabled = enabled, colors = colors, contentPadding = contentPadding, interactionSource = interactionSource,
    )
}
