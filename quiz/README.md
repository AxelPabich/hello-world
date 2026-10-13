# Fragenpakete

Die Pakete werden beim Build in die App eingebaut (Assets). Zusätzlich lädt der Master unter
„Fragen aktualisieren“ die neuesten Pakete direkt von hier (Branch `master`) – dafür ist kein
neuer Build nötig. `index.json` listet alle Pakete mit Version; eine höhere Version ersetzt die
eingebaute.

Format je Frage:

```json
{ "id": "kueche-0012", "version": 1, "kategorie": "Küche & Genuss", "stufe": "schwer",
  "frage": "…", "antworten": ["richtig", "falsch", "falsch", "falsch"],
  "erklaerung": "…", "quelle": "https://…", "erstellt": "2026-10-13", "gueltigBis": null }
```

- Die **erste Antwort ist immer die richtige**; die App mischt die Reihenfolge für jeden Spieler.
- `id` bleibt bei Korrekturen gleich, `version` steigt. Ändert sich eine Antwort, beginnt die
  Statistik dieser Frage neu.
- `gueltigBis` (`JJJJ-MM-TT`) für Fragen zu aktuellen Themen – danach werden sie nicht mehr gezogen.
- `stufe`: `leicht`, `mittel` oder `schwer` – die App passt sie anhand der Antworten an.

Die Prüfung `QuestionPackFilesTest` (Kern-Tests) stellt sicher, dass alle Pakete gültig sind.
