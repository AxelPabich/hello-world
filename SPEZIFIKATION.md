# Buzzer-Runde – Spezifikation

Android-App, mit der mehrere Handys im selben Raum eine Quiz-Buzzer-Runde bilden.
Ein Gerät ist **Master** (Spielleiter), die anderen sind **Spieler**.
Der Master stellt die Fragen mündlich; die App regelt nur, wer in welcher
Reihenfolge antworten darf.

---

## 1. Verbindung

- Transport: **Google Nearby Connections**, Strategie `P2P_STAR`
  (Master in der Mitte, Spieler sternförmig verbunden).
- **Gruppe eröffnen:** Der Master startet `startAdvertising` mit dem Gruppennamen.
- **Gruppe suchen:** Spieler starten `startDiscovery` und sehen eine Liste der
  gefundenen Gruppen.
- **Beitreten:** Der Spieler wählt eine Gruppe und schickt eine Verbindungsanfrage
  mit seinem Namen. Beim Master erscheint der Dialog „*Name* möchte beitreten“
  mit **Annehmen / Ablehnen** und dem vierstelligen Prüfcode (siehe Abschnitt 28).
- Nachrichten an „alle“ verschickt der Master per `sendPayload` an die Liste aller
  verbundenen Spieler.
- Ein Beitritt ist nur zwischen zwei Runden möglich (Zustand *Bereit*).
- Bricht die Verbindung eines Spielers während einer Runde ab, zählt er für
  diese Runde als grau (ausgeschieden).

---

## 2. Display-Farben der Spieler

| Farbe | Bedeutung |
|---|---|
| **Schwarz** | Bereit, keine Runde aktiv. Tippen hat keine Wirkung. |
| **Blau** | Nur Startmodus A: Finger liegt auf, warte auf die anderen bzw. auf die Freigabe. |
| **Gelb** | Buzzer ist freigegeben – jetzt reagieren. |
| **Grün** | Du bist dran und darfst antworten. |
| **Rot + Nummer** | Du hast gebuzzert; die Nummer ist dein Platz in der Reihenfolge. |
| **Pink** | Gleichstand – du musst ins Sudden Death. |
| **Grau** | Ausgeschieden für diese Runde (Frühstart, nicht gebuzzert, falsch beantwortet oder Verbindung verloren). |

---

## 3. Startmodi (vom Master wählbar)

### Modus A – Halten und Loslassen
1. Der Master tippt **„Buzzer scharf“**. Alle Displays sind schwarz mit dem
   Hinweis „Finger auflegen“.
2. Jeder Spieler legt einen Finger auf sein Display → Display **blau**.
   Hebt ein Spieler den Finger in dieser Phase wieder ab, wird er wieder schwarz;
   das ist noch kein Frühstart.
3. Sobald **alle** Spieler halten, startet eine **zufällige Verzögerung**
   (Bereich einstellbar, z. B. 1–4 s). Ab jetzt ist Loslassen ein **Frühstart → grau**.
4. Nach der Verzögerung werden alle Displays gleichzeitig **gelb**.
5. Es zählt der Zeitpunkt des **Loslassens**.

### Modus B – Tippen
1. Der Master tippt **„Buzzer frei“**. Danach läuft eine **zufällige Verzögerung**
   (Bereich einstellbar), während der die Displays schwarz bleiben.
2. Tippt ein Spieler während der Verzögerung, ist das ein **Frühstart → grau**.
3. Nach der Verzögerung werden alle Displays gleichzeitig **gelb**.
4. Es zählt der Zeitpunkt des **Antippens**.

In beiden Modi kennt niemand – auch nicht der Master – den genauen Zeitpunkt
der Freigabe; die Verzögerung würfelt die App erst beim Start.

---

## 4. Ablauf einer Runde

1. **Bereit** – alle schwarz. Der Master stellt die Frage mündlich.
2. **Freigabe** – je nach Startmodus (Abschnitt 3); Displays werden gelb.
3. **Buzzern** – jeder Druck (bzw. jedes Loslassen) wird mit Zeitstempel an den
   Master gemeldet. Der Spieler sieht sofort eine Quittung („gedrückt“, gedimmtes Gelb).
4. **Auswertung** – der Master wartet ein kurzes Sammelfenster ab
   (Abschnitt 6), bevor er Ergebnisse anzeigt.
5. **Ergebnis** – der Schnellste wird **grün**, alle anderen, die gebuzzert haben,
   werden **rot mit ihrer Platznummer**. Wer später noch buzzert, bekommt ebenfalls
   rot mit seiner Nummer (nach demselben Sammelfenster).
6. **Einfrieren** – sobald alle (nicht ausgeschiedenen) Spieler gebuzzert haben
   oder das **Zeitlimit** abgelaufen ist, werden keine Eingaben mehr angenommen.
   Wer bis dahin nicht gebuzzert hat (bzw. in Modus A noch hält), wird **grau**.
7. **Antworten** – der grüne Spieler antwortet mündlich. Der Master tippt:
   - **Richtig** → Punkte vergeben (Abschnitt 7), weiter mit Schritt 8.
   - **Falsch** → der grüne Spieler wird **grau**, der Spieler mit der
     **nächsthöheren Nummer** wird **grün**. Weiter mit Schritt 7.
     Gibt es keinen weiteren Spieler mehr, weiter mit Schritt 8.
8. **Runde beenden** – der Master tippt **„Nächste Runde“** → alle Displays schwarz.

---

## 5. Gleichstand und Sudden Death

- Liegen zwei oder mehr Reaktionszeiten innerhalb der **Gleichstands-Schwelle**
  (einstellbar, Vorschlag 20 ms), gelten sie als gleichzeitig.
- **Gleichstand um Platz 1:** Die betroffenen Spieler werden **pink** und spielen
  direkt nach dem Einfrieren der Runde ein **Sudden Death** nach denselben Regeln (gleicher Startmodus), aber mit
  einem **größeren Zufallsbereich** für die Verzögerung. Die Reihenfolge im
  Sudden Death bestimmt ihre Reihenfolge untereinander. Ein erneuter Gleichstand
  führt zu einem weiteren Sudden Death.
- **Gleichstand auf späteren Plätzen:** Diese Spieler bekommen zunächst
  dieselbe rote Nummer. Erst wenn der Master nach einer falschen Antwort diesen
  Platz freigibt, werden sie pink und spielen ihr Sudden Death aus.
- Die übrigen Spieler bleiben während des Sudden Death eingefroren.

---

## 6. Zeitsynchronisation und Fairness

1. **Uhrenabgleich:** Der Master gleicht beim Beitritt und danach regelmäßig
   (z. B. alle 5 s zwischen den Runden) die Uhr jedes Spielers ab
   (Ping-Pong nach NTP-Prinzip). Verwendet wird der Messwert mit der kürzesten
   Laufzeit aus den letzten N Messungen. Grundlage ist auf allen Geräten die
   monotone Uhr `SystemClock.uptimeMillis()` – dieselbe Uhr, aus der auch die
   Zeitstempel der Touch-Ereignisse stammen.
2. **Geplante Freigabe:** Der Master sendet nicht „jetzt gelb“, sondern
   „gelb um Master-Zeit T“. T liegt mindestens die größte gemessene Laufzeit plus
   Sicherheitspuffer in der Zukunft (und enthält die Zufallsverzögerung).
   Jeder Spieler rechnet T in seine eigene Uhrzeit um und schaltet genau dann.
3. **Zeitstempel am Ereignis:** Der Spieler misst den Zeitpunkt des
   Touch-Ereignisses (`MotionEvent.getEventTime()`, nicht die Verarbeitungszeit)
   und meldet die **Reaktionszeit = Ereignis − T**. Die Ankunftszeit beim Master
   spielt keine Rolle.
