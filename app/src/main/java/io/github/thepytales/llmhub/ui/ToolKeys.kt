package io.github.thepytales.llmhub.ui

/**
 * Tastenübersicht je Werkzeug (AP-135 c, Analyse Q-03): Strg+/ shows the keys of the open editor first, the app's
 * below. Each list holds only keys the editor really handles (its key handlers are named beside it); the video editor
 * brings its own groups (VIDEO_SHORTCUT_GROUPS, desktop).
 */
typealias KeyGroups = List<Pair<String, List<Pair<String, String>>>>

object ToolKeys {
    /** Fließtext-Editor (FlowEditor, FlowKeys; AP-125 c): like Word. */
    val DOCUMENT: KeyGroups = listOf(
        trLater("Im Dokument (wie in Word)") to listOf(
            trLater("Strg+P") to trLater("Drucken"),
            trLater("Strg+F · Strg+H") to trLater("Suchen · Suchen und ersetzen"),
            trLater("Strg+B · Strg+I · Strg+U") to trLater("Fett · Kursiv · Unterstrichen"),
            trLater("Strg+Alt+1 … 3 · Strg+Alt+0") to trLater("Überschrift 1 bis 3 · Standard"),
            trLater("Strg+Umschalt+E · Strg+R · Strg+Umschalt+J") to trLater("Zentriert, rechtsbündig, Blocksatz"),
            trLater("Strg+← / →, Strg+↑ / ↓") to trLater("Wort- bzw. absatzweise (mit Umschalt: markieren)"),
            trLater("Strg+Pos1 / Strg+Ende") to trLater("Anfang bzw. Ende des Dokuments"),
            trLater("Strg+Rück / Strg+Entf") to trLater("Wort löschen"),
            trLater("Alt+Umschalt+↑ / ↓") to trLater("Absatz verschieben"),
            trLater("Umschalt+F3") to trLater("Groß-/Kleinschreibung"),
            trLater("Strg+Umschalt+> / <") to trLater("Schrift größer / kleiner"),
            trLater("Strg+Umschalt+C · Strg+Alt+V") to trLater("Format kopieren und einfügen"),
            trLater("Strg+\\") to trLater("Formatierung entfernen"),
            trLater("Strg+Enter") to trLater("Seitenumbruch"),
            trLater("Strg+J") to trLater("Hilf mir schreiben"),
            trLater("Strg+E") to trLater("Kana neben dem Dokument"),
            trLater("Strg+Umschalt+F") to trLater("Menüs ein- und ausblenden"),
            "F7" to trLater("Rechtschreibung prüfen"),
            trLater("Strg+Klick") to trLater("Satz markieren bzw. Link öffnen"),
            "@" to trLater("Bausteine, Tabelle, Datum … einfügen"),
        ),
    )

    /** Tabelle (SheetEditor, key handler of the cell field): like Excel. */
    val SHEET: KeyGroups = listOf(
        trLater("In der Tabelle (wie in Excel)") to listOf(
            trLater("Strg+P") to trLater("Drucken"),
            trLater("Enter · Umschalt+Enter") to trLater("Zelle darunter · darüber"),
            trLater("Tab · Umschalt+Tab") to trLater("Zelle rechts · links"),
            trLater("↑ ↓ (mit Umschalt: markieren)") to trLater("Zelle darüber, darunter"),
            trLater("Umschalt+← / →") to trLater("Bereich nach links, rechts erweitern"),
            trLater("Esc") to trLater("Eingabe verwerfen"),
            trLater("Strg+C · Strg+V") to trLater("Bereich kopieren und einfügen (auch aus Excel)"),
            trLater("Strg+B · Strg+I · Strg+U") to trLater("Fett · Kursiv · Unterstrichen"),
            trLater("Strg+F · Strg+H") to trLater("Suchen · Suchen und ersetzen"),
            "Tab" to trLater("Vorschlag für die Formel übernehmen"),
            "=" to trLater("Formel beginnen, z. B. =SUMME(B2:B9)"),
        ),
    )

