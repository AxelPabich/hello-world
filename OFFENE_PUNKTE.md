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

## Offen

12. **Spielfläche immer noch zu groß (Tab S11 Ultra, Samsung-Taskleiste):** Auf dem
    Einstellungs-Bildschirm liegen „Abbrechen“ und „Übernehmen“ weiterhin unter der
    Taskleiste mit den System-Knöpfen (Screenshot vom 13.10., 00:41). Build 8 hat die
    Einstellungen vom Dialogfenster zur App-Seite gemacht und die Begrenzung zentral über
    `WindowInsets.safeDrawing` + `tappableElement` gesetzt – das reicht auf diesem Gerät
    offenbar nicht, vermutlich meldet die Taskleiste ihre Fläche nicht als Inset.
    Vorgehen für den nächsten Build:
    - Zuerst **messen**: Insets (Status-, Navigations-, Taskleiste, Aussparung), Fenstergröße
      (`currentWindowMetrics`) und maximale Größe (`maximumWindowMetrics`) ins
      Diagnose-Protokoll schreiben und in „Über & Diagnose“ anzeigen.
    - Daraus eine **eigene Begrenzung** ableiten, falls die Insets fehlen: Differenz aus
      Anzeigegröße und tatsächlich nutzbarer Fenstergröße bzw. sichtbarem Anzeigebereich
      (`getWindowVisibleDisplayFrame`) als Abstand verwenden.
    - Alle Bildschirme (Start, Suche, Spieler, Master, Einstellungen, Hilfe, Über,
      Dialoge) prüfen; bei Bedarf Edge-to-Edge abschalten.
    - Klären, ob der Screenshot von Build 8 stammt (Build-Nummer steht unter
      „Über & Diagnose“); er ist bildgleich zum vorherigen.

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