4. **Sammelfenster:** Nach dem ersten eingegangenen Buzz wartet der Master
   (größte Laufzeit + Puffer, ca. 300–500 ms), bevor er grün/rot verteilt.
   Erst dann ist sicher, dass kein schnellerer Buzz mehr unterwegs ist.
5. **Grenzen:** Unterschiedliche Touchscreens haben unterschiedliche Latenzen
   (typisch 10–50 ms). Das lässt sich nicht herausrechnen; deshalb sollte die
   Gleichstands-Schwelle nicht zu klein gewählt werden.

---

## 7. Wertung (vor Spielbeginn wählbar)

- **Richtige Antworten:** +1 Punkt pro richtiger Antwort.
- **Zeitwertung:** Bei richtiger Antwort wird dem Spielerkonto die
  **Restzeit** bis zum Zeitlimit (Zeitlimit − Reaktionszeit) gutgeschrieben.
  Wer schnell buzzert und richtig antwortet, bekommt also mehr.
  In diesem Modus wird das Zeitlimit beim Spielstart
  festgelegt und kann danach **nicht mehr verändert** werden.
- Falsche Antworten geben 0 Punkte.

---

## 8. Einstellungen

| Einstellung | Wann änderbar | Vorschlag |
|---|---|---|
| Startmodus (A Halten / B Tippen) | zwischen Runden | B |
| Zeitlimit pro Runde | zwischen Runden; bei Zeitwertung nur vor Spielbeginn | 10 s |
| Zufallsverzögerung (min–max) | zwischen Runden | 1–4 s |
| Zufallsverzögerung Sudden Death | zwischen Runden | 1–6 s |
| Gleichstands-Schwelle | zwischen Runden | 20 ms |
| Wertung | nur vor Spielbeginn | Richtige Antworten |
| Master spielt mit | zwischen Runden | aus |
| Max. Reaktionszeit an/aus | zwischen Runden (bei Restzeit-Wertung immer an) | an |
| Max. Antwortzeit an/aus + Sekunden | zwischen Runden | aus, 15 s |
| Nach abgelaufener Antwortzeit | zwischen Runden | automatisch weiter |
| Kurznachrichten / Emoticons erlaubt | zwischen Runden | an / an |
| Emoticon-Auswahl (7) | zwischen Runden, je Profil | 👍 😂 😮 🤔 😅 😎 🙈 |
| Rangliste: Namen anzeigen | zwischen Runden | an |
| Rangliste: sichtbare Plätze ab Platz 1 (0 = alle) | zwischen Runden | 0 |

---

## 9. Master-Bildschirm

- Spielerliste mit Name, Status-Farbe, Platz, Reaktionszeit (ms) und Punktestand.
- Knöpfe: **Buzzer frei / scharf**, **Richtig**, **Falsch**, **Nächste Runde**,
  Einstellungen.
- Beitrittsanfragen als Dialog (Annehmen / Ablehnen).
- **Master spielt mit:** Das eigene Spielfeld des Masters nimmt **1/3 des
  Bildschirms** ein und verhält sich exakt wie ein Spieler-Display.
  Fairness: Die Zufallsverzögerung ist auch für den Master unbekannt, und seine
  Eingaben laufen durch dieselbe Auswertung (Uhr-Abweichung 0, Laufzeit 0).
  Das Sammelfenster gilt auch für ihn, er sieht sein Ergebnis also nicht früher.
  **Einschränkung:** Da der Master die Fragen stellt, kennt er in der Regel die
  Antwort. Mitspielen ist daher nur sinnvoll, wenn die Fragen z. B. von einer
  Liste stammen, die er nicht kennt.

---

## 10. Nachrichten (Protokoll-Entwurf)

| Richtung | Nachricht | Inhalt |
|---|---|---|
| M → S | `PING` / S → M `PONG` | Zeitstempel für den Uhrenabgleich |
| M → S | `ARM` | Rundennummer, Startmodus (Modus A: „Finger auflegen“) |
| S → M | `HOLD` / `UNHOLD` | Modus A: Finger liegt auf / abgehoben (vor Freigabe) |
| M → S | `GO_AT` | Rundennummer, Master-Zeit T der Freigabe, Zeitlimit |
| S → M | `BUZZ` | Rundennummer, Reaktionszeit, Flag Frühstart |
| M → S | `STATE` | Farbe, ggf. Platznummer, Punktestand |
| M → S | `SUDDEN_DEATH` | beteiligte Spieler, neue Freigabe |
| M → S | `FREEZE` | keine Eingaben mehr |
| M → S | `RESET` | Runde beendet, Display schwarz |
| M → S | `BOARD` | Rangliste aus Sicht des Empfängers, höchster Punktestand, anzeigen ja/nein |

---

## 11. Rangliste für alle Spieler

- Zwischen den Runden (Zustand *Bereit*) und am Ende einer Runde sehen alle Spieler
  die Mitspieler mit Punktestand als **Balkengrafik** (Balken relativ zum
  höchsten Punktestand). Während des Buzzerns und Antwortens ist sie ausgeblendet.
- Gleiche Punktzahl ergibt denselben Platz.
- Der Master kann die **Namen ausblenden**; fremde Einträge erscheinen dann als „???“.
- Der Master legt fest, **wie viele Plätze ab Platz 1** sichtbar sind (0 = alle).
- Jeder Spieler sieht **immer sich selbst** („Du“, hervorgehoben), auch wenn sein
  Platz außerhalb der sichtbaren Plätze liegt; die Lücke wird mit „…“ angedeutet.
- Der Master sieht in seiner Spielerliste immer alle Namen mit Balken.

---

## 12. Diagnose und Build-Nummer

- Jede APK trägt eine **Build-Nummer** (Lauf-Nummer der GitHub Action):
  Version `1.0.<Nr>`, Dateiname `buzzer-build-<Nr>.apk`. Sie steht auch auf dem
  Startbildschirm.
- Bei einer Fehlermeldung und auf dem Startbildschirm gibt es **„Diagnose senden“**.
  Der Bericht enthält die Fehlermeldung, die letzten Ereignisse der App und ggf.
  einen Absturz beim letzten Start und wird über die **Teilen-Funktion** von Android
  verschickt.
- **Systeminformationen** (Gerät, Android-Version, Play-Dienste, Standort an/aus,
  erteilte Berechtigungen) kommen nur hinein, wenn der Nutzer das im Dialog ankreuzt.

---

## 13. Zeitlimits

- **Maximale Reaktionszeit** (bisher „Zeitlimit pro Runde“) ist abschaltbar; ohne
  Limit endet die Runde, wenn alle gedrückt haben. Bei der Restzeit-Wertung ist sie
  Pflicht. Während der Buzzer offen ist, zeigt der untere Rand einen sich füllenden
  Balken mit der Zeit als Zahl.
- **Maximale Antwortzeit** (abschaltbar, nur vor der Freigabe änderbar): Sobald ein
  Spieler grün wird, läuft ein Balken mit Restzeit rückwärts. Läuft die Zeit ab, gilt
  die Antwort als **falsch**; danach geht es je nach Einstellung automatisch weiter oder
  erst, wenn der Master „Weiter“ tippt.
- Vor jeder Freigabe sehen alle Spieler beide Limits.

---

## 14. Konfiguration, Profile, Export

- Der Spielername, der Emoticon-Empfang und die zuletzt verwendeten Einstellungen
  liegen in einer lokalen Config-Datei. Jeder neue Build ergänzt beim Start fehlende
  Felder mit Vorgaben. Die Datei übersteht Updates, aber kein Deinstallieren.
- **Spielprofile** (nur Spieleinstellungen) lassen sich unter frei wählbarem Namen
  speichern, laden, umbenennen und löschen.
- Stammt ein Profil aus einem älteren Build, zeigt die App beim ersten Laden die
  neuen Felder an; mit „Übernehmen“ wird das Profil ergänzt gespeichert.
