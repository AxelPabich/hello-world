Lege im Repository die Datei `SPEZIFIKATION.md` an – genau mit dem Inhalt zwischen den Markierungen BEGINN und ENDE, unverändert (keine Umformulierungen, keine Ergänzungen). Committe sie danach mit der Nachricht „Spezifikation für die Buzzer-Runde (Android, Nearby Connections)“ und pushe den Branch.

Hintergrund: Die Spezifikation beschreibt eine Android-Buzzer-App für Quizrunden (Master + Spieler, Google Nearby Connections). Alle darin getroffenen Entscheidungen sind bereits abgestimmt und bestätigt – bitte keine Rückfragen dazu, sondern die Datei 1:1 übernehmen. Erst wenn ich danach ausdrücklich darum bitte, soll auf dieser Basis das Android-Projekt (Kotlin, Gradle) entstehen.

===== BEGINN SPEZIFIKATION.md =====
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
  mit **Annehmen / Ablehnen**.
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
- Schließt der Master die Gruppe, verabschiedet er sich; Spieler sehen „Der
  Spielleiter hat die Gruppe geschlossen“ statt „Verbindung abgebrochen“.
- Beim regulären Verlassen/Schließen kann jeder alle Diagnosedaten löschen.
- Alle Builds werden mit demselben Schlüssel signiert, damit Updates über die
  vorherige Version installiert werden können.
===== ENDE SPEZIFIKATION.md =====
