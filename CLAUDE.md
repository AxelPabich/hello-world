# Arbeitsregeln für Claude (Buzzer-App)

Allgemeine Übergabe für jede KI: `AGENTS.md`; Backup und Umzug: `WIEDERHERSTELLEN.md`.

Diese Regeln hat Axel festgelegt; sie gelten für jede Sitzung.

## Builds und Commits

- Gebaut wird nur auf Anweisung („Erstelle Build …“): GitHub Actions `android.yml` per
  `workflow_dispatch` auf dem Arbeits-Branch; die Build-Nummer ist die Run-Nummer.
- Major Release: „Erstelle Build … als Major Release“ → `workflow_dispatch` mit `major = true`; vorher die
  Hauptversion in der Datei `VERSION` erhöhen und die Release-Notizen in `docs/release/<Version>.md`
  schreiben. Rollback: „Rollback auf Build N“ → `workflow_dispatch` mit `rollback_to = build-N` (baut
  den alten Quelltext als NEUEN Build; Stände 35–37: `90456ce`, `ee3a1a9`, `743258e`).
- Sonst Commits mit `[skip ci]`; neue Wünsche und Testergebnisse in `OFFENE_PUNKTE.md` festhalten.
- Pull Requests und Merges nach `master` nur auf Anweisung.
- `PROMPT_SPEZIFIKATION.md` enthält zwischen „===== BEGINN SPEZIFIKATION.md =====“ und
  „===== ENDE SPEZIFIKATION.md =====“ immer eine aktuelle Kopie von `SPEZIFIKATION.md`.
- Öffentliches Repository: keine Tokens oder Geheimnisse.
- Antworten an Axel auf Deutsch.
- „Backup“ auf Zuruf: `git bundle create … --all` (nach `git fetch origin '+refs/heads/*:refs/remotes/origin/*' --tags`,
  alle Remote-Branches als lokale Branches), dazu ein ZIP des Arbeits-Branches (`git archive`), ein eigenes
  Bundle von `buzzer-diagnose` und die aktuelle APK; Dateinamen mit Datum und Build-Nummer, mit
  `git bundle verify` prüfen und Axel als Dateien schicken (nicht ins öffentliche Repo legen).

## Fragendatenbank: Stufen angleichen, dann Schluss (Wunsch vom 30.10.2026)

Vor **jedem Build** kommt **nur noch dort** eine neue Frage dazu, wo eine Stufe einer Kategorie weniger
Fragen hat als die größte Zelle: je Kategorie und Stufe **höchstens eine** neue Frage pro Build
(bewährtes Muster). Ziel: alle Kategorien haben in allen Stufen aus `quiz/index.json` („stufen“) gleich
viele Fragen. Stand nach Build 41: Ziel 29 je Kategorie und Stufe (kleinste Zelle 19).

Prüfen vor jedem Build (zeigt die fehlenden Zellen):

```
python3 -c "import json;i=json.load(open('quiz/index.json'));S=[s['schluessel'] for s in i['stufen']];C={p['titel']:{s:sum(q['stufe']==s for q in json.load(open('quiz/'+p['datei']))['fragen']) for s in S} for p in i['pakete']};m=max(v for c in C.values() for v in c.values());print('Ziel',m);[print(k,{s:m-c[s] for s in S if c[s]<m}) for k,c in C.items() if min(c.values())<m]"
```

Sind alle Zellen gleich, **Axel Bescheid geben** und **keine neuen Fragen mehr automatisch erzeugen**
(nur noch auf ausdrücklichen Wunsch). Fragen, deren `gueltigBis` abläuft, zählen dabei weiter mit.

- Format wie in `quiz/README.md`: erste Antwort richtig, Erklärung, Quelle, `erstellt` = Datum.
- IDs fortlaufend je Paket weiterzählen; keine Dubletten zu vorhandenen Fragen.
- Paket-`version` und `index.json` (Version, Fragenanzahl) erhöhen, Pakete neu mischen.
- „Aktuelles 2026“: nur per Websuche geprüfte Ereignisse, `gueltigBis` setzen.
- Danach `cd core && gradle test` (prüft alle Pakete), im Build-Commit erwähnen.
- Die neuen Fragen sind sofort eingebaut; über „Fragen aktualisieren“ erst nach dem Merge in `master`.