- Der Master kann das laufende Profil an die Spieler senden; sie können es ansehen und
  unter einem wählbaren Namen (Vorgabe: Name beim Master) speichern.
- **Export/Import:** Lokale Einstellungen und wahlweise Profile lassen sich in eine
  frei benannte Datei `*.buzzconf` exportieren. Beim Import zeigt die App den Inhalt;
  übernommen wird nur, was angekreuzt ist. Gleichnamige Profile werden wahlweise
  ersetzt oder als Kopie abgelegt.
- Die Einstellungen sind in die Tabs **Ablauf**, **Zeiten**, **Wertung** und
  **Profile** gegliedert.

---

## 15. Kurznachrichten und Emoticons

- **Kurznachrichten** (max. 100 Zeichen) nur in den Rundenpausen, an einzelne
  Teilnehmer, den Spielleiter oder alle. Sie laufen über das Master-Gerät.
- **Emoticons** jederzeit, auch während Buzzern und Antworten. Die 7 Emoticons sind je
  Profil wählbar und stehen als Knöpfe am oberen Rand. Diese Leiste gehört nicht zur
  Buzzer-Fläche. Ein Emoticon erscheint mit dem Namen des Absenders am rechten Rand
  aller Spielfelder und fliegt nach oben weg.
- Der Master kann Nachrichten und Emoticons getrennt abschalten. Jeder Teilnehmer
  kann den Empfang von Emoticons abschalten und kann dann auch keine senden.

---

## 16. Diagnose (Ergänzung) und Signatur

- Das Diagnose-Protokoll liegt in einer Datei und übersteht Neustarts. Es enthält
  auch Spielereignisse (Freigabe, Reaktionszeiten, Ergebnis, Richtig/Falsch,
  Funk-Laufzeiten) und bei einem Abbruch die Zeit seit der letzten Nachricht.
  Der Master protokolliert außerdem fehlende Reaktionen, Gleichstände mit Abstand,
  Sudden-Death-Ergebnisse und auffällige Funk-Laufzeiten; wiederholte gleiche
  Anzeigen stehen beim Spieler nur einmal im Protokoll.
- Schließt der Master die Gruppe, verabschiedet er sich; Spieler sehen „Der
  Spielleiter hat die Gruppe geschlossen“ statt „Verbindung abgebrochen“.
- Beim regulären Verlassen/Schließen kann jeder alle Diagnosedaten löschen.
- Alle Builds werden mit demselben Schlüssel signiert, damit Updates über die
  vorherige Version installiert werden können.

---

## 17. Oberfläche und Menü

- Design „TV-Quizshow“ (Nachtblau, Gold, Türkis; Schriften Anton und Barlow).
  Weitere Designs lassen sich ergänzen und in den App-Einstellungen wählen.
- Die Oberfläche bleibt in der nutzbaren Bildschirmfläche: Status-, Navigations- und
  Taskleiste, schwebende System-Knöpfe und Kameraaussparung werden ausgespart.
- Startbildschirm: Name, Kacheln „Spiel leiten“ / „Mitspielen“, Menü „Profile &
  Einstellungen“ (Töne, Emoticons, Design, Profile, Export/Import), „Hilfe &
  Spielregeln“, „Über & Diagnose“.
- Spieler: großer runder Buzzer in der Zustandsfarbe (pulsiert bei Gelb, wackelt bei
  Frühstart, Konfetti bei „Richtig“), oben Emoticons/Ton/Chat, unten Zeitbalken.
- Master: Spielerkarten mit Statusring, Platz, Zeiten und Punktebalken; unten ein
  Hauptknopf je Phase; seltene Aktionen im Menü ⋮.

## 18. Klänge

- Alle Klänge werden synthetisch erzeugt.
- Welche Klänge erklingen, ist je Profil einstellbar (Tab „Klang“, mit „Auf Standard
  zurücksetzen“); jedes Gerät kann zusätzlich stummschalten.
- „Richtig“ und „Falsch“ hört nur der betroffene Spieler. Bei der Freigabe gibt es
  keinen Ton (unterschiedliche Lautsprecher-Verzögerung wäre unfair); das Summen beim
  Scharfschalten hat eine feste Länge und endet nicht mit der Freigabe.

## 19. Spielverlauf

- In den Pausen kann der Master auf allen Bildschirmen eine Animation des bisherigen
  Spielverlaufs zeigen: Punktebalken wachsen Runde für Runde, die Zeilen tauschen die
  Plätze. Dauer höchstens 10 Sekunden, dazu Verfolgungsjagd-Musik auf allen Geräten.
- Namen folgen der Ranglisten-Einstellung („???“ wenn ausgeblendet, eigener Eintrag „Du“).

## 20. Korrektur der letzten Runde

- In den Pausen kann der Master die letzte gewertete Runde korrigieren: einen anderen
  Spieler zum Sieger erklären oder die Wertung stornieren.
- Punkterunde: der Punkt wandert bzw. entfällt. Zeitrunde: dem bisherigen Sieger wird
  seine Restzeit abgezogen, der neue bekommt seine Restzeit aus dieser Runde (ohne Buzz 0 s).
- Ranglisten, Anzeigen und der Spielverlauf werden angepasst.

## 21. Spielpause

- Zwischen den Runden kann der Master das Spiel pausieren. Alle Spieler-Bildschirme
  werden verdeckt: „Kurze Pause – Gleich geht es weiter“. Während der Pause lässt sich
  keine Runde freigeben; wer neu beitritt, sieht die Pause ebenfalls.

## 22. App weitergeben

- Im Startmenü und im Master-Menü ⋮ gibt es „App weitergeben“. Die App kopiert ihre
  eigene Installationsdatei (APK) und übergibt sie an Android:
  - **Per Bluetooth senden:** öffnet direkt das Bluetooth-Senden des Systems; gibt es das
    auf dem Gerät nicht, erscheint die normale Teilen-Auswahl.
  - **Teilen …:** Teilen-Auswahl (Quick Share, Messenger usw.).
  - **QR-Code** mit dem festen Download-Link der neuesten Version.
- Der Empfänger öffnet die Datei, erlaubt einmal „Installation aus unbekannten Quellen“
  und installiert. Weil alle Builds mit demselben Schlüssel signiert sind, lassen sich
  weitergegebene Versionen später als Update installieren.
- Android erlaubt keine stille Übertragung: Gerät und Übertragung bestätigt der Nutzer im
  System. Welche Wege funktionieren, hängt vom Handy-Hersteller ab.

## 23. Spielart: Buzzer oder Quiz

- Nach „Spiel leiten“ wählt der Master die Spielart: **Buzzer** (Fragen mündlich, wie
  bisher) oder **Quiz** (Fragen mit vier Antworten aus der Fragen-Datenbank, die App wertet
  selbst aus). Über das Menü ⋮ lässt sie sich zwischen zwei Runden wechseln; ein Wechsel
  beginnt ein neues Spiel (Punkte auf 0). Mitspieler können schon vor der Wahl beitreten.

## 24. Quiz-Modus

**Einrichten (Master):** Kategorien, Schwierigkeit (alle Stufen der Fragendatenbank, nach gemessener
Stufe), Fragen pro Spiel, Antwortzeit, Wertung, bei „Countdown“ dessen Länge, „Ich spiele
mit“. Angezeigt wird „x neue von y passenden Fragen“. Die Einstellungen gehören zum Profil.

**Ablauf einer Frage**

1. Die Frage erscheint auf allen Geräten gleichzeitig (Uhrenabgleich wie bei der Freigabe).
2. 3 Sekunden später erscheinen die vier Antworten (2×2-Felder) und der Antworttimer läuft.
   Jede Antwort steht vollständig im Feld: Passt eine nicht, wird die Schrift etwas kleiner
   (bis 13 sp, kompakt 12 sp); reicht das nicht, werden alle vier Felder gleich höher (notfalls
   lässt sich das Antwortfeld verschieben).
   Zeitbalken, Frage und Antworten stehen in **einem** Feld – die Frage direkt über den
   Antworten.
