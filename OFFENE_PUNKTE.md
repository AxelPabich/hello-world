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

## Umgesetzt im Quiz-Build (Punkte 14–17)

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

**Gestaltung** (Entwurf: https://claude.ai/artifact/Ebuq6z8eMP7AfNExwinGZK)

- Zeitbalken, Frage und die vier Antworten stehen **in einem gemeinsamen Feld** – die
  Frage direkt über den Antworten, auch beim mitspielenden Master (unteres Drittel).
- Solange der Antworttimer läuft, fliegen Emoticons nur in einer **eigenen Bahn
  oberhalb dieses Felds** (kleiner, von rechts nach links) – nie über die Antworten.
  Erst nach der Auflösung fliegen sie wieder wie gewohnt über die Spielfläche.
- Kurznachrichten erscheinen nur in den Pausen (Auflösung, zwischen den Fragen).

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

### 16. App-Versionen abgleichen und aktualisieren

**Versionen austauschen**

- Jede App kennt zwei Nummern: die **Build-Nummer** (z. B. 12) und eine
  **Protokoll-Version** (steigt nur, wenn sich die Nachrichten zwischen den Geräten
  inkompatibel ändern). Kompatibel ist, wer dieselbe Protokoll-Version spricht –
  unterschiedliche Builds können also zusammen spielen.
- Beide Nummern stehen schon in der Kennung, die bei der Suche sichtbar ist. So erkennt
  ein Spieler **vor dem Beitreten**, ob er zur Runde passt; der Master sieht in der
  Spielerliste die Version jedes Teilnehmers.
- Der erste Austausch nach dem Verbinden („Hallo“ mit Versionen) bleibt für alle
  künftigen Versionen gleich. Damit können sich auch inkompatible Geräte noch
  verbinden – dann aber nur zum Aktualisieren, nicht zum Spielen.

**Bei Inkompatibilität**

- Die App ermittelt die **höchste verfügbare Version** unter allen Teilnehmern
  (auch wenn ein Spieler neuer ist als der Master).
- Wer älter ist, bekommt den Hinweis „Deine App ist zu alt für diese Runde“ und zwei Wege:
  1. **Vom Mitspieler holen:** Die neueste APK wird direkt über die bestehende
     Nearby-Verbindung übertragen (ohne Internet) und danach die Installation angeboten.
  2. **Aus dem Repository laden:** die aktuellste Version von der Release-Seite
     (`buzzer-latest`) herunterladen und installieren.
- Ist die eigene App neuer als die der anderen, bietet sie an, ihre Version an die
  älteren Geräte zu verteilen (Master: „Alle auf Build 12 bringen“).
- Auch bei kompatiblen, aber älteren Versionen gibt es einen dezenten Hinweis
  „Neuere Version verfügbar“ – ohne Zwang.

**Technik und Grenzen**

- Updates funktionieren ohne Deinstallation, weil alle Builds mit demselben festen
  Schlüssel signiert sind.
- Android verlangt beim ersten Mal die Erlaubnis „Unbekannte Apps installieren“ für
  die Buzzer-App; die App führt direkt zur passenden Einstellung.
- Die übertragene Datei wird vor der Installation geprüft (Paketname, Signatur,
  höhere Build-Nummer), damit nur echte Buzzer-Updates installiert werden.
- Die bisherigen Builds (bis 11) kennen den Versionsabgleich noch nicht: Wer noch
  so einen Build hat, muss einmalig über „App weitergeben“ (QR-Code/Bluetooth) oder
  die Release-Seite aktualisieren. Ab dem Build mit diesem Punkt läuft es automatisch.

### 17. Master sieht, wenn jemand eine Nachricht tippt

- Solange ein Teilnehmer im Nachrichtenfenster tippt, erscheinen beim Master neben dem
  Brief-Knopf in der oberen Leiste **drei pulsierende Punkte** – ohne Namen, ohne Text.
  So kann er mit der Freigabe der nächsten Frage (bzw. Buzzer-Runde) noch warten.
- Gilt für Quiz- und Buzzer-Modus (Nachrichten gibt es nur in den Pausen).
- Technik: Das Spielergerät meldet „tippt“ beim ersten Zeichen und „fertig“ beim
  Senden oder Schließen; bleibt die Meldung aus (Verbindung weg), verschwinden die
  Punkte nach 8 Sekunden ohne Tastendruck von selbst. Nur der Master erhält die Meldung.
- Die Punkte sind nur ein Hinweis – die Freigabe wird dadurch nicht blockiert.

## Umgesetzt im nächsten Build (Punkte 18–21, Paket „Aktuelles 2026“)

18. **Standort ausgeschaltet:** Manche Geräte (z. B. Galaxy A53, Android 16) verlangen für die
    Suche nicht nur die Berechtigung, sondern auch den eingeschalteten Standort – sonst Fehler
    8034. Vor der Suche prüfen und mit Hinweis direkt zur Standort-Einstellung führen; Fehler
    8034 verständlich erklären.
19. **„Fragen aktualisieren“:** Bei HTTP 404 nicht „(Internet?)“ melden, sondern „Im
    Repository liegen keine neueren Fragen“.
20. **Prüfcode auf beiden Geräten (Sicherheit geht vor):** Der vierstellige Nearby-Code
    erscheint beim Master im Beitrittsdialog **und** beim Spieler („Warte auf Bestätigung –
    Code 7179, vergleiche ihn mit dem Spielleiter“). Nur bei gleichem Code annehmen. Der Code
    steht zusätzlich im Protokoll beider Geräte.
21. **Diagnose an den Master schicken und in GitHub ablegen (Entscheidungen vom 13.10.2026):**
    - Spieler: „An den Spielleiter senden“ im Diagnose-Dialog; Übertragung über Nearby.
      Systeminfos nur, wenn der Spieler sie ankreuzt – mit kurzer Erklärung, wozu sie dienen
      und wie die Daten geschützt sind.
    - Master: sammelt die Berichte und lädt sie in das **private** Repository
      `AxelPabich/buzzer-diagnose` hoch: `diagnose/JJJJ-MM-TT/Build-N/Name-HH-MM.txt`.
    - Zugang über ein Fine-grained Token (nur dieses Repository, „Contents: Read and write“),
      einmalig in der App eingegeben, verschlüsselt im Android-Keystore gespeichert.
      Beim ersten Hochladen zeigt die App eine Schritt-für-Schritt-Anleitung.
    - Keine zusätzliche Verschlüsselung mit dem Token (Begründung: sonst könnte nur das
      Master-Gerät die Berichte lesen, auch Claude nicht; das private Repository schützt sie).

## Umgesetzt im nächsten Build (Rückmeldung vom 14.10.2026, Punkte 22–30)

22. **Diagnose während des Spiels erreichbar:** Bisher gibt es „Diagnose senden“ nur auf der
    Startseite – mit Verbindung kommt man nicht hin, daher fehlten „An den Spielleiter senden“
    und „In GitHub ablegen“. Neu: Menü ⋮ beim Spieler (obere Leiste) und beim Spielleiter.
    Der Spielleiter lädt eigene **und** eingesammelte Berichte hoch; nicht hochgeladene Berichte
    werden auf dem Gerät zwischengespeichert (gehen beim Verlassen nicht verloren).
23. **Statistik zurück ins Repository:** Der Spielleiter lädt die Quiz-Statistik (`.buzzstats`)
    mit seinem Zugang hoch; Claude wertet sie aus und passt die Fragenpakete an (Rückfrage offen).
24. **Quiz-Vorlagen getrennt von Buzzer-Profilen:** eigene Einrichtungs-Menüs; vor dem Quiz wird
    eine Quiz-Vorlage gewählt (Rückfrage offen). Vorlagen separat anlegen, senden, exportieren.
25. **Prüfung durch den Spielleiter:** Er sieht bei der Auflösung die Erklärung und kann selbst
    eine Prüfung starten. Solange er prüft, sehen die Spieler unten ein Laufband „Bitte warte
    kurz – die Antworten werden überprüft“.
26. **Obere Leiste auf schmalen Handys (A53):** Ton, Brief und Menü stehen immer rechts fest;
    die Emoticons nutzen den restlichen Platz und lassen sich seitlich wischen.
27. **Verbindung bleibt im Hintergrund:** Während eine Gruppe offen ist, läuft ein Vordergrund-
    Dienst mit Benachrichtigung „Buzzer-Runde läuft“, damit Android die Verbindung nicht nach
    einigen Minuten kappt. Hinweis auf Samsung-Akkuoptimierung, falls es trotzdem passiert.
28. **Pause per Knopf:** sichtbarer Pause-Knopf beim Spielleiter (statt nur im Menü).
29. **„Get Ready“:** Der Spielleiter fragt „Bereit?“ ab; bei den Spielern erscheint ein Fenster
    mit „Bereit!“. Der Spielleiter sieht, wer bestätigt hat; sind alle bereit, erscheint
    „Go!“ mit Klang. Er kann die nächste Runde/Frage auch ohne „Go!“ starten.
30. **Einstellungen nicht bei jedem Tipp speichern:** +/− in der Quiz-Einrichtung erst nach
    kurzer Ruhe übernehmen (heute 8 Protokollzeilen in 3 Sekunden).

## Erledigt in Build 18

31. ✅ **Challenge-Ablauf:** Das Laufband „Bitte warte kurz – die Antworten werden überprüft“ startet
    automatisch, sobald ein Spieler eine Challenge sendet oder der Spielleiter „Antwort prüfen“
    öffnet, und endet mit seiner Entscheidung. Nach einer Entscheidung startet „Antwort prüfen“
    keine Prüfung mehr nebenbei (im Test lief das Laufband sonst bis zur nächsten Frage).
32. ✅ **Ruhigeres Protokoll beim Beitritt:** „Runde beendet“/„Spielart: noch offen“ beim Wählen der
    Spielart nicht mehr als eigene Ereignisse beim Spieler protokollieren.
33. ✅ **Testspiel-Schalter:** Im Menü ⋮ des Spielleiters „Testspiel – nicht in die Statistik“.
    Solange er an ist, zählen Antworten, „gesehen“, Challenges und Entscheidungen nicht in die
    Quiz-Statistik (Fragen werden also beim nächsten echten Spiel wieder als ungespielt gewählt).
    Anzeige „Testspiel“ in der Kopfzeile beim Spielleiter; wird beim Start einer neuen Runde
    nicht automatisch zurückgesetzt, aber beim Neustart der App.
34. ✅ **Diagnose lokal ablegen:** Jede Diagnose (im Spiel und außerhalb, z. B. vom Startbildschirm
    oder nach einem Verbindungsfehler) kann auf dem Gerät gespeichert werden. Liste
    „Gespeicherte Diagnosen“ unter „Über & Diagnose“ mit Ansehen, Teilen, Löschen.
    Wer ein gültiges Repository-Token hat, lädt sie stattdessen direkt hoch (bei Fehler oder
    ohne Internet bleiben sie lokal liegen und werden später erneut angeboten).
35. ✅ **Gespeicherte Diagnosen beim Beitritt einsammeln:** Hat ein Spieler gespeicherte Diagnosen
    und der Spielleiter ein gültiges Token, fragt der Spieler beim Verbindungsaufbau „Der
    Spielleiter kann N gespeicherte Diagnose(n) in sein Diagnose-Repository übernehmen.
    Senden?“ (Ja / Nein / Später). Übertragung wie bisher in Stücken; erst nach Bestätigung
    durch den Spielleiter („im Repository abgelegt“) werden sie beim Spieler gelöscht.
    Spielleiter ohne Token fragen nicht.

## Erledigt in Build 19

36. ✅ **Richtige Antwort beim Spielleiter hervorheben**:
    In der Auflösung ist die richtige Antwort eine grüne Karte mit hellem Rahmen, Haken und
    größerer Schrift; falsche Antworten bleiben gedämpft.
37. ✅ **Spielverlauf am Ende halten:** Die Animation „Spielverlauf“ bleibt nach dem Durchlauf
    3 Sekunden auf dem Endstand stehen (Musik endet mit der Animation), erst dann schließt sie –
    bei allen Spielern und beim Spielleiter.
38. ✅ **Knopf für den Spielverlauf:** Der Spielleiter bekommt in der Kopfzeile neben dem Pause-Knopf
    einen eigenen Knopf mit 🐇🐢 (Hase und Schildkröte), der den Spielverlauf für alle startet.
    Aktiv, sobald es einen Verlauf gibt (wie bisher „Spielverlauf zeigen“ im Menü ⋮, das bleibt).
39. ✅ **Test-Statistik getrennt:** Im Testspiel werden die Ergebnisse (Antworten, übersprungene
    Fragen, Challenges, Entscheidungen) in einer eigenen Test-Statistik (`quiz-stats-test.json`)
    aufgezeichnet statt gar nicht. Sie beeinflusst weder Auswahl noch Schwierigkeit der Fragen.
    „Quiz-Statistik hochladen“ legt sie – falls vorhanden – zusätzlich als eigene Datei
    `statistik/JJJJ-MM-TT/Name-HH-MM-SS-TEST.buzzstats` ab (im JSON als Test gekennzeichnet).
    Nach erfolgreichem Hochladen wird die Test-Statistik auf dem Gerät geleert.
40. ✅ **Offene Challenge bei „Get Ready“ / „Nächste Frage“:** Ist noch eine Challenge oder Prüfung
    offen, fragt die App den Spielleiter zuerst: „Offene Challenge von Wally – Wertung bleibt?“
    (Wertung bleibt / Zurück zur Prüfung). Erst danach startet die Bereit-Abfrage bzw. die nächste
    Frage; das Laufband läuft nie mehr während der Bereit-Abfrage. (Test 14.10., Build 18: nach
    einer Challenge ohne Entscheidung lief das Laufband 50 s weiter.)
41. ✅ **Ersatzfrage:** Neue Challenge-Entscheidung „Ersatzfrage“: Die aktuelle Frage wird annulliert
    (Punkte/Zeiten zurück) und sofort durch eine neue Frage aus demselben Filter (Kategorien,
    Stufen, zuerst ungespielte) ersetzt. Die Nummerierung bleibt („Frage 7/20“ wird neu
    gestellt), die Gesamtzahl ändert sich nicht. Gibt es keine passende Frage mehr, ist der Knopf
    ausgegraut („keine Ersatzfrage verfügbar“). In der Statistik zählt die ersetzte Frage als
    annulliert.
42. ✅ **Wertung bei Entscheidungen sicher korrigieren** (Prüfung des Codes am 14.10.):
    - Annullieren, Korrigieren und Sperren nehmen Punkte bzw. Zeiten der Frage schon zurück und
      rechnen bei „Korrigieren“ neu; „Sperren“ löscht die Gewinne wie „Annullieren“ und sperrt die
      Frage zusätzlich. Das wird für alle vier Wertungen (Schnellster, Jeder, Countdown, Battle)
      mit Tests abgesichert, inkl. Rangliste, Spielverlauf und der Anzeige bei den Spielern.
    - Lücke Countdown: Nach „Korrigieren“ beginnt der Countdown neu ab der ersten jetzt richtigen
      Antwort (bisher galt die Frist der ursprünglich ersten richtigen Antwort).
    - Lücke Statistik: Bisher bleiben die Antworten in der Fragen-Statistik mit der alten
      Bewertung stehen. Künftig zählen sie bei Annullieren/Sperren/Ersatzfrage nicht, und bei
      Korrigieren werden richtig/falsch neu gezählt – sonst lernt die Schwierigkeit Falsches.
43. ✅ **App-Symbol**: Lachender roter Buzzer auf goldenem
    Sockel vor Studio-Scheinwerferstrahlen im Quizshow-Blau, mit Funkeln in Gold und Cyan
    (adaptives Symbol, einfarbige Variante für Android 13+, Rundsymbol). Die Benachrichtigung
    „Buzzer-Runde läuft“ zeigt den Buzzer als kleines Symbol. Vorschau: `docs/app-symbol-vorschau.png`.
44. ✅ **Copyright**: Am Ende von „Hilfe & Spielregeln“
    und „Über & Diagnose“: „© 2026 Axel Pabich · Idee, Spielregeln und Tests: Axel Pabich ·
    Programmiert mit Claude (Anthropic)“.
45. ✅ **Dateiname mit Erstellungszeit:** Diagnoseberichte in GitHub heißen nach dem Zeitpunkt ihrer
    Erstellung (aus der Berichts-ID), nicht nach dem Hochladen – gespeicherte Berichte landen so
    chronologisch richtig (Test 14.10.: Wallys Bericht von 19:40:41 hieß `Wally-19-42-13.txt`).
    Auch der Datumsordner richtet sich nach der Erstellung.
46. ✅ **Countdown begrenzen und Hinweis:** In der Quiz-Einrichtung kann die Countdown-Zeit höchstens
    halb so lang wie die Antwortzeit sein (Regler entsprechend begrenzt; wird die Antwortzeit
    verkürzt, rückt der Countdown automatisch mit). Darunter ein Hinweis: „Nach der ersten
    richtigen Antwort bleiben den anderen noch x s.“ Ältere Profile/Vorlagen mit zu langem Countdown
    werden beim Laden begrenzt, mit kurzer Meldung „Countdown auf x s begrenzt“. (Test 14.10.: mit
    „Familienabend leicht“ griff der Countdown nie, alle bekamen ihre Restzeit.)

## Erledigt in Build 21

47. ✅ **Get Ready: nicht mehrfach auslösbar, danach automatisch weiter** (Test 14.10., Build 20:
    vor Frage 3 wurde „Get Ready“ fünfmal getippt, Wally musste dreimal bestätigen):
    - Während die Bereit-Abfrage läuft, zeigt der Knopf „Bereit: x / y …“ und löst keine neue
      Abfrage aus; abbrechen weiterhin über das Bereit-Feld oben.
    - Quiz: Nach „Alle bereit – Go!“ startet die nächste Frage automatisch (kurz nach dem
      Go-Klang, ca. 1,5 s). Kein Auto-Start, wenn der Spielleiter in der Zwischenzeit pausiert
      oder die Abfrage abgebrochen hat; die offene-Challenge-Rückfrage (Punkt 40) kommt wie bisher
      vor dem Start der Abfrage.
    - Buzzer: Dort liest der Spielleiter die Frage selbst vor – nach „Go!“ also kein
      automatisches „Buzzer frei“, nur der Knopf leuchtet auf.
48. ✅ **Spieleinstellungen im Quiz entrümpelt**:
    Im Quiz zeigt ⋮ „Spieleinstellungen“ nur noch, was dort wirkt – Tabs „Teilnehmer“
    (Master spielt mit, Kurznachrichten, Emoticons), „Wertung“ (Gleichstands-Schwelle für
    Schnellster/Battle, Rangliste, Neues Spiel) und „Klang“ (nur Quiz-Klänge: Beitritt, Tusch,
    Wah-wah, Go, Rundenende, Emoticon, Kurznachricht, Spielverlauf). Weg im Quiz: Startmodus,
    Zufallsverzögerung, Sudden Death, Reaktions-/Antwortzeit-Limits, Buzzer-Wertung, Profile-Tab
    (Vorlagen gibt es in der Quiz-Einrichtung). Eine empfangene Quiz-Vorlage beschreibt nur noch
    Quiz-Werte. Der Buzzer-Modus bleibt unverändert.

## Erledigt in Build 22

49. ✅ **Anfragen stören das Spiel nicht**: Beitritts- und
    Update-Anfragen sowie das Update-Angebot erscheinen beim Spielleiter erst in der nächsten Pause
    – also sobald die aktuelle Frage beantwortet bzw. aufgelöst ist (Quiz: Auflösung; Buzzer: „Runde
    beendet“ oder vor der Freigabe). Einsteigen mitten im Spiel ist ausdrücklich erlaubt; dem
    Neuen fehlen nur die Punkte der Fragen davor. Während der Frage steht in der Kopfzeile nur
    „⏳ Name wartet – Anfrage erscheint nach der Runde“. Bisher wurden Beitritte während der Runde
    sofort abgelehnt und Update-Anfragen sofort als Dialog angezeigt. Bricht der Spieler die
    Anfrage ab, verschwindet sie.
50. ✅ **Prüfcode beim Spielleiter groß**: In der
    Beitritts- und der Update-Anfrage steht der Prüfcode wie beim Spieler groß als „PRÜFCODE 1234“
    im Quizshow-Feld, darunter „Name sieht denselben Code. Vergleicht ihn – nimm nur bei gleichem
    Code an.“

## Erledigt in Build 23

51. ✅ **Ergebnis am Quiz-Ende als Spielverlauf:** Nach „Quiz beendet“ läuft der Spielverlauf
    automatisch auf allen Geräten ab – höchstens 15 Sekunden (bei vielen Fragen entsprechend
    schneller), danach bleibt das Ergebnis wie bisher statisch stehen.
52. ✅ **Splash-Screen beim App-Start (3 s, zufällig wechselnd)** – Entwurf: `docs/animationen-entwurf.html`
    (Vorschau https://claude.ai/artifact/4DcvoHvTA6WC6mw3tJ4Bb2). Bei jedem Start zufällig eine von:
    A „Hüpfer“ (Maskottchen fällt herein und hüpft, Buchstaben springen auf; Ton: Boing + tiefer
    Quizshow-Buzzer), B „Vorhang auf“ (Vorhang, Scheinwerfer, Glühbirnen-Schild; Ton: Ding-Ding-
    Glocke), C „Hau drauf“ (flache Hand mit Kraftlinien haut auf den Buzzer, „BZZZT!“; Ton:
    schnarrender Elektro-Buzzer). Immer dabei: jubelnde Mitspieler mit Handys, die Konfetti werfen,
    plus Jubel-Geräusch. Tippen überspringt; Ton folgt der Ton-Einstellung der App.
53. ✅ **Spielstart-Animation (3 s, zufällig wechselnd, auf allen Geräten gleich)** – Rennszenen mit
    Mitspielern, Hase und Schildkröte: 1 „Die Fabel“ (Hase schläft ein, Schildkröte zieht vorbei,
    „LOS!“, Startpfiff), 2 „Verfolgungsjagd“ (Kette unten hin, oben zurück, „QUIZ!“, Wuuusch),
    3 „Startschuss“ („Auf die Plätze … Fertig …“, Staubwolke, Schildkröte: „Ich komm ja schon!“,
    Startschuss). Der Spielleiter wählt die Variante zufällig und schickt sie mit, damit alle
    dieselbe sehen. Läuft beim Start eines Quiz und bei „Neues Spiel“, nicht vor jeder Runde.

## Erledigt in Build 25

54. ✅ **Start-Animationen im Diagnose-Protokoll**: Das
    Protokoll vermerkt „Splash: A Hüpfer / B Vorhang auf / C Hau drauf“ (mit „ohne Ton“, falls der
    Ton aus ist) und „Splash übersprungen“, auf jedem Gerät außerdem „Spielstart-Animation:
    1 Die Fabel / 2 Verfolgungsjagd / 3 Startschuss“. So lässt sich nachvollziehen, welche
    Variante wann lief.
55. ✅ **Get Ready auch für den Spielleiter**: Bei „Get
    Ready“ bekommt auch der Spielleiter selbst die Abfrage „BEREIT?“ (mit „Abfrage abbrechen“).
    „Alle bereit – Go!“ und damit der automatische Start im Quiz kommen erst, wenn auch er bestätigt
    hat – er wird nicht mehr überrascht. In der Bereit-Liste steht er als „Name (Spielleiter)“.
56. ✅ **Bereit-Abfrage automatisch**: In der Quiz-Einrichtung
    der Schalter „Bereit-Abfrage automatisch“ mit wählbarer Zeit (5–120 s, Standard 10 s) „… nach der
    Auflösung“. Dann startet „BEREIT?“ von selbst; sind alle (auch der Spielleiter) bereit, kommt die
    nächste Frage. Bei offener Challenge, Prüfung oder Pause wartet die Automatik; nach der letzten
    Frage gibt es keine. Gehört zur Quiz-Vorlage.

## Offen

57. **Start-Animationen laufen komplett und blenden aus** (in Build 26):
    Splash-Screen und Rennszene laufen jetzt über die Bildwiederholung statt über die
    Animations-Skalierung des Systems (bei „Animationen aus“/verkürzt war sie sofort vorbei) und
    starten erst mit dem ersten angezeigten Bild. Nach 3 s bleibt das Schlussbild 0,8 s stehen und
    blendet in 0,6 s aus; erst dann erscheint der Startbildschirm bzw. die erste Quizfrage.
58. **Neues „Wah-wah-wah-waaah“** (in Build 26): gestopfte Posaune –
    drei absteigende „Wah“ und ein langes, wackelndes „Waaah“, das am Ende abrutscht (Hörprobe
    `docs/wah-wah-neu.wav`).
59. **Spielleitung übergeben und übernehmen** (in Build 26;
    Entscheidungen vom 15.10.2026: Punkte bleiben, das Quiz läuft weiter, freiwillige Übergabe
    über ⋮): Der Spielleiter schickt in jeder Pause den Spielstand an alle. ⋮ „Spielleitung übergeben
    an …“ macht einen Spieler zum Spielleiter, der bisherige spielt weiter mit. „Spiel verlassen“
    fragt „Spieler übernehmen“ oder „Für alle beenden“. Geht der Spielleiter oder bricht die
    Verbindung ab, sehen alle „Wer übernimmt?“ mit „Ich übernehme“ und 1 Minute Countdown; die
    anderen verbinden sich automatisch mit dem Nachfolger (ohne Prüfcode-Rückfrage). Punkte, Runde,
    Einstellungen und die Quiz-Fragen gehen mit; eine unterbrochene Frage wird wiederholt. Ohne
    Nachfolger endet das Spiel für alle. Spezifikation Abschnitt 48. Im Test prüfen: Übergabe per ⋮,
    Verlassen mit „Spieler übernehmen“, App des Spielleiters beenden (Verbindungsabbruch).
60. **Verteilung über Google Play** (zurückgestellt, 15.10.2026): Play Console „Interne Tests“
    (bis 100 Tester, nicht öffentlich) statt APK von der Release-Seite – keine Play-Protect-Scans
    mehr, automatische Updates. Braucht Entwicklerkonto (einmalig 25 US-$), eigenen Signaturschlüssel
    als GitHub-Secret und ein App-Bundle (AAB) im Build. Bis dahin: Play Protect → Zahnrad →
    „Apps mit Play Protect scannen“ nur fürs Update kurz aus.
61. **Feinschliff Übernahme** (in Build 27): Der Spielverlauf beginnt
    nach einer Übernahme mit den übernommenen Punkten statt bei 0; doppelte Protokollzeile
    „Spielleitung an … übergeben“ entfernt. Wird die App beim Spielleiter weggewischt, dürfen die
    Spieler übernehmen (vorher: Spiel für alle beendet).
62. **Übernahme bei Funkabbruch robuster** (in Build 27) – Test vom
    15.10.2026, Axel schaltet Bluetooth und WLAN aus:
    - Die Spieler merkten den Abbruch erst nach ~60 s (Nearby). Jetzt pingt der Spielleiter in jeder
      Phase alle 2 s; nach 12 s Stille startet die Übernahme.
    - Simone hatte übernommen, sah dann aber Axels alte Gruppe und gab an ihn ab (Fehler: der
      Vorgänger zählte beim Vorrang mit). Peter hing in der Verbindung zu Simone, beide endeten
      nach 1 Minute mit „Niemand hat übernommen“. Jetzt zählt der Vorgänger nicht mehr.
    - Axels Gruppe war nach dem Funkabbruch nicht mehr erreichbar (STATUS_RADIO_ERROR). Verliert
      der Spielleiter alle Spieler fast gleichzeitig, kündigt er die Gruppe neu an und zeigt einen
      Hinweis.
    - Peters Tablet lief ab 20:28 wieder mit Build 22 – bitte aktualisieren.
63. **Diagnosen abrufen und prüfen** (in Build 27): ⋮ „Diagnosen der
    Spieler abrufen“; Spieler entscheiden mit Systemdaten-Haken, „Bericht ansehen“ und „Nicht erneut
    fragen (bis zum nächsten Build)“. „Bericht ansehen“ auch in „Diagnose senden“. Abschnitt 49.
64. **Installation hängt auf dem Galaxy Tab S6 (aus dem Spiel heraus)** (in Build 28): Vor der Installation wird das Spiel verlassen (Verbindung und Hintergrund-Dienst
    aus), die APK geht über den Paket-Installer von Android (Sitzung) statt über den Dialog; jedes
    Ergebnis und der Build-Wechsel beim nächsten Start stehen im Protokoll. Aufgefallen: Peters
    Tablet startete am 15.10. um 20:28 wieder mit Build 22 statt 26 – die neue Protokollzeile
    „ZURÜCKGESETZT“ macht so etwas künftig sichtbar.
65. **„Später“ installieren** (in Build 28): Die Version bleibt bereit (auch nach Neustart) und
    wird über „Build N installieren“ auf dem Startbildschirm, in „Über & Diagnose“, im ⋮-Menü des
    Spielleiters und nach dem Verlassen eines Spiels wieder angeboten. Vorher bedeutete „Später“
    praktisch „nie“ – die Datei lag unerreichbar im Cache.
67. **Direkter Download-Link** (in Build 29): In „Über & Diagnose“ beim
    Update-Bereich „Direkter Download im Browser (buzzer.apk)“ plus kopierbarer Link – Ausweg, wenn
    die Installation aus der App hängt (Datei dann über „Eigene Dateien“ öffnen).
82. **Neue Stufe „Ingomania“, Stufen und Kategorien aus der Fragendatenbank** (Wunsch vom
    24.10.2026; umgesetzt, kommt mit dem nächsten Build): vierte, schwerste Stufe mit je 20 Fragen
    pro Kategorie (200 Fragen; Pakete jetzt 84/84/84/84/84/84/82/82/82/82), nicht vorgewählt. Die
    Stufen stehen jetzt in `quiz/index.json` und werden dort verwaltet; die App liest sie aus dem
    eingebauten oder per „Fragen aktualisieren“ geladenen Index. Neue Stufen/Pakete erscheinen ohne
    Codeänderung. Spezifikation Abschnitt 54; Tests `IngomaniaTest`, `QuestionPackFilesTest`.
100. **Fragendatenbank angleichen, dann Schluss** (Wunsch vom 30.10.2026): Pro Build nur noch je eine neue
    Frage in den Zellen (Kategorie × Stufe), die unter der größten liegen (Ziel 29). Größte Lücke: 10 Fragen
    (Einzelhandel und Büroalltag „schwer“) – also noch etwa 10 Builds. Danach Axel Bescheid geben und keine
    Fragen mehr automatisch erzeugen. Regel in `CLAUDE.md`.
99. **Pause mitten in der Quizfrage** (Wunsch vom 30.10.2026; kommt mit dem nächsten Build): Die Zeit steht
    (Countdown stoppt), Frage und Antworten sind auch beim Spielleiter verdeckt. Er wählt: Frage
    weiterlaufen lassen, Frage neu stellen (dieselbe Frage von vorn, neue Reihenfolge, Antworten verfallen),
    Frage überspringen, Runde abbrechen (annulliert, ohne Punkte und Statistik; danach „Bereit?“) oder
    Challenge ausrufen (Auflösung, dann Prüfung mit der üblichen Entscheidung). Tests in `PauseInRoundTest`.
98. **Regel-Bestätigung beim zweiten Quiz übersprungen** (Diagnose Build 40 vom 25.10.2026, 12:59): Nach dem
    ersten Quiz der Sitzung galt das Spiel als „begonnen“, deshalb zeigte das zweite Quiz die Regeln nur
    zur Information und startete ohne OK. Behoben: Jedes Quiz fragt die Bestätigung neu ab (kommt mit dem
    nächsten Build).
97. **Beitritt nach App-Neustart (Fehler 8003)** (Diagnose vom 25.10.2026, Tablett S7; in Build 39): Meldet
    Nearby `STATUS_ALREADY_CONNECTED_TO_ENDPOINT`, trennt die App die hängende alte Verbindung und
    wiederholt den Beitritt bis zu 3-mal selbst (Pause 1,2 s).
96. **Neue Fragen für Build 39**: je eine pro Kategorie und Stufe (13 × 4 = 52), „Aktuelles 2026“ per
    Websuche geprüft (Commonwealth Games, Deutscher Buchpreis, Rad-WM Zeitfahren). Eisbär-Frage
    (`besser-0002`, Version 2) bleibt, Erklärung ergänzt: Haare farblos, durchsichtig, hohl; Haut schwarz;
    „Fellfarbe“ wäre weiß – deshalb wird gezielt nach den Haaren gefragt.
95. **Zufallsverteilung der Antwortfelder abschaltbar** (Wunsch vom 25.10.2026; kommt mit dem nächsten
    Build): Schalter „Antworten für jeden Spieler anders mischen“ (Quiz einrichten, Standard an). Aus:
    alle sehen dieselbe Reihenfolge – Chancengleichheit bei Zeitwertung.
94. **Wartezeit nach der Länge der Frage, Lesetest** (25.10.2026): Die Pause zwischen Frage und Antworten
    richtet sich nach der Zeichenzahl und dem langsamsten gemeldeten Lesetempo (Schalter „Lesezeit der
    Frage anpassen“). Jeder Spieler kann im Menü „Lesetempo messen“ (5 Beispielzeilen) sein Tempo ermitteln;
    es geht automatisch an den Spielleiter.
93. **Vier Antwortfelder untereinander** (25.10.2026): volle Breite, Buchstabe links, größere Schrift.
92. **Chat bleibt sichtbar, während der Runde stumm** (25.10.2026): Bei genug Platz zeigt die Spieleransicht
    den Chat auch während der Frage; neue Nachrichten erscheinen erst nach der eigenen Antwort.
91. **Zurück-Kette im Spielleiter-Menü** (25.10.2026; Annahme): Quiz einrichten → Quiz-Vorlage → Spielart
    wählen → „Spiel verlassen?“.
90. **Spielende/Abbruch: Endergebnis für Spieler** (25.10.2026): Alle sehen das Endergebnis (bei Abbruch mit
    Hinweis); „Weiter zum nächsten Quiz“ führt zur Wartesicht „Der Spielleiter richtet das nächste Quiz ein“.
89. **Profil des laufenden Spiels kopieren** (25.10.2026): Spieler-Menü → „Profil dieses Spiels kopieren“
    (Nachricht `PROFILE_REQ`); danach der übliche Dialog zum Speichern.
88. **Spielregeln bestätigen** (25.10.2026): Vor Spielbeginn zeigen alle Geräte die Regeln groß mit
    „OK – ich bin bereit“; erst wenn alle (und der Spielleiter) bestätigt haben, beginnt die Rennszene und
    die erste Frage. Spielleiter kann „Sofort beginnen“. Schalter „Spielregeln vor Spielbeginn bestätigen“.
    Ältere Apps (vor Build 39) werden nicht abgewartet.
87. **Kategorienauswahl als Popup** (25.10.2026): Eine Zeile zeigt die Auswahl, Tipp öffnet das Fenster
    (Alle/Keine, mindestens eine bleibt gewählt).
86. **Neue Kategorien** (25.10.2026): „Die 80er, 90er und 00er Jahre“, „Bienchen & Blümchen“,
    „Nepper, Schlepper, Bauernfänger“ – je 20 Fragen pro Stufe (insgesamt 13 Kategorien).
85. **Pause in der Runde, Chat jederzeit lesbar** (Wunsch vom 24.10.2026; umgesetzt, kommt mit dem
    nächsten Build): Der Pause-Knopf geht jetzt auch mitten in der Runde – Quizfrage: Zeit steht und läuft
    mit der Restzeit weiter; Buzzer vor der Entscheidung: Runde wird abgebrochen und neu freigegeben;
    Antwortzeit: steht. Der Spielleiter kann den Chat jederzeit lesen (Punkt am ✉, neue Nachrichten
    als Zeile in der Runde, Verlauf im Fenster); Senden bleibt auf die Pausen beschränkt. Mit älteren
    Spieler-Apps funktioniert die Verschiebung der Quizzeit nicht. Spezifikation Abschnitt 57,
    Tests `PauseInRoundTest`. Die Oberfläche ist noch am Gerät zu prüfen.
84. **Major Release und Rollback** (Wunsch vom 24.10.2026; in Build 38 = Version 2.0.38):
    Datei `VERSION` (jetzt `2.0`) bestimmt Haupt.Neben, Version also `2.0.<Nr>`. Der Workflow kann
    mit `major = true` ein Major Release veröffentlichen und mit `rollback_to = build-<Nr>` den Quelltext
    eines früheren Standes als neuen Build mit höherer Nummer bauen (Android erlaubt keinen Downgrade).
    Jeder Build bekommt Seite und Tag `build-<Nr>` (APK-Seiten der letzten 10 bleiben). Tags für die
    schon gebauten Stände ließen sich hier nicht pushen – dafür Commit-IDs: Build 35 `90456ce`,
    36 `ee3a1a9`, 37 `743258e`. Der erste Lauf mit dem neuen Workflow ist der Test – Fehler im Workflow
    zeigen sich erst dort. Spezifikation Abschnitt 56, Release-Notizen `docs/release/2.0.md`.
83. **Spielleiter-Ansicht näher an der der Spieler** (Wunsch vom 24.10.2026; in Build 38): Während einer Quizfrage große Frage mit vier Antwortfeldern wie bei den
    Spielern (Titel der Seite entfällt, Wischzeile „wer hat geantwortet“, Knöpfe darunter), in der
    Auflösung gleiche Kopfzeile und Ergebnis-Banner, im Buzzer-Spiel der Buzzer des Masters auf
    halber Höhe. Spezifikation Abschnitt 55. Noch am Gerät zu prüfen (Handy und Tablett).
81. **Selbsttest vom 24.10.2026 (Axel Build 36, Tablett S7 und Peter noch Build 35)**
    (in Build 37): 21 von 22 – „Buzzer-Runde: alle buzzern“ schlug
    nur fehl, weil die Spieler zufällig gleichzeitig buzzerten (3 ms Abstand): Gleichstand, Sudden
    Death, Entscheidung nach 18 s statt der erlaubten 15 s. Spiel korrekt, Test wartet jetzt bis
    zu 40 s. Funk-Laufzeiten an dem Tag 692 ms (Tablett S7) und 805 ms (Peter), sonst ca. 250 ms.
80. **Aus dem Test mit Build 36 (19.10.2026, 21:43–21:55, Axel und Simone)** (in Build 37):
    - Selbsttest 21 von 21, Quiz-Teil ohne Wartezeit (Punkt 78 bestätigt).
    - 21:54: Simone 14 s ohne Funk – nach 15 s getrennt, 2 s später ohne Prüfcode zurück, Punkte
      behalten (Punkte 76 und 77 bestätigt).
    - 21:45: Simone kam zurück, während ihre alte Verbindung noch hing; der Spielleiter trennte die
      alte sofort – danach kam über die neue nichts mehr bei ihr an (sie sendete noch), sie verpasste
      die Fragen 4–6. Jetzt wird die alte Verbindung nur aus dem Spiel genommen, nicht getrennt.
    - Antwortfelder (Punkt 79) passen auf dem Tablet; Handy als Spielleiter (kompakte Ansicht)
      noch zu prüfen.
79. **Antworten immer vollständig im Feld** (Wunsch vom 19.10.2026; in Build 36):
    Lange Antworten wurden abgeschnitten (kompakt feste 78 dp Höhe). Jetzt
    werden alle vier Antworten in der Feldbreite gemessen: erst kleinere Schrift, dann alle vier
    Felder gleich höher.
77. **Stille Spieler schneller erkennen** (in Build 36): Im Test
    am 17.10. um 12:33 meldete der Funk Peters Abbruch erst nach 47 s. Jetzt trennt der Spielleiter
    einen Spieler nach 15 s ohne Antwort auf den Herzschlag selbst (nicht während einer
    Dateiübertragung). Spezifikation Abschnitt 53, Tests in `RejoinTest`.
78. **Selbsttest schneller** (in Build 36): Die Quiz-Frage im
    Live-Test wartete 18 s auf die Antwort des Spielleiters; jetzt antwortet er, sobald alle Spieler
    geantwortet haben.
    Test vom 17.10.: Selbsttest 23 von 23 mit Simone, Peter und Tablett S7; Quiz mit 10 Fragen
    ohne Störung (die vierfache Challenge bei Frage 2 war absichtlich und hat funktioniert).
76. **Wiedereinstieg nach kurzem Funkabbruch** (Wunsch vom 17.10.2026; in Build 36):
    Bisher wartete ein zurückkehrender Spieler bis zum Rundenende, und der
    Spielleiter musste den Prüfcode bestätigen, wenn seine alte Verbindung noch hing (Simone,
    16.10. 14:02) oder der Abbruch länger als 3 Minuten her war (Peter, 14:03). Jetzt kommen
    Rückkehrer sofort und ohne Rückfrage hinein; eine offene Quizfrage bekommen sie mit der
    Restzeit und können noch antworten. Spezifikation Abschnitt 53, Tests in `RejoinTest`.
75. **Selbsttest** (in Build 35): ⋮ „Selbsttest“ für den Spielleiter
    mit Token – 11 Prüfungen der Spiellogik auf dem Gerät, Fragenpakete, Release-Seite, optional
    Live-Test mit den Spielern im Testmodus (Buzzer, Bereit, Quiz, Diagnosen); Bericht ins
    Repository. Spezifikation Abschnitt 52.
74. **Aus dem Test mit Build 34 (16.10.2026, 13:45–14:08)** (umgesetzt, kommt mit dem nächsten Build):
    - Paket-Installer erneut abgelehnt (Axel: „Self update is blocked“ trotz Rückfrage, dann
      „Install not allowed“; Peter: „Package Verification Result“); der Ausweg über den Dialog
      griff. Jetzt kommt der klassische Installer-Dialog zuerst.
    - Gespaltenes Spiel: Axel war 13:58 kurz weg, Peter übernahm nach 7 s, Axel tauchte 20 s später
      wieder auf, Simone ging zu Axel, Peter blieb allein Spielleiter, bis er um 14:03 von Hand
      wechselte. Jetzt gibt ein Nachfolger ohne Spieler an den zurückgekehrten Vorgänger ab.
    - Simone kam neu, während ihre alte Verbindung noch hing – kurz zwei Einträge „Simone“. Jetzt
      wird die alte Verbindung ersetzt.
    Hinweis Axel: Auf seinem Tablet muss „Installation aus fremden Quellen“ regelmäßig neu freigegeben
    werden – gewollt, so bleibt es (erklärt die Ablehnungen „Self update is blocked“).
    Bestätigt: Herzschlag 12–13 s, „Axel ist wieder da“, Punkte beim Wiederbeitritt,
    Diagnosen automatisch (Einstellung über Builds hinweg), Spielregeln, Spielverlauf.

73. **Spielverlauf und „Hau drauf“** (in Build 34): Erstes Tippen →
    Endstand, zweites Tippen oder Weitermachen des Spielleiters schließt; Hand trifft im Splash
    „Hau drauf“ die Kuppel (vorher Höhe aus Bildschirmhöhe, Hand aus Breite – auf Tablets verfehlt).
    Siegerfanfare: „gut“ (Axel).

Bestätigt im Test (16.10.2026, Build 33): Challenge aus der „Bereit?“-Abfrage (Peter, Frage 7 →
Ersatzfrage); Diagnose-Einstellung „immer senden“ überlebt den Build-Wechsel (Peter, Simone);
Spielregeln zum Quiz-Start; keine Fehler. Installation von Build 33 lief noch über den alten Code
von Build 32: Axel und Peter je erster Versuch abgelehnt, zweiter mit Bestätigung erfolgreich –
die Korrektur greift ab dem Update auf Build 34.

72. **Wünsche und Test vom 16.10.2026, Build 32** (in Build 33):
    - „Bereit?“ bietet „Letzte Frage anfechten“.
    - Diagnose-Einstellung „Nicht erneut fragen“ gilt über Builds hinweg; „Wieder fragen“ in den
      Einstellungen.
    - Installation: Der Paket-Installer lehnte mehrfach ab (Axel: „Self update is blocked by unknown
      source package“, Peters Tablet: „Package Verification Result“), erst ein späterer Versuch klappte.
      Jetzt ohne „Update ohne Rückfrage“ und mit automatischem Ausweg über den Installer-Dialog.
    - Spielregeln kamen bei jeder Rückkehr erneut – jetzt nur für Neue.
    Bestätigt: Spielregeln, Avatare, Rückkehr zum bisherigen Spielleiter („Axel ist wieder da“),
    Punkte beim Wiederbeitritt, Übergaben mit mitgeschickten Fragen, Herzschlag 13 s,
    Build-Wechsel im Protokoll, Start-Animationen wechseln.
71. **Fragendatenbank wächst mit jedem Build** (Daueraufgabe ab Build 33): je Kategorie eine neue
    Frage pro Schwierigkeitsstufe (10 Pakete × 3 = 30 Fragen je Build). Festgehalten in `CLAUDE.md`.
    Build 33: +30 Fragen (Pakete jetzt 25/25/25/25/25/25/23/23/23/23 Fragen); „Aktuelles 2026“
    per Websuche geprüft: Özdemir Ministerpräsident BW, Super Bowl LX Seahawks, Australian Open Alcaraz.
    Build 34: +30 Fragen (28/28/28/28/28/28/26/26/26/26); „Aktuelles 2026“ per Websuche: Audi in
    der Formel 1, Chemie-Nobelpreis (Kagan, Soai), Wirtschaftsnobelpreis (Hall, Woodford).
    Build 35: +30 Fragen (31/31/31/31/31/31/29/29/29/29); „Aktuelles 2026“ per Websuche: French
    Open (Zverev, Andreeva), Darts-WM (Littler).
    Build 36: +30 Fragen (34/34/34/34/34/34/32/32/32/32); „Aktuelles 2026“ per Websuche:
    Basketball-WM der Frauen in Berlin, US Open (Zverev gegen Shelton, Rybakina).
    Build 37: auf Wunsch (24.10.2026) je 10 neue Fragen pro Kategorie und Stufe = +300 Fragen
    (64/64/64/64/64/64/62/62/62/62). Aktuelles 2026: 30 Fragen per Websuche mit konkreten
    Quellen (Eishockey-WM, Masters, NBA, Stanley Cup, Cannes, Berlinale, Oscars, Landtagswahlen
    u. a.); NBA, Cannes, Stanley Cup und Dschungelkönig zusätzlich selbst gegengeprüft. Beim
    Durchsehen 11 Fragen wegen Dubletten zu anderen Paketen oder unsicherer Fakten ersetzt.
    Build 38 (Major Release 2.0): +40 Fragen, je Kategorie eine pro Stufe, jetzt vier Stufen
    (88/88/88/88/88/88/86/86/86/86); „Aktuelles 2026“ per Websuche: Leichtathletik-EM Birmingham
    (Stadt, 21 deutsche Medaillen), Oscar-Regie (Paul Thomas Anderson), Vuelta-Sieger (Enric Mas).
70. **Wünsche vom 16.10.2026** (in Build 32): Spielregeln und gespielte
    Kategorien beim Beitritt und zu Spielbeginn; Avatar-Emoticon in den Einstellungen; Siegerfanfare
    als Abschluss des Spielverlaufs; Start-Animationen nie zweimal hintereinander gleich (Rennszene
    auf allen Geräten gleich); neue Klänge der Animationen (Hörproben in `docs/klaenge/`).
69. **Aus dem Test vom 15./16.10.2026 (Build 28, alle drei Geräte)** (alle vier in Build 31 umgesetzt):
    - Fehler: Nach „Fragen aktualisieren“ beim Spielleiter fehlten der Nachfolgerin 12 von 20
      Fragen (neue Pakete noch nicht geladen) – das Quiz schrumpfte auf 8 Fragen. Vorschlag: die
      Fragen selbst im Spielstand mitschicken.
    - 00:14: Spielleiter Axel kurz weg, niemand tippte „Ich übernehme“ – Spielende nach 1 Minute,
      obwohl Axels Gruppe nach 30 s wieder auftauchte. Vorschlag: Taucht der bisherige Spielleiter
      wieder auf und hat noch niemand übernommen, verbinden sich die Spieler wieder mit ihm (er nimmt
      sie ohne Rückfrage und mit ihren Punkten auf).
    - Wer zwischen den Runden die Verbindung verliert und neu beitritt, verliert seine Punkte –
      sie sollten beim Wiederbeitritt (gleicher Name) zurückkommen.
    - Kosmetik: Beim Wegwischen der App erschien „Alle Spieler getrennt“ dreimal im Protokoll.
    Bestätigt: Peters Tablet läuft mit Build 28; Wegwischen beim Spielleiter → Übernahme (Simone
    nach 4 s); Ketten-Übergaben Simone → Axel → Peter → Axel mit richtigen Punkten; automatischer
    Wiederbeitritt in der Pause; Diagnosen abrufen inkl. „Nicht erneut fragen“; neue Fragenpakete
    über „Fragen aktualisieren“ (3 Pakete geladen); Spieler-Erkennung nach 13–14 s.
68. **Neue Fragenpakete** (15.10.2026): „Einzelhandel“, „Büroalltag“ und „Besserwisser“ mit je
    20 Fragen (Version 1). Über „Fragen aktualisieren“ erst nach dem Merge in `master`, sonst mit dem
    nächsten Build.
66. **Vorschläge aus dem Test vom 15.10.2026, 22:04–22:18 (Build 27)** (in Build 29 umgesetzt, bis auf
    das Tablet):
    - Wer nach einer Übernahme mitten in einer Frage zurückkommt, wartet bis zur Pause und wird
      dann automatisch aufgenommen (vorher: von Hand).
    - Neuer Spielleiter startete die nächste Frage, bevor alle zurück waren (Peter und Axel
      verpassten Fragen): jetzt Hinweis „Noch nicht zurück: …“, solange die Übernahme läuft.
    - „Diagnosen abrufen“: Spieler mit Build < 27 können nicht antworten – wird jetzt mit Namen
      angezeigt.
    - Offen: Peters Galaxy Tab S6 (Android 12) installiert Build 27 nicht, auch außerhalb des Spiels
      (21:53 Release-Seite, 21:55 und 21:58 von Simone; danach startet weiter Build 26).


Bestätigt im Test (15.10.2026, Build 27): Herzschlag – Spieler mit Build 27 erkennen den
Funkabbruch nach 12–13 s; Übernahme nach Funkabbruch (Axel übernimmt von Simone, später Simone
von Axel), Vorgänger tritt dem Nachfolger bei, Punkte richtig übernommen; Übergabe per ⋮
(Simone → Axel); „Alle Spieler getrennt“-Hinweis; Diagnosen abrufen (Simone).

Bestätigt im Test (15.10.2026, Build 26): Übergabe per ⋮ (Simone → Axel) und „Spiel verlassen –
Spieler übernehmen“ (Axel geht, Simone übernimmt, Peter kommt automatisch zurück); Quiz lief mit
Frage 3/12 und den richtigen Punkten weiter. Noch zu testen: Verbindungsabbruch (Bluetooth aus bzw.
App beim Spielleiter beenden).

Bestätigt im Test (15.10.2026, Build 22): Prüfcode beim Spielleiter groß (Punkt 50); Beitritt
wartet bis zur Auflösung (Punkt 49, Peter und Wally). Verworfen: ewige Bestenliste mit Spieler-ID.

Bestätigt im Test (14.10.2026, Build 17): Hintergrund-Dienst hält die Verbindung auch bei
längerer Zeit im Hintergrund; Diagnose- und Statistik-Ablage, Get Ready, Pause, Vorlagen laufen.

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
