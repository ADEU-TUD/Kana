# Lückenliste Video: Lora gegen Clipchamp, CapCut und DaVinci Resolve (AP-129 a)

Stand: 19.11.2026 · Grundlage für **AP-129** im [Arbeitsplan](ARBEITSPLAN.md). Verglichen wird der Videoeditor (AP-25,
AP-52 bis AP-57, AP-70, AP-121, AP-128, AP-130) mit **Clipchamp** (Windows), **CapCut** und **DaVinci Resolve** (ohne
Fusion, Color und Fairlight, siehe Grundsätze) – für die vier Zwecke des Teams: **Vorlesungsaufzeichnungen** (V),
**Interviews/Gespräche** (I), **kurze Clips** (K, Social Media, Ankündigungen) und **Unterrichtsvideos** (U, Lehramt,
oft mit Kindern und Namen). Lebendes Dokument: Wird eine Lücke geschlossen, wechselt die Zeile auf ✓ und nennt das Paket.

**Legende** · Stand: ✓ vorhanden · ◐ teilweise (was fehlt, steht dabei) · ✗ fehlt · — bewusst nicht (Grund dabei) ·
Nutzen fürs Team: **hoch** · mittel · gering · Zwecke: V I K U.

## Schnitt und Aufbau

| Funktion | Zwecke | Stand | Nutzen |
| --- | --- | --- | --- |
| Clips teilen, kürzen, Ripple, Roll, Slip, Magnet, Spuren sperren | V I K U | ✓ (AP-53, AP-121) | hoch |
| Pausen und Füllwörter automatisch herausschneiden | V I U | ✓ (AP-25, AP-55) | hoch |
| Im Transkript schneiden (Text löschen = Video schneiden) | V I U | ✓ (AP-55) | hoch |
| Mit Lora kürzen nach Wunsch („auf 10 Minuten, Beispiele behalten“) | V I U | ✓ (AP-25, AP-55) | hoch |
| **Mehrkamera-Schnitt nach Sprecher** (zwei Kameras, über den Ton synchronisiert, Schnitt zur sprechenden Person) | I | ✓ *Rohschnitt › Mehrkamera nach Sprecher* – nach Sprechern des Transkripts oder nach Lautstärke der Mikrofone; Ton der Hauptkamera läuft durch (AP-129) | **hoch** |
| Mehrere Tonquellen über die Wellenform synchronisieren | I V | ✓ (AP-53) | hoch |
| **Höhepunkte aus einer langen Aufnahme als Clip** | K V | ✓ *Rohschnitt › Höhepunkte als Clip* – Lora wählt 30 s bis 3 min, neues Projekt, auf Wunsch gleich 9:16 mit großen Untertiteln (AP-129) | **hoch** |
| Kapitel und Marker, Kapitel in der MP4 | V U | ✓ (AP-53); Kapitel aus dem Transkript vorschlagen ◐ → AP-147 | mittel · AP-147 |
| Standbild, rückwärts, Tempo 0,25×–4× | K U | ✓ (AP-54) | mittel |
| Tempo-Rampen (sanft schneller/langsamer) | K | ✗ | gering |
| Szenenerkennung (lange Datei an Schnitten teilen) | K | ✗ – Aufnahmen des Teams sind meist eine Einstellung | gering |

## Bild

| Funktion | Zwecke | Stand | Nutzen |
| --- | --- | --- | --- |
| Bild-in-Bild (Webcam über Folien), Position, Größe, Zuschnitt | V U | ✓ (AP-52) | hoch |
| Zoom und Schwenk mit Keyframes (Ken Burns, Screencast) | V U | ✓ (AP-54) | hoch |
| **Hochformat/Quadrat mit Bildausschnitt, der der Person folgt** (Auto-Reframe) | K | ✓ *Projekt › Format › Hochformat: Ausschnitt folgt der Person* – 9:16, 1:1, 4:5, Gesichtserkennung auf dem Gerät, ruhige Führung (AP-129); vorher nur 9:16 von Hand über Keyframes | **hoch** |
| Helligkeit, Kontrast, Sättigung, Weißabgleich auf Knopfdruck | alle | ✓ (AP-54) | mittel |
| Gesichter verpixeln, Bereiche verpixeln/weichzeichnen | U I | ✓ (AP-54, AP-128) | hoch |
| Unscharfer Hintergrund statt schwarzer Balken (Hochformat ohne Ausschnitt) | K | ✗ | mittel |
| Verwacklung ausgleichen (Stabilisieren) | I K | ✗ – Handyaufnahmen ohne Stativ; ffmpeg kann es (vidstab), aber langsam | mittel |
| Greenscreen (Chroma Key), Hintergrund entfernen | K | ✗ | gering |
| Pfeile, Kreise, Markierungen im Bild | U V | ◐ als Bild auf V2 (PNG aus *Erstellen*); keine eigenen Formen im Editor | mittel |
| LUTs, Farbkorrektur wie DaVinci Color | — | — bewusst nicht (Grundsatz: ohne Color) | gering |