3. Die Reihenfolge der Antworten ist **für jeden Teilnehmer und jede Frage** zufällig.
4. Jeder tippt genau eine Antwort (danach gesperrt). Gemessen wird die Zeit ab dem Einblenden
   auf dem eigenen Gerät (Touch-Zeitpunkt). Aufgelöst wird, wenn alle geantwortet haben oder
   die Zeit abgelaufen ist; der Master kann vorziehen („Jetzt auflösen“) oder die Frage
   überspringen (ohne Wertung).
5. Auflösung: Das richtige Feld wird grün; wer es gewählt hat, sieht darin 👍 und hört einen
   **Tusch**. Eine falsch gewählte Antwort wird rot, fett mit ✗ durchgestrichen, dazu
   **„Wah-wah-waaah“**. Ohne Antwort wird nur das richtige Feld grün, dazu „Wah-wah-waaah“.
   Klänge nur auf dem eigenen Gerät.
6. Darunter: Erklärung, Link zur Quelle, Knopf **Challenge**. Die Auflösung ist eine Pause
   (Kurznachrichten, Rangliste, Spielverlauf, Pause).

**Wertung** (falsche Antworten kosten nie etwas; bei Gleichstand innerhalb der
Gleichstands-Schwelle punkten beide)

| Modus | Wer punktet |
|---|---|
| Schnellster punktet | nur die erste richtige Antwort (1 Punkt) |
| Jeder punktet | jede richtige Antwort (1 Punkt) |
| Countdown | nach der ersten richtigen Antwort läuft nur noch der Countdown (Vorgabe 5 s); alle Richtigen bekommen ihre Restzeit |
| Battle | nur der Schnellste bekommt seine Restzeit |

**Fairness:** Die Spielergeräte erhalten die richtige Antwort erst mit der Auflösung (auch
nicht im Speicher). Der Master kann mitspielen; sein Antwortfeld liegt im unteren Drittel,
die Lösung sieht er dann ebenfalls erst bei der Auflösung. Spielt er nicht mit, sieht nur er
die Lösung schon während der Frage.

**Master während der Frage:** wer hat schon geantwortet (mit Zeit, ohne Inhalt).
**Master bei der Auflösung:** Verteilung je Antwort mit Namen, Punkte, Stufe (Autor/gemessen).

**Challenge:** Jeder Teilnehmer kann eine Frage bis zur nächsten Frage einmal anfechten
(optionale Begründung). Der Master sieht Begründung und Quelle und entscheidet: **Wertung
bleibt**, **Frage annullieren** (Punkte zurück), **Antwort korrigieren** (Punkte neu
berechnet, gilt auch für spätere Spiele) oder **Frage sperren** (annulliert, wird nicht mehr
gezogen). Ohne Entscheidung bleibt die Wertung. Der Master kann die Wertung einer Frage auch
ohne Challenge ändern.

**Emoticons und Kurznachrichten:** Solange die Antwortzeit läuft, fliegen Emoticons nur in
einer eigenen Bahn oberhalb des Fragenfelds (von rechts nach links) – nie über die Antworten.
Danach wie gewohnt. Kurznachrichten nur in den Pausen.

**Ende:** Nach der letzten Frage „Ergebnis zeigen“: Rangliste auf allen Geräten, Tusch und
Trommelwirbel; der Master kann ein neues Quiz einrichten.

## 25. Fragen-Datenbank und Statistik

- **Fragenpakete** liegen im Repository (`quiz/*.json`, Format in `quiz/README.md`) und werden
  in die App eingebaut. „Fragen aktualisieren“ lädt neuere Pakete direkt aus dem Repository
  (`index.json` mit Versionen), ohne neuen Build. Neue Fragen recherchiert Claude auf Zuruf.
- Je Frage: ID, Version, Kategorie, Stufe, Frage, vier Antworten (die erste ist richtig),
  Erklärung, Quelle, Erstellt, optional „gültig bis“ (aktuelle Themen laufen ab).
- **Statistik** (nur auf dem Master-Gerät): je Frage gespielt, zuletzt gespielt, wer sie gesehen
  hat (Namen), richtig/falsch/keine Antwort, Antwortzeit der Richtigen, Challenges und
  Entscheidungen, gesperrt, korrigierte Antwort. Neue Version einer Frage → Antwortzahlen
  beginnen neu.
- **Auswahl:** gewählte Kategorien und Stufen, nicht gesperrt, nicht abgelaufen. Vorrang hat,
  was möglichst wenige der verbundenen Spieler schon gesehen haben; danach am längsten nicht
  gespielt, dann Zufall. Unter gleich neuen Fragen wechseln die Kategorien ab. Reichen die
  neuen Fragen nicht, kommen bekannte erneut (älteste zuerst) mit dem Hinweis, neue anzufordern.
- **Schwierigkeit lernt mit:** Quote = (richtig + 2·Startwert) / (Antworten + 2) mit Startwert
  je Stufe aus der Fragendatenbank („startwert“, z. B. leicht 0,8 / mittel 0,55 / schwer 0,3 /
  Ingomania 0,12); die Messung vergibt die höchste Stufe, deren „gemessenAb“ erreicht ist (leicht
  0,7, mittel 0,4, schwer 0,2); Stufen mit „nurAutor“ vergibt sie nicht an Fragen anderer Stufen.
  Brauchten die Richtigen im Schnitt mehr als 60 % der Zeit, eine Stufe schwerer. Ab 6 Antworten
  gilt die gemessene Stufe.
- **Statistik teilen** (`.buzzstats`, JSON) – zur Auswertung durch Claude.

## 26. App-Versionen abgleichen

- Jede App hat eine **Build-Nummer** und eine **Protokoll-Version**; kompatibel ist, wer dieselbe
  Protokoll-Version spricht. Beide stehen im Gerätenamen bei Suche und Beitritt:
  `Name [Build/Protokoll]`, nur zum Aktualisieren `Name [Build/Protokoll/u]`. Dieses Format
  bleibt in allen künftigen Versionen gleich. Builds bis 11 senden keine Angabe.
- Die Gruppensuche zeigt, ob die Version passt. Bei Abweichung: „Build N vom Spielleiter holen“
  bzw. „Meine Version an den Spielleiter geben“ – eine Verbindung nur zum Aktualisieren
  (der Master bestätigt sie). Dabei schickt der Neuere seine APK über die Nearby-Verbindung
  (ohne Internet), danach wird getrennt.
- Der Master sieht abweichende Builds in der Spielerliste und im Menü ⋮: „Alle auf Build N
  bringen“ (an ältere Spieler senden) bzw. „Build N von *Name* holen“.
- Alternativ lädt „Über & Diagnose → Nach neuer Version suchen“ die neueste Version von der
  Release-Seite des Repositorys.
- Vor der Installation prüft die App die Datei: Paketname, gleiche Signatur, höhere Build-
  Nummer. Android fragt einmal nach „Unbekannte Apps installieren“; Name, Profile und
  Statistik bleiben erhalten. Wer noch Build 11 oder älter hat, aktualisiert einmalig über
  „App weitergeben“ oder die Release-Seite.

## 27. Tipp-Hinweis für den Master

- Tippt ein Teilnehmer im Nachrichtenfenster, erscheinen beim Master neben dem Brief-Knopf
  drei pulsierende Punkte (ohne Namen). Sie verschwinden beim Senden/Schließen bzw. nach
  8 Sekunden ohne Tastendruck. Nur ein Hinweis – die Freigabe wird nicht blockiert.

## 28. Prüfcode beim Beitreten

- Nearby erzeugt für jede Verbindung einen vierstelligen Prüfcode, der auf beiden Geräten gleich
  ist. Der Master sieht ihn im Beitrittsdialog, der Spieler groß beim Warten auf die Bestätigung.
  Der Master nimmt nur an, wenn beide denselben Code sehen – so verbindet sich kein fremdes Gerät
  unbemerkt. Der Code steht außerdem im Protokoll beider Geräte.

