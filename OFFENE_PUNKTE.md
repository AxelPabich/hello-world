# Offene Punkte

Gesammelte Wünsche für kommende Builds. Umgesetzt und gebaut wird erst auf
ausdrückliche Bitte.

Stand: Build 4 (Version 1.0.4) – alle sichtbaren Funktionen laufen auf den
Testgeräten (Samsung SM-X930 / Android 16, Samsung SM-T865 / Android 12).

## Diagnose – erledigt

1. Doppelte Einträge zusammengefasst: gleiche aufeinanderfolgende Anzeigen stehen im
   Spieler-Protokoll nur noch einmal.
2. Master protokolliert fehlende Reaktionen: „Keine Reaktion: Peter“.
3. Master protokolliert Gleichstände und Sudden Death:
   „Gleichstand Axel/Peter (1 ms)“, „Sudden Death um Platz 1: Axel/Peter“,
   „Sudden Death: Axel 300 ms, Peter 500 ms“.
4. Auffällige Funk-Laufzeiten werden bei der Freigabe markiert:
   „Hinweis: Funk-Laufzeit Peter 939 ms (sonst ~210 ms)“ – wenn sie mehr als doppelt
   so lang und mindestens 300 ms länger als üblich ist.

## Erledigt im nächsten Build

5. Nutzbare Bildschirmfläche (Systemleisten, Taskleiste, schwebende Knöpfe ausgespart).
6. Oberfläche im Stil A „TV-Quizshow“ mit neuer Menüstruktur; weitere Designs vorbereitet.
7. Synthetische Klänge, je Profil schaltbar mit Reset; Richtig/Falsch nur beim Betroffenen.
8. Spielverlauf-Animation (max. 10 s) mit Verfolgungsjagd-Musik auf allen Geräten.
9. Spieleinstellungen als Seite in der App statt Dialogfenster (lagen unter der Taskleiste).
10. Korrektur der letzten Runde (anderer Sieger oder storniert; Punkte- bzw. Zeitkonten).
11. Spielpause mit Overlay „Kurze Pause – Gleich geht es weiter“.
12. Spielfläche auf dem Tab S11 Ultra: mit Build 8 passt sie (der Eindruck „zu groß“ kam
    von einem noch installierten Build 7). Die geplante Messung der Insets entfällt.

13. App weitergeben (Bluetooth, Teilen-Auswahl, QR-Code) – Start- und Master-Menü.

## Offen

### 14. Quiz-Modus (Entscheidungen vom 13.10.2026)

**Spielstart:** Beim Starten eines Spiels fragt die App „Quiz“ oder „Buzzer“. Der
verbale Buzzer-Modus bleibt unverändert erhalten.

**Fragen-Quelle (Weg 1):** Claude recherchiert auf Zuruf und legt Fragenpakete ins
Repository (`quiz/*.json`). Kein API-Schlüssel, keine Kosten in der App.
Die Pakete werden in die APK eingebaut; zusätzlich kann der Master unter
„Fragen aktualisieren“ die neuesten Pakete direkt aus dem Repository laden
(öffentliche Datei, kein neuer Build nötig).

**Ablauf einer Frage**

1. Die Frage erscheint auf allen Geräten gleichzeitig (Uhrenabgleich wie bei der Freigabe).
2. 3 Sekunden später erscheinen die vier Antworten als 2×2-Felder unter der Frage,
   gleichzeitig startet der Antworttimer.
3. Die Reihenfolge der Antworten wird **für jeden Teilnehmer und jede Frage** neu
   gemischt – Abgucken bringt nichts.
4. Jeder tippt genau eine Antwort (danach gesperrt). Gewartet wird, bis alle
   geantwortet haben oder der Timer abgelaufen ist.
5. Auflösung:
   - richtiges Feld wird grün; wer es gewählt hat, bekommt darin ein 👍 und einen **Tusch**;
   - eine falsch gewählte Antwort wird rot, mit einem fetten ✗ durchgestrichen, dazu
     **„Wah-wah-waaah“**;
   - keine Antwort gewählt: nur das richtige Feld wird grün, dazu „Wah-wah-waaah“.
   - Klänge nur auf dem eigenen Gerät (wie bisher bei Richtig/Falsch).
