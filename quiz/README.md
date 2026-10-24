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
- `stufe`: der `schluessel` einer Stufe aus `index.json` („stufen“) – derzeit `leicht`, `mittel`,
  `schwer`, `ingomania`. Die App passt sie anhand der Antworten an (`ingomania` nur für Fragen mit
  dieser Autoren-Stufe).

## Stufen und Kategorien verwalten

`index.json` enthält die Stufen und die Liste der Pakete (= Kategorien). Eine neue Stufe oder ein
neues Paket dort einzutragen genügt – die App zeigt es nach dem nächsten Build (eingebaut) oder
nach „Fragen aktualisieren“ (aus `master`) an, ohne Codeänderung.

```json
"stufen": [
  { "schluessel": "ingomania", "name": "Ingomania", "startwert": 0.12,
    "gemessenAb": 0.0, "nurAutor": true, "vorgewaehlt": false }
]
```

`startwert`: erwarteter Anteil richtiger Antworten vor der Messung; `gemessenAb`: ab diesem Anteil
gilt eine Frage nach der Messung mindestens als diese Stufe; `nurAutor`: die Messung vergibt die
Stufe nicht an Fragen anderer Stufen; `vorgewaehlt`: in der Grundauswahl des Quiz.

Die Prüfung `QuestionPackFilesTest` (Kern-Tests) stellt sicher, dass alle Pakete gültig sind.