## 29. Diagnose an den Spielleiter und Ablage in GitHub

- Der Diagnose-Dialog bietet zusätzlich: als Spieler **„An den Spielleiter senden“** (über die
  Nearby-Verbindung, in Teilen übertragen), als Spielleiter **„In GitHub ablegen“** (eigener Bericht).
- Systeminformationen kommen nur hinein, wenn der Nutzer sie ankreuzt; der Dialog erklärt kurz,
  wozu sie dienen und wie die Daten geschützt sind (keine Standortdaten, Kontakte oder Nummern).
- Der Spielleiter legt empfangene Berichte im **privaten** Repository `AxelPabich/buzzer-diagnose`
  ab: `diagnose/JJJJ-MM-TT/Build-N/Name-HH-MM-SS.txt`. Mit eingerichtetem Zugang geschieht das
  sofort, sonst über ⋮ „Diagnoseberichte in GitHub ablegen“. Der Spieler erhält eine Bestätigung.
- Zugang: ein Fine-grained Token, das nur in dieses Repository schreiben darf (Contents: Read and
  write). Beim ersten Hochladen zeigt die App eine Anleitung mit direkten Links zum Anlegen von
  Repository und Token. Der Token liegt nur auf dem Gerät, verschlüsselt mit einem Schlüssel aus
  dem Android-Keystore; „Über & Diagnose“ zeigt den Stand und kann ihn löschen. Die App prüft vor
  dem Speichern, dass das Repository erreichbar und privat ist.

## 30. Standort für die Suche

- Manche Geräte verweigern die Suche, solange der Standort ausgeschaltet ist (Fehler 8034), obwohl
  alle Berechtigungen erteilt sind. Die App prüft das vor der Suche und erklärt es mit dem Weg zur
  Einstellung („Trotzdem suchen“ bleibt möglich). Fehler 8034 wird ebenso verständlich erklärt.
- „Fragen aktualisieren“ meldet bei fehlenden Paketen im Repository „Im Repository liegen keine
  neueren Fragen“ statt eines Internet-Hinweises.

## 31. Diagnose im Spiel

- Spieler haben in der oberen Leiste ein Menü ⋮ mit „Diagnose senden“ (dort „An den Spielleiter
  senden“) und „Gruppe verlassen“; der Spielleiter findet „Diagnose senden“ in seinem Menü ⋮.
- Der Spielleiter lädt eigene und eingesammelte Berichte hoch. Noch nicht hochgeladene Berichte
  werden auf dem Gerät zwischengespeichert und überstehen Verlassen und Neustart.

## 32. Quiz-Statistik zurück ins Repository

- „Quiz-Statistik hochladen“ (Menü ⋮ im Quiz) legt die Statistik mit dem Zugang des Spielleiters
  in `buzzer-diagnose/statistik/JJJJ-MM-TT/Name-HH-MM-SS.buzzstats` ab. Claude wertet sie auf Zuruf
  aus und passt die Fragenpakete im Buzzer-Repository an; die App selbst schreibt dort nicht.

## 33. Quiz-Vorlagen

- Quiz-Vorlagen sind getrennt von den Buzzer-Profilen. Nach der Wahl „Quiz“ wählt der Spielleiter
  zuerst eine Vorlage (oder „mit den aktuellen Einstellungen weiter“ bzw. „Neue Vorlage“).
- Die Quiz-Einrichtung zeigt die aktive Vorlage mit „Speichern“, „Speichern unter …“, „Wechseln“
  und „Vorlage an Spieler senden“. Empfänger speichern sie als Quiz-Vorlage. Export und Import
  übernehmen die Kennzeichnung.
- Änderungen in der Einrichtung werden erst nach kurzer Ruhe übernommen (nicht bei jedem Tipp).

## 34. Prüfung durch den Spielleiter

- Bei der Auflösung sieht der Spielleiter die Erklärung und kann selbst „Antwort prüfen
  (Challenge)“ starten. Während er prüft, läuft bei allen Spielern unten ein Laufband „Bitte warte
  kurz – die Antworten werden überprüft“. Mit seiner Entscheidung (oder der nächsten Frage) endet es.
- In der Auflösung beim Spielleiter ist die richtige Antwort eine grüne Karte mit hellem Rahmen,
  Haken und größerer Schrift; die übrigen Antworten bleiben gedämpft (bei Annullierung grau umrandet,
  „galt als richtig“).
- Sendet ein Spieler eine Challenge, startet das Laufband automatisch. Nach einer Entscheidung
  heißt der Knopf „Entscheidung ändern“ und öffnet nur die Auswahl, ohne erneutes Laufband.
- Beim Beitritt protokolliert der Spieler „Runde beendet“ nur nach einer laufenden Runde und die
  Spielart nur, wenn sie feststeht und sich ändert.

## 35. Obere Leiste

- Ton, Brief und Menü stehen rechts immer fest; die Emoticons nutzen den restlichen Platz und
  lassen sich auf schmalen Bildschirmen seitlich wischen.

## 36. Verbindung im Hintergrund

- Solange eine Gruppe offen ist (Spielleiter und Spieler), läuft ein Vordergrund-Dienst mit der
  Benachrichtigung „Buzzer-Runde läuft“. So trennt Android die Verbindung im Hintergrund nicht.
  Beim Verlassen endet der Dienst.

## 37. Pause per Knopf

- Neben dem Menü ⋮ hat der Spielleiter einen Pause-Knopf (II / ▶), aktiv in den Pausen und bei
  der Auflösung.

## 38. Get Ready

- „Get Ready“ (Buzzer: neben „Buzzer frei“; Quiz: in der Einrichtung und bei der Auflösung) zeigt
  allen Spielern „Bereit?“ mit dem Knopf „Bereit!“. Der Spielleiter sieht „Bereit: x / y“ und wer
  fehlt; sind alle bereit, erscheint „GO!“ mit Startsignal-Klang. Er kann auch ohne „Go!“ starten;
  die Abfrage schließt dann überall. Auch der Spielleiter bekommt „BEREIT?“ und muss bestätigen,
  bevor „Go!“ kommt (er steht als „Name (Spielleiter)“ in der Liste).
- Optional „Bereit-Abfrage automatisch“ (Quiz-Einrichtung, 5–120 s nach der Auflösung): startet
  „BEREIT?“ von selbst; wartet bei offener Challenge, Prüfung oder Pause; nicht nach der letzten Frage.
- Während die Abfrage läuft, zeigt der Knopf „Bereit: x / y …“ und startet keine neue Abfrage
  (abbrechen über das Bereit-Feld oben). Quiz: Nach „Go!“ startet die nächste Frage (bzw. in der
  Einrichtung das Quiz) nach 1,5 s automatisch – nicht, wenn inzwischen pausiert oder abgebrochen
  wurde. Buzzer: Nach „Go!“ leuchtet „Buzzer frei“ grün, freigeben bleibt beim Spielleiter.

## 39. Testspiel

- Im Quiz hat der Spielleiter im Menü ⋮ den Schalter „Testspiel – nicht in die Statistik“. Solange
  er an ist (Kopfzeile „TESTSPIEL“), fließen Antworten, übersprungene Fragen, Challenges und
  Entscheidungen nicht in die Quiz-Statistik, sondern in eine getrennte Test-Statistik
  (`quiz-stats-test.json`), die Auswahl und Schwierigkeit nicht beeinflusst. Er gilt bis zum
  Neustart der App.
- „Quiz-Statistik hochladen“ legt die Test-Statistik – falls vorhanden – zusätzlich als
  `statistik/JJJJ-MM-TT/Name-HH-MM-SS-TEST.buzzstats` ab (im JSON `"test": true`) und leert sie danach.