6. Unter der Auflösung: kurze Erklärung, Link zur Quelle, Knopf „Challenge“.

**Wertung** (je Profil wählbar; falsche Antworten kosten nie etwas)

| Modus | Wer punktet |
|---|---|
| Schnellster punktet | nur die **erste richtige** Antwort (1 Punkt) |
| Jeder punktet | jede richtige Antwort (1 Punkt) |
| Countdown nach erster richtiger Antwort | Nach der ersten richtigen Antwort läuft ein kurzer Timer; alle richtigen bekommen ihre Restzeit gutgeschrieben |
| Battle | nur der Schnellste bekommt seine Restzeit gutgeschrieben |

**Fairness/Technik**

- Die Geräte erhalten die richtige Antwort **erst bei der Auflösung** – vorher steht sie
  nirgends auf dem Spielergerät (auch nicht im Speicher).
- Gemessen wird ab dem Einblenden der Antworten auf dem eigenen Gerät (wie die
  Reaktionszeit beim Buzzer); der Spieler meldet die Nummer der Originalantwort.
- Der Master kann mitspielen; auch er sieht die Lösung erst bei der Auflösung.

**Challenge:** Ein Spieler kann eine Frage anfechten. Der Master sieht Frage,
Antworten, Quelle und entscheidet: Wertung bleibt, Frage annullieren (Korrektur
wie bei der Rundenkorrektur), Frage sperren oder Antwort korrigieren. Das wird
in der Fragen-Datenbank vermerkt und fließt beim nächsten Paket-Update zu Claude zurück.

### 15. Fragen-Datenbank: ungespielte Fragen zuerst, Schwierigkeit lernt mit

**Fragenpaket (Repository, nur lesend)**

```json
{ "id": "kueche-0012", "version": 1, "kategorie": "Küche & Genuss",
  "stufe": "schwer", "frage": "Welches Gewürz …?",
  "antworten": ["Safran …", "Vanille …", "Kardamom …", "Muskatblüte …"],
  "erklaerung": "…", "quelle": "https://…", "erstellt": "2026-10-13",
  "gueltigBis": null }
```

- Die erste Antwort ist im Paket immer die richtige; gemischt wird erst in der App.
- `id` bleibt bei Korrekturen gleich, `version` steigt. Ändert sich eine Antwort,
  beginnt die Statistik dieser Frage neu.
- `gueltigBis` für Fragen zu aktuellen Themen („Wer ist gerade …“) – danach werden
  sie nicht mehr gezogen.

**Spielstatistik (lokal auf dem Master-Gerät, getrennt vom Paket)**

Je Frage: wie oft gespielt, zuletzt gespielt, **welche Spieler (Namen) sie schon
gesehen haben**, Anzahl Antworten / richtig / falsch / keine, Antwortzeiten der
Richtigen, Challenges und Entscheidungen, gesperrt ja/nein.

**Auswahl der nächsten Frage**

1. Filter: gewählte Kategorien und Stufen, nicht gesperrt, nicht abgelaufen.
2. Vorrang hat, was **möglichst wenige der aktuell verbundenen Spieler** schon
   gesehen haben – eine Frage, die nur ein neuer Mitspieler noch nicht kennt,
   gilt nicht als „neu“.
3. Bei Gleichstand: am längsten nicht gespielt, dann Zufall.
4. Innerhalb eines Spiels keine Wiederholung; Kategorien werden abwechselnd gezogen.
5. Sind alle Fragen bekannt, beginnt der Durchlauf von vorne (ältestes zuerst) und
   der Master bekommt den Hinweis „Neue Fragen anfordern“.

**Schwierigkeit lernt mit**

- Messwert je Frage: Anteil richtiger Antworten (alle Antwortenden inkl. „keine
  Antwort“) und mittlere Antwortzeit der Richtigen.