## Text und Untertitel

| Funktion | Zwecke | Stand | Nutzen |
| --- | --- | --- | --- |
| Titel, Bauchbinde, Einblendung, Abspann im TUD-Design | alle | ✓ (AP-25) | hoch |
| **Vorlagen für Titel und Abspann** je Zweck (Vorlesung, Interview mit Bauchbinden der Sprecher, Clip, Unterricht) | alle | ✓ *So entsteht dein Video › Vorlage …*, Eigenschaften eines Titels (AP-129); TUD-Design unverändert | **hoch** |
| Untertitel aus der Transkription, einbrennen oder als SRT/VTT | alle | ✓ (AP-25) | hoch |
| **Untertitel-Stile für kurze Clips** (groß mit Kontur, Wort für Wort mit hervorgehobenem Wort) | K | ✓ *Projekt › Untertitel › Aussehen*: Standard · Groß · Wort für Wort (AP-129) | **hoch** |
| Untertitel prüfen (Zeilenlänge, Lesetempo, Lücken) | V U | ✗ → AP-147 | mittel · AP-147 |
| Untertitel übersetzen | V | ✗ → AP-140 (Übersetzen) | mittel |
| Animierte Texte, Sticker, Emojis | K | ✗ – passt nicht zum CD der TU | gering |

## Ton

| Funktion | Zwecke | Stand | Nutzen |
| --- | --- | --- | --- |
| Rauschminderung (RNNoise), Lautheit −16 LUFS, Pegel | alle | ✓ (AP-25) | hoch |
| **Musik automatisch leiser, wenn gesprochen wird** (Ducking) | K U V | ✓ *Projekt › Ton › Musik leiser, wenn gesprochen wird* – leicht/mittel/stark, genau um so viel dB (AP-129) | **hoch** |
| Namen im Ton piepen, stumm, Stimme verfremden | U I | ✓ (AP-128) | hoch |
| Ton vom Bild lösen, verknüpfen, mehrere Tonspuren | alle | ✓ (AP-53) | hoch |
| Sprecherstimme aus Text (Text-to-Speech) | U V | ✗ → AP-141 (vertonte Präsentation) | mittel · AP-141 |
| Musik- und Geräuschbibliothek | K | — bewusst nicht (Lizenzen; eigene Dateien gehen) | gering |
| Equalizer, Hall entfernen | I | ✗ | gering |

## Aufnahme und Export

| Funktion | Zwecke | Stand | Nutzen |
| --- | --- | --- | --- |
| Bildschirm mit Webcam und Mikrofon aufnehmen | V U | ✓ (AP-56) | hoch |
| Präsentation aufnehmen → Lehrvideo | V | ✗ → AP-141 | hoch · AP-141 |
| Export MP4 mit Vorgaben (OPAL, YouTube), GPU, Warteschlange | alle | ✓ (AP-25, AP-57, AP-81) | hoch |
| Vorgabe Videocampus Sachsen mit VTT daneben | V U | ◐ → AP-147 | mittel · AP-147 |
| Export im Hochformat für Reels/Shorts | K | ✓ Projektformat 9:16 (AP-52) plus Ausschnitt (AP-129) | hoch |
| Datenschutz-Prüfliste vor dem Export | U I | ✓ (AP-128) | hoch |

## Ergebnis

Alle Lücken mit **hohem** Nutzen für die vier Zwecke sind geschlossen (AP-129): Ducking, Mehrkamera nach Sprecher,
Hochformat mit Ausschnitt, der der Person folgt, Untertitel-Stile, Höhepunkte als Clip, Vorlagen für Titel und Abspann.
Präsentation → Lehrvideo (hoch) steht als eigenes Paket AP-141 im Plan. Offen mit mittlerem Nutzen: unscharfer
Hintergrund, Stabilisieren, Pfeile und Formen im Bild, Untertitel prüfen/übersetzen (AP-147, AP-140), Text-to-Speech
(AP-141).