## 40. Gespeicherte Diagnosen

- Jede Diagnose (im Spiel oder außerhalb, z. B. nach einem Verbindungsfehler) lässt sich „Auf dem
  Gerät speichern“. Mit gespeichertem GitHub-Token heißt der Knopf „In GitHub ablegen“; klappt das
  Hochladen nicht, bleibt der Bericht gespeichert.
- „Gespeicherte Diagnosen“ (Über & Diagnose, Menü ⋮ von Spieler und Spielleiter) zeigt die Liste mit
  Ansehen, Teilen, Löschen und – mit Token – „Alle in GitHub ablegen“.
- Der Dateiname in GitHub richtet sich nach der Erstellungszeit des Berichts (nicht nach dem
  Hochladen). Liegt dieselbe Datei schon dort, gilt der Bericht als abgelegt.
- Hat der Spielleiter einen Token, meldet er das jedem beitretenden Spieler (`DIAG_COLLECT`). Hat
  der Spieler gespeicherte Diagnosen, fragt er „Der Spielleiter kann N gespeicherte Diagnose(n) in
  sein Diagnose-Repository übernehmen. Senden?“ (Ja / Nein / Später; „Nein“ gilt bis zum Neustart).
  Die Berichte gehen in Teilen mit Kennzeichen „gespeichert“; der Spielleiter bestätigt erst nach
  dem Hochladen (`DIAG_OK`), dann löscht der Spieler sie.

## 41. Spielverlauf

- Nach der Animation bleibt der Endstand 3 Sekunden stehen (die Musik endet mit der Animation).
- Der Spielleiter startet den Spielverlauf für alle über den Knopf 🐇🐢 in der Kopfzeile (neben der
  Pause); er ist aktiv, sobald es einen Verlauf gibt. „Spielverlauf zeigen“ im Menü ⋮ bleibt.

## 42. Entscheidungen bei der Auflösung

- Tippt der Spielleiter „Get Ready“ oder „Nächste Frage“, während eine Challenge oder Prüfung offen
  ist, fragt die App: „Offene Challenge von … – Wertung bleibt?“ (Wertung bleibt / Zurück zur
  Prüfung). Erst nach „Wertung bleibt“ geht es weiter.
- „Ersatzfrage“: Die aufgelöste Frage wird annulliert und unter derselben Nummer durch eine Frage
  aus demselben Filter ersetzt, die im Quiz noch nicht vorkommt (ungespielte zuerst). Sie
  verschwindet auch aus dem Spielverlauf. Ohne passende Frage ist der Knopf ausgegraut.
- Annullieren, Sperren und Ersatzfrage nehmen Punkte bzw. Zeiten der Frage zurück (in allen
  Wertungen, auch in Rangliste und Spielverlauf). „Korrigieren“ rechnet neu; im Countdown beginnt
  der Countdown dann bei der ersten jetzt richtigen Antwort.
- Die Fragen-Statistik folgt der Entscheidung: Bei Annullieren/Sperren/Ersatzfrage zählen die
  Antworten nicht, bei Korrigieren werden richtig/falsch neu gezählt.

## 43. Countdown-Grenze

- Der Countdown ist höchstens halb so lang wie die Antwortzeit; wird die Antwortzeit verkürzt,
  rückt er mit. Unter dem Regler steht „Nach der ersten richtigen Antwort bleiben den anderen noch
  x s.“ Zu lange Werte aus älteren Vorlagen werden beim Laden begrenzt (Meldung).

## 44. App-Symbol und Copyright

- App-Symbol: lachender roter Buzzer auf goldenem Sockel vor Scheinwerferstrahlen im
  Quizshow-Blau (adaptiv, einfarbig ab Android 13); die Benachrichtigung zeigt den Buzzer.
- „Hilfe & Spielregeln“ und „Über & Diagnose“ enden mit „© 2026 Axel Pabich · Idee, Spielregeln
  und Tests: Axel Pabich · Programmiert mit Claude (Anthropic)“.

## 45. Spieleinstellungen im Quiz

- Im Quiz zeigt ⋮ „Spieleinstellungen“ nur Quiz-relevante Werte: „Teilnehmer“ (Master spielt mit,
  Kurznachrichten, Emoticons), „Wertung“ (Gleichstands-Schwelle, Rangliste, Neues Spiel), „Klang“
  (Beitritt, Tusch, Wah-wah, Go, Rundenende, Emoticon, Kurznachricht, Spielverlauf). Fragen,
  Antwortzeit, Wertung und Vorlagen stehen in der Quiz-Einrichtung. Empfangene Quiz-Vorlagen
  zeigen nur Quiz-Werte. Der Buzzer-Modus behält alle Einstellungen.

## 46. Anfragen während einer Runde

- Der Prüfcode steht in Beitritts- und Update-Anfragen beim Spielleiter genauso groß wie beim
  Spieler („PRÜFCODE 1234“ mit Hinweis zum Vergleichen).

- Beitritts- und Update-Anfragen sowie das Angebot einer empfangenen neueren Version erscheinen
  beim Spielleiter erst, wenn die aktuelle Frage beantwortet ist (Quiz: Auflösung; Buzzer: „Runde
  beendet“ oder vor der Freigabe). Einsteigen mitten im Spiel ist erlaubt – der Neue beginnt mit
  0 Punkten und spielt ab der nächsten Frage mit. Während der Frage
  zeigt die Kopfzeile nur „⏳ Name wartet – Anfrage erscheint nach der Runde“. Abgebrochene
  Anfragen verschwinden aus der Warteschlange.

## 47. Start-Animationen

- Splash-Screen beim App-Start (3 s, Tippen überspringt), zufällig eine von drei Varianten:
  „Hüpfer“, „Vorhang auf“, „Hau drauf“ – jeweils mit eigenem Buzzerton; immer mit jubelnden
  Mitspielern, die Handys hochhalten und Konfetti werfen. Klang nur bei eingeschaltetem Ton.
- Spielstart-Animation (3 s): Rennszene mit Mitspielern, Hase und Schildkröte („Die Fabel“,
  „Verfolgungsjagd“, „Startschuss“). Der Spielleiter würfelt die Variante und schickt sie mit
  (`INTRO|v=n`), alle sehen dieselbe. Quiz: Die erste Frage kommt nach der Animation. Buzzer: bei
  „Neues Spiel“. Entwurf: `docs/animationen-entwurf.html`.
- Am Quiz-Ende läuft der Spielverlauf automatisch (höchstens 15 s, bei vielen Fragen schneller),
  danach bleibt das Ergebnis stehen; der Spielverlauf ist allgemein auf 15 s begrenzt.

## 48. Spielleitung übergeben und übernehmen

- In jeder Pause (vor der Freigabe, nach der Runde, bei der Auflösung) schickt der Spielleiter allen
  Spielern den Spielstand für einen Nachfolger (`SUCCESSION`): Spiel-Kennung, Spielart, Runde,
  Profil und Einstellungen, Punkte nach Namen, Fragen-IDs des laufenden Quiz und die nächste Frage,
  Testspiel ja/nein. Während einer Frage bzw. Runde kommt kein neuer Stand – fällt der Spielleiter
  dann weg, wird die laufende Frage wiederholt.
- **Übergeben:** ⋮ „Spielleitung übergeben an …“ (nur in einer Pause) – Auswahl eines verbundenen
  Spielers. Der Stand geht mit Namen des Nachfolgers an alle; der Nachfolger eröffnet sofort die
  Gruppe, die anderen und der bisherige Spielleiter (jetzt als Spieler) verbinden sich automatisch
  mit ihm.
- **Verlassen:** ⋮ „Spiel verlassen“ fragt „Spieler übernehmen“ (`MASTER_GONE`) oder „Für alle
  beenden“ (`BYE`, wie bisher).
  Wird die App beim Spielleiter weggewischt, gilt „Spieler übernehmen“ (sobald eine Spielart läuft).
