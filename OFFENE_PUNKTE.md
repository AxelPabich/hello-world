# Offene Punkte

Gesammelte Wünsche für den nächsten Build. Umgesetzt und gebaut wird erst auf
ausdrückliche Bitte.

Stand: Build 4 (Version 1.0.4) – alle sichtbaren Funktionen laufen auf den
Testgeräten (Samsung SM-X930 / Android 16, Samsung SM-T865 / Android 12).

## Diagnose

1. **Doppelte Einträge zusammenfassen**
   Der Master schickt die Anzeige eines Spielers mehrfach (z. B. bei jeder
   Statusänderung anderer Spieler). Im Spieler-Protokoll steht dann z. B.
   „Anzeige: GREEN Platz 1“ zweimal hintereinander. Gleiche aufeinanderfolgende
   Anzeigen nur einmal protokollieren.

2. **Zeitablauf ohne Reaktion protokollieren (Master)**
   Wenn ein Spieler bis zum Ende der Reaktionszeit nicht drückt, steht das bisher
   nur indirekt im Spieler-Protokoll („Anzeige: GRAY“). Im Master-Protokoll ergänzen,
   z. B. „Zeit abgelaufen: keine Reaktion von Peter“.

3. **Gleichstand und Sudden Death protokollieren (Master)**
   Z. B. „Gleichstand Axel/Peter (1 ms) → Sudden Death“ und das Ergebnis des
   Sudden Death.

4. **Auffällige Funk-Laufzeiten markieren**
   Im Test gab es einen Ausreißer von 939 ms (sonst ~200 ms). Laufzeiten deutlich
   über dem üblichen Wert im Protokoll hervorheben, z. B. „Hinweis: Funk-Laufzeit
   Peter 939 ms (sonst ~210 ms)“.

## Hinweise aus dem Test

- Uhrenabgleich: Bei gleichzeitigem Drücken auf zwei Geräten lagen die gemessenen
  Reaktionszeiten nur 1–10 ms auseinander (Funk-Laufzeit ~200 ms).
- Kurznachrichten als durchlaufender Chat im Spielfeld kommen gut an.
