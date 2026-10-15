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

## Offen

(zurzeit nichts)

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