- **Übernehmen:** Nach `MASTER_GONE` oder einem Verbindungsabbruch zum Spielleiter sehen die
  Spieler „Wer übernimmt?“ mit „Ich übernehme“ und einem Countdown von 1 Minute. Wer zuerst tippt,
  leitet; die übrigen finden ihn über die Suche (Name aus dem Spielstand) und treten ohne
  Prüfcode-Rückfrage bei (der Nachfolger nimmt bekannte Namen 3 Minuten lang automatisch auf).
  Übernehmen zwei gleichzeitig, gibt der später Beigetretene ab und tritt dem anderen bei.
  Meldet sich niemand innerhalb 1 Minute, endet das Spiel für alle („Niemand hat die Spielleitung
  übernommen“). Ohne gewählte Spielart gibt es keine Übernahme.
- **Herzschlag:** Der Spielleiter pingt in jeder Phase etwa alle 2 s. Hört ein Spieler 12 s nichts
  (Spielleiter ab Build 27), gilt die Verbindung als abgebrochen – sonst merkt Nearby das erst nach
  rund 1 Minute. Taucht der bisherige Spielleiter wieder auf, tritt ihm niemand bei und kein
  Nachfolger gibt an ihn ab: Das Spiel liegt beim Nachfolger. Verliert der Spielleiter alle Spieler
  fast gleichzeitig, kündigt er seine Gruppe nach 3 s neu an und weist darauf hin, dass vielleicht
  schon jemand übernommen hat.
- Der neue Spielleiter sieht „Noch nicht zurück: …“, bis alle Mitspieler des Vorgängers wieder da
  sind (höchstens 3 Minuten). Sie werden sofort aufgenommen, auch mitten in einer Runde (Abschnitt 53).
- Taucht der bisherige Spielleiter wieder auf, bevor jemand übernommen hat, verbinden sich die
  Spieler wieder mit ihm. Allgemein nimmt ein Spielleiter Spieler, deren Verbindung bei ihm in
  diesem Spiel abgebrochen ist, sofort und ohne Rückfrage wieder auf (Abschnitt 53), und wer mit
  gleichem Namen zurückkommt, bekommt seine Punkte zurück (bis „Neues Spiel“).
- Der Spielstand enthält die Fragen des laufenden Quiz ab der zuletzt gestellten (wird es zu groß,
  zuerst ohne Quellen, dann ohne Erklärungen) – der Nachfolger braucht die Pakete nicht selbst.
- **Was bleibt:** Punkte (beim Wiederbeitritt nach Namen zugeordnet; wer später kommt, bekommt sie
  dann), Runde, Spielart, Profil und Einstellungen, Testspiel. Der Nachfolger spielt weiter mit
  („Master spielt mit“) und behält seine Punkte. Das Quiz läuft mit derselben Fragenliste weiter
  (aus den eigenen Fragenpaketen; fehlende Fragen fallen weg): Der neue Spielleiter sieht die
  zuletzt aufgelöste Frage (ohne Antworten und Entscheidungen) und macht mit „Nächste Frage“
  bzw. Get Ready weiter. Der Spielverlauf beginnt mit den übernommenen Punkten.

## 49. Diagnosen abrufen und prüfen

- Spielleiter: ⋮ „Diagnosen der Spieler abrufen“ schickt allen Spielern `DIAG_REQUEST`.
- Spieler: Rückfrage „Diagnose an den Spielleiter?“ mit „Systeminformationen mitsenden“ (Vorgabe aus),
  „Bericht ansehen“ und „Nicht erneut fragen“. Mit dem Haken wird die Antwort (senden mit/ohne
  Systemdaten bzw. nicht senden) gespeichert.
- Gesendete Berichte landen wie bisher beim Spielleiter (mit Token direkt in GitHub).
- Spieler mit Build < 27 kennen die Anfrage nicht; der Spielleiter sieht ihre Namen im Hinweis.
- „Diagnose senden“ zeigt ebenfalls „Bericht ansehen“: genau der Text, der verschickt würde.
- „Nicht erneut fragen“ gilt dauerhaft, auch über neue Builds hinweg; in den Einstellungen steht die
  gespeicherte Antwort mit „Wieder fragen“.

## 50. Installation neuer Versionen

- „Installieren“ verlässt zuerst ein laufendes Spiel (Spielleiter: die Spieler dürfen übernehmen),
  dann übergibt die App die APK über den Paket-Installer von Android (Sitzung). Android fragt wie
  gewohnt nach; geht der Paket-Installer nicht, öffnet sich der bisherige Installer-Dialog.
- Protokoll: Größe der APK, Übergabe an den Installer, Warten auf Bestätigung, Ergebnis (Erfolg
  bzw. Grund wie abgebrochen, blockiert, Konflikt, zu wenig Speicher). Beim nächsten Start steht
  „App aktualisiert: Build A → Build B“ – oder „ZURÜCKGESETZT“, falls eine ältere Version läuft.
- „Später (bleibt bereit)“: Die geprüfte APK bleibt als `buzzer-build-N.apk` im App-Cache liegen
  (immer nur eine, ältere werden gelöscht) und wird auch nach einem Neustart wieder gefunden.
  Angeboten wird sie über „Build N installieren“ auf dem Startbildschirm, in „Über & Diagnose“,
  beim Spielleiter im ⋮-Menü und erneut nach dem Verlassen eines Spiels. Nicht neuere oder
  ungültige Dateien räumt die App beim Start weg.
- „Über & Diagnose“ zeigt zusätzlich „Direkter Download im Browser (buzzer.apk)“ und den Link zum
  Kopieren – Installation dann ohne die App über „Eigene Dateien“.

## 51. Spielregeln, Avatar, Klänge

- **Spielregeln der Runde** (`RULES`): Beim Beitritt (sobald eine Spielart gewählt ist) und zu
  Spielbeginn (Quiz starten, Buzzer gewählt bzw. „Neues Spiel“) bekommen die Spieler die Regeln in
  Klartext – Quiz: Fragen, gespielte Kategorien, Stufen, Ablauf, Antwortzeit, Wertung, Challenge;
  Buzzer: Ablauf, Startmodus, Frühstart, Limits, Wertung, Gleichstand. Das Fenster verschwindet,
  sobald gespielt wird; über ⋮ „Spielregeln dieser Runde“ lässt es sich erneut öffnen.
- **Avatar** (`AVATAR`): In den Einstellungen wählt jeder ein Emoticon. Es steht vor dem Namen in
  Rangliste, Spielverlauf, Empfängerliste und bei den Karten des Spielleiters (auch beim
  mitspielenden Spielleiter). Wird beim Beitritt und bei jeder Änderung geschickt.
- **Krönender Abschluss:** Endet die Animation des Spielverlaufs, folgt auf die Jagdmusik eine
  Siegerfanfare (Wirbel, Beckenschlag, Bläserakkord, Glocken, Jubel).
- **Start-Animationen:** zufällig, aber nie zweimal hintereinander dieselbe Variante (je Gerät
  gemerkt). Die Rennszene wählt der Spielleiter und schickt sie mit – alle sehen dieselbe; der
  Splash-Screen beim App-Start ist je Gerät verschieden (keine Verbindung beim Start).
- **Klänge der Animationen** neu: Blechbläser, Glocken, Becken, Pauke, Trommelwirbel,
  Trillerpfeife, Luftzug und Hall, passend zu den Bewegungen der Szenen. Hörproben: `docs/klaenge/`.
- **„Bereit?“ mit Challenge:** Im Quiz zeigt die „Bereit?“-Abfrage beim Spieler zusätzlich „Letzte
  Frage anfechten“, solange die zuletzt aufgelöste Frage noch nicht angefochten ist.