    /** Präsentation (SlidesEditor, SlideCanvasEditor, Bildschirmpräsentation): like PowerPoint. */
    val SLIDES: KeyGroups = listOf(
        trLater("In der Präsentation (wie in PowerPoint)") to listOf(
            trLater("Strg+P") to trLater("Drucken"),
            "F5" to trLater("Präsentieren von vorn (Umschalt+F5: ab dieser Folie)"),
            trLater("Strg+M") to trLater("Neue Folie"),
            trLater("Strg+D") to trLater("Folie bzw. Gewähltes duplizieren"),
            trLater("Strg+Umschalt+↑ / ↓") to trLater("Folie nach vorn, nach hinten"),
            trLater("Strg+G · Strg+Umschalt+G") to trLater("Gruppieren · Gruppierung aufheben"),
            trLater("Strg+C · Strg+X") to trLater("Kopieren · Ausschneiden"),
            trLater("Strg+Umschalt+C / V") to trLater("Format übertragen und einfügen"),
            trLater("Pfeiltasten") to trLater("Gewähltes verschieben"),
            trLater("Entf") to trLater("Gewähltes löschen"),
            trLater("Tab · Umschalt+Tab") to trLater("Aufzählung einrücken, ausrücken"),
        ),
        trLater("Beim Präsentieren") to listOf(
            trLater("→ · Leertaste · Bild ab") to trLater("Weiter"),
            trLater("← · Bild auf · Rück") to trLater("Zurück"),
            trLater("Zahl + Enter") to trLater("Zu dieser Folie"),
            trLater("Pos1 · Ende") to trLater("Erste · letzte Folie"),
            trLater("B · W") to trLater("Schwarzes · weißes Bild"),
            trLater("D · E · L") to trLater("Stift · Tinte löschen · Laserpointer"),
            "N" to trLater("Notizen"),
            trLater("Esc") to trLater("Beenden"),
        ),
    )

    /** Gestalten (DesignEditor, key handler of the canvas): like Canva. */
    val DESIGN: KeyGroups = listOf(
        trLater("Beim Gestalten (wie in Canva)") to listOf(
            trLater("Strg+P") to trLater("Drucken"),
            trLater("Strg+Z · Strg+Y") to trLater("Rückgängig · Wiederholen"),
            trLater("Strg+C · Strg+X · Strg+V") to trLater("Kopieren · Ausschneiden · Einfügen"),
            trLater("Strg+D") to trLater("Duplizieren"),
            trLater("Strg+A") to trLater("Alles auswählen"),
            trLater("Strg+G · Strg+Umschalt+G") to trLater("Gruppieren · Gruppierung aufheben"),
            trLater("Strg+Umschalt+C / V") to trLater("Format übertragen und einfügen"),
            trLater("Pfeiltasten") to trLater("Gewähltes verschieben"),
            trLater("Entf") to trLater("Gewähltes löschen"),
        ),
    )

    /** Transkript (transcriptKey): like the former Whisper app and f4. */
    val TRANSCRIPT: KeyGroups = listOf(
        trLater("Im Transkript") to listOf(
            trLater("F4 · Strg+Leertaste") to trLater("Abspielen / anhalten"),
            trLater("F3 · F5") to trLater("Zurück · vor (Sekunden wie in den Einstellungen)"),
            "F6" to trLater("Abschnitt in Schleife"),
            trLater("Strg+F") to trLater("Suchen und ersetzen"),
            trLater("Strg+P") to trLater("Drucken"),
        ),
    )

    /** PDF-Werkzeugkasten. */
    val PDF: KeyGroups = listOf(
        trLater("Im PDF-Werkzeugkasten") to listOf(
            trLater("Strg+P") to trLater("Drucken (mit Druck-Lotse)"),
            trLater("Strg+K") to trLater("Werkzeug suchen: „drehen“, „schwärzen“, „zusammenfügen“ …"),
        ),
    )
}
