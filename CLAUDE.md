# Arbeitsregeln für Claude (Buzzer-App)

Diese Regeln hat Axel festgelegt; sie gelten für jede Sitzung.

## Builds und Commits

- Gebaut wird nur auf Anweisung („Erstelle Build …“): GitHub Actions `android.yml` per
  `workflow_dispatch` auf dem Arbeits-Branch; die Build-Nummer ist die Run-Nummer.
- Sonst Commits mit `[skip ci]`; neue Wünsche und Testergebnisse in `OFFENE_PUNKTE.md` festhalten.
- Pull Requests und Merges nach `master` nur auf Anweisung.
- `PROMPT_SPEZIFIKATION.md` enthält zwischen „===== BEGINN SPEZIFIKATION.md =====“ und
  „===== ENDE SPEZIFIKATION.md =====“ immer eine aktuelle Kopie von `SPEZIFIKATION.md`.
- Öffentliches Repository: keine Tokens oder Geheimnisse.
- Antworten an Axel auf Deutsch.

## Fragendatenbank wächst mit jedem Build (Wunsch vom 16.10.2026)

Vor **jedem Build** kommt in **jeder bestehenden Kategorie** je **eine neue Frage pro
Schwierigkeitsstufe** dazu (leicht, mittel, schwer – also drei Fragen je Paket in `quiz/`):

- Format wie in `quiz/README.md`: erste Antwort richtig, Erklärung, Quelle, `erstellt` = Datum.
- IDs fortlaufend je Paket weiterzählen; keine Dubletten zu vorhandenen Fragen.
- Paket-`version` und `index.json` (Version, Fragenanzahl) erhöhen, Pakete neu mischen.
- „Aktuelles 2026“: nur per Websuche geprüfte Ereignisse, `gueltigBis` setzen.
- Danach `cd core && gradle test` (prüft alle Pakete), im Build-Commit erwähnen.
- Die neuen Fragen sind sofort eingebaut; über „Fragen aktualisieren“ erst nach dem Merge in `master`.