- **Spielregeln** bekommen nur Neue – wer nach Übernahme oder Funkabbruch zurückkommt, nicht erneut.
- **Installation:** zuerst der klassische Installer-Dialog (nach dem Verlassen des Spiels); der
  Paket-Installer (Sitzung) nur, wenn es keinen Dialog gibt – er wurde auf Axels Gerät (Android 16)
  und Peters Tablet wiederholt abgelehnt.
- **Übernahme und Rückkehr:** Hat jemand übernommen, aber noch niemand ist bei ihm, und der
  bisherige Spielleiter taucht wieder auf, gibt der Nachfolger ab und tritt ihm bei – sonst
  zerfällt das Spiel in zwei Gruppen. Kommt ein Spieler mit gleichem Namen neu, während seine alte
  Verbindung noch hängt, wird die alte ersetzt (keine doppelten Einträge, Punkte bleiben).
- **Spielverlauf tippen:** Erstes Tippen springt zum Endstand („ENDSTAND“, Siegerfanfare), zweites
  Tippen schließt. Ohne Tippen bleibt der Endstand stehen, bis der Spielleiter weitermacht (nächste
  Frage, Freigabe oder „Bereit?“) – dann schließt er auf allen Geräten.
- **Splash „Hau drauf“:** Die Hand landet berechnet auf der Kuppel des Maskottchens (Hand und
  Maskottchen teilen dieselbe Geometrie, unabhängig vom Seitenverhältnis).

## 52. Selbsttest

- Spielleiter mit GitHub-Zugang: ⋮ „Selbsttest (alles automatisch prüfen)“ zwischen den Runden.
- **Auf dem Gerät** (`SelfTest.runOffline`, simulierte Spieler mit abweichenden Uhren und Laufzeiten
  in virtueller Zeit): Codec aller wichtigen Nachrichten, Einstellungen, Buzzer-Reihenfolge,
  Frühstart, falsch/richtig/Punkte, Quiz-Wertung, Challenge „annullieren“, Bereit-Abfrage mit Go,
  Übernahme mit mitgeschickten Fragen, Punkte beim Wiederbeitritt, alle Klänge. Dazu Fragenpakete
  (Anzahl, alle Stufen je Kategorie, eindeutige IDs) und die Release-Seite.
- **Live-Test** (optional, Spieler ab Build 35): Der Spielleiter schaltet die Spieler in den
  Testmodus (`TEST_MODE`) – sie bestätigen „Bereit?“, buzzern nach 150–650 ms und antworten zufällig
  von selbst. Geprüft werden Funk-Laufzeit je Spieler, eine Buzzer-Runde, die Bereit-Abfrage, eine
  Quiz-Frage (Testspiel, Statistik unberührt; haben alle Spieler geantwortet, antwortet der
  Spielleiter auch – die Frage wartet nicht die volle Zeit) und das Einsammeln der Diagnosen (im Testmodus immer
  mit Systemdaten). Danach Testmodus aus, Spielart und Einstellungen wie vorher; Punkte sind zurückgesetzt.
- **Ergebnis:** Fenster mit allen Prüfungen; Bericht „Selbsttest-<Name>“ (Ergebnisse plus Protokoll
  und Systemdaten des Spielleiters) und die Diagnosen der Spieler landen im Diagnose-Repository.

## 53. Wiedereinstieg nach kurzem Funkabbruch

- Wer in diesem Spiel schon mitgespielt hat, kommt **sofort und ohne Prüfcode** wieder hinein –
  auch mitten in einer Runde. Das gilt für Spieler, deren Verbindung beim Spielleiter abgebrochen
  ist (ohne Zeitgrenze, bis die Gruppe geschlossen wird), und für Spieler, deren alte Verbindung
  beim Spielleiter noch hängt, weil sie den Abbruch früher bemerkt haben; die alte Verbindung wird
  ersetzt – aus dem Spiel genommen, aber nicht aktiv getrennt (das riss die neue Verbindung zum
  selben Gerät mit); der Funk beendet sie selbst. Erkannt wird der Rückkehrer am Namen.
- **Quiz:** Kommt der Spieler zurück, solange die Frage offen ist, bekommt er sie sofort wieder
  (sobald seine Uhr abgeglichen ist, höchstens nach 3 s) – mit derselben Antwort-Reihenfolge und der
  restlichen Antwortzeit (auch nach Countdown-Verkürzung). Eine vor dem Abbruch gegebene Antwort
  zählt weiter. Wer neu beitritt, spielt ab der nächsten Frage mit.
- **Buzzer:** Der Rückkehrer ersetzt seinen grauen Eintrag und spielt ab der nächsten Runde mit.
- Läuft gerade „Bereit?“, bekommt der Rückkehrer die Abfrage sofort.
- **Stille Spieler:** Antwortet ein Spieler 15 s lang nicht auf den Herzschlag (alle 2 s), trennt
  ihn der Spielleiter selbst, statt auf die Meldung des Funks zu warten (die kam im Test erst nach
  47 s). So wartet keine Frage und keine Bereit-Abfrage auf ihn; kommt er zurück, gilt das oben
  Gesagte. Läuft gerade eine Datei von oder zu ihm (Update, Diagnose), wartet der Spielleiter eine
  weitere Frist ab.

## 54. Stufen und Kategorien liegen in der Fragendatenbank

- **Stufen** stehen in `quiz/index.json` unter „stufen“ (Reihenfolge = leicht bis schwer):
  `schluessel`, `name`, `startwert`, `gemessenAb`, `nurAutor`, `vorgewaehlt`. Die App liest sie beim
  Start aus dem eingebauten Index; „Fragen aktualisieren“ lädt den neuesten Index und merkt ihn
  sich (`quiz-index.json`), der geladene Index gilt vor dem eingebauten. Eine neue Stufe erscheint
  so in der Auswahl, in der Statistik und in den Nachrichten, ohne dass der Code geändert wird.
- Ohne Index (oder ohne „stufen“) gelten eingebaute Standardstufen. Eine Frage mit unbekannter
  Stufe wird übersprungen und im Protokoll gemeldet.
- **Vierte Stufe „Ingomania“** (Wunsch vom 24.10.2026): die schwerste Stufe, `vorgewaehlt: false`
  (der Spielleiter wählt sie bewusst dazu) und `nurAutor: true`. Je Kategorie 20 Fragen.
- **Kategorien** sind die Pakete der Datenbank: jede Datei, die der Index listet, ist eine
  Kategorie (Titel = Kategorie der Fragen). Ein neues Paket im Index erscheint ohne Codeänderung.
- **Ältere Apps:** Nachrichten tragen die Stufe weiter als EASY/MEDIUM/HARD (unbekannte Stufen als
  HARD) plus die neuen Felder `lk` (Schlüssel) und `ll` (Beschriftung); neue Apps bauen unbekannte
  Stufen daraus nach.
- Die Paketprüfung (`QuestionPackFilesTest`) verlangt: jede Stufe des Index ist in jeder Kategorie
  vertreten, jede Frage nennt eine Stufe des Index.

## 55. Spielleiter-Ansicht während des Spiels

- **Quizfrage läuft:** Der Spielleiter sieht dieselbe Ansicht wie die Spieler: Kopfzeile „FRAGE n / m ·
  Kategorie · Stufe“, die große Frage mit den vier Antwortfeldern (spielt er mit, tippt er hier;
  sonst sieht er die Karte mit der markierten Lösung und dem Zeitbalken). Der Titel der
  Spielleiter-Seite entfällt in dieser Zeit. Darunter nur Zusätzliches: eine Wischzeile, wer schon
  geantwortet hat, und „Jetzt auflösen“ / „Frage überspringen“.
- **Auflösung:** gleiche Kopfzeile; spielt der Spielleiter mit, auch sein Ergebnis-Banner
  („Richtig! +1“). Die Verteilung je Antwort, Challenge und Entscheidungen bleiben.
- **Buzzer:** Spielt der Spielleiter mit, bekommt sein Buzzer die Hälfte des Bildschirms.