- Geglättet, damit wenige Spiele nicht kippen:
  `Quote = (richtig + 2·Startwert) / (Antworten + 2)` mit Startwert leicht 0,8 /
  mittel 0,55 / schwer 0,3.
- Einstufung: Quote ≥ 0,7 → leicht, 0,4–0,7 → mittel, < 0,4 → schwer. Brauchten
  die Richtigen im Schnitt mehr als 60 % des Timers, rutscht die Frage eine Stufe höher.
- Erst ab 6 Antworten ersetzt die gemessene Stufe die Autoren-Stufe; der Master
  sieht beides („Autor: mittel · gemessen: leicht“).

**Rückkanal zu Claude:** Die Statistik lässt sich wie die Diagnose teilen
(Datei `.buzzstats`). Daraus kann Claude zu leichte/zu schwere Fragen umstufen,
angefochtene Fragen prüfen, gesperrte ersetzen und gezielt Nachschub für knappe
Kategorien recherchieren.

### Konzept Oberfläche (Vorschlag)

- Startbildschirm: Logo, Name, zwei große Kacheln „Spiel leiten“ / „Mitspielen“,
  darunter „Profile & Einstellungen“, „Hilfe“, „Über & Diagnose“.
- Spieler: Zustandsfarbe bleibt als leuchtender Hintergrund; in der Mitte ein großer
  runder Buzzer, der in der Zustandsfarbe glüht (pulsiert bei Gelb, wackelt bei
  Frühstart, Konfetti bei „Richtig“). Oben Emoticons/Chat/Ton, unten Zeitbalken und Punkte.
- Master: Kopfzeile (Runde, Phase, Profil), Spielerkarten mit Statusring, Platz,
  Reaktionszeit und Punktebalken; unten **ein** großer Hauptknopf, der je nach Phase
  wechselt (Buzzer frei → Richtig/Falsch → Nächste Runde); seltene Aktionen im Menü ⋮.
- Eigene App-Einstellungen (Ton, Lautstärke, Vibration, Emoticon-Empfang,
  Export/Import, Diagnose) getrennt von den Spieleinstellungen des Masters.
- Vibration als Ergänzung zu Farbe und Ton.

### Audio-Vorschläge

| Moment | Effekt | Wo |
|---|---|---|
| Spieler tritt bei | kurzes „Ding“ | Master |
| Buzzer scharf / Zufallsverzögerung | leises elektrisches Summen (gleichbleibend) | alle |
| Freigabe (gelb) | **kein Ton** – siehe Hinweis | – |
| Eigener Buzz | klassischer Buzzer-Sound | eigenes Gerät |
| Platz 1 / grün | kurze Fanfare | grüner Spieler |
| Rot | dumpfes „Bonk“ | rote Spieler |
| Frühstart | Tröte / Fehlton | betroffener Spieler |
| Gleichstand → Sudden Death | Trommelwirbel | alle |
| Antwortzeit läuft | tickende Uhr, letzte 5 s schneller | grüner Spieler, Master |
| Zeit abgelaufen | Gong | grüner Spieler, Master |
| Richtig | Applaus + „Ding Ding“ | nur betroffener Spieler |
| Falsch | „Wah-wah-wah“ (Fail) | nur betroffener Spieler |
| Rundenende / Rangliste | Trommelwirbel + Tusch | alle |
| Emoticon | leises „Plopp“ (Standard aus) | Empfänger |
| Kurznachricht | sanftes „Pling“ | Empfänger |

Hinweis Fairness: Die Tonausgabe hat je nach Gerät 20–200 ms Verzögerung. Ein Ton bei
der Freigabe würde Spieler mit schnellem Lautsprecher bevorzugen – die Freigabe bleibt
deshalb rein visuell (plus optional Vibration, ebenfalls nicht ganz gleich schnell).

## Hinweise aus dem Test

- Uhrenabgleich: Bei gleichzeitigem Drücken auf zwei Geräten lagen die gemessenen
  Reaktionszeiten nur 1–10 ms auseinander (Funk-Laufzeit ~200 ms).
- Kurznachrichten als durchlaufender Chat im Spielfeld kommen gut an.
