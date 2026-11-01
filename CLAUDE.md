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
- Vor jedem Build oben in `aenderungen/aenderungen.json` einen Eintrag `"build": 0` mit den Änderungen
  für die Spieler (kurz, verständlich, Deutsch) anlegen; der Workflow setzt die Nummer ein, macht daraus
  die Release-Notiz, und die App zeigt sie nach dem Update unter „Was ist neu?“. Nach dem Build die
  Nummer auch im Repository eintragen (Test `ChangelogFileTest` erlaubt nur oben eine 0).
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

## Sicherheits-Erinnerung (Wunsch vom 31.10.2026)

Solange in `OFFENE_PUNKTE.md` Punkt 106 („Schutz vor fremden Änderungen / Malware“) offene Kästchen hat,
erinnert Claude Axel **einmal pro Woche** daran (geplante Aufgabe; Stand der Kästchen nennen, nichts
umsetzen ohne Anweisung). Sind alle abgehakt, die Erinnerung beenden.

## Fragendatenbank (Wunsch vom 01.11.2026, ersetzt die Stufen-Angleichung vom 30.10.)

Drei Phasen, nacheinander. Vor **jedem Build** den Stand prüfen und die nächste Phase nur beginnen, wenn die
vorige erreicht ist; beim Wechsel Axel Bescheid geben.

1. **Auffüllen auf 120 Fragen je Kategorie.** Neue Fragen nur in **mittel und schwer** (dort braucht die
   Zielverteilung von Phase 2 mehr), je Kategorie höchstens 4 neue Fragen pro Build.
2. **Umverteilen der Stufen** auf **15 % leicht, 45 % mittel, 30 % schwer, 10 % Ingomania** (bei 120 Fragen:
   18 / 54 / 36 / 12). Methode **Ersetzen**: überzählige leichte und Ingomania-Fragen löschen, dafür neue mittlere
   bzw. schwere schreiben. **Höchstens 20 veränderte Fragen pro Build insgesamt** (gelöscht + neu, alle Kategorien
   zusammen). Zum Löschen bevorzugt die am häufigsten gespielten (`gespielt.json`, siehe unten), sonst die ältesten.
3. **Danach** keine Änderungen mehr an den festen Kategorien – nur noch „Aktuelles“ pflegen und die Spielzählung
   führen. Über den Austausch alter Fragen redet Axel später.

Prüfen vor jedem Build (Anzahl je Kategorie, Abweichung von der Zielverteilung):

```
python3 -c "import json;i=json.load(open('quiz/index.json'));Z={'leicht':.15,'mittel':.45,'schwer':.30,'ingomania':.10};[print(p['titel'][:28].ljust(28),len(f),{s:f'{sum(q[\"stufe\"]==s for q in f)}/{round(Z[s]*120)}' for s in Z}) for p in i['pakete'] for f in [json.load(open('quiz/'+p['datei']))['fragen']]]"
```

**„Aktuelles“** (läuft neben den Phasen und gilt dauerhaft):
- **Jede Woche 10 % der ältesten Fragen** (nach `erstellt`) durch frische ersetzen (geplante Aufgabe, montags).
  Neue Fragen zählen für Phase 1 und 2 mit (Stufen nach Zielverteilung wählen).
- **Sportanteil langsam auf höchstens 30 % senken:** beim Austausch zuerst alte Sportfragen entfernen und durch
  andere Themen ersetzen – Politik, Tagesschau-Meldungen, Gesellschaft/Gossip, Kunst, Kultur, Musik, Wissenschaft.
- Amerikanische Sportarten **nur Baseball, American Football und Basketball**; **kein Eishockey** (die vorhandenen
  Eishockey-Fragen werden bei den nächsten Rotationen zuerst ersetzt).
- Nur per Websuche geprüfte Ereignisse, `gueltigBis` setzen.

**Spielzählung:** Bei jedem „Diag“ und bei der wöchentlichen Rotation im privaten Repository `buzzer-diagnose`
`python3 -I statistik/zaehlen.py` laufen lassen (schreibt `statistik/gespielt.json`: wie oft jede Frage gespielt
wurde) und mit committen. Namen der Mitspieler gehören nicht ins öffentliche Repository.

- Format wie in `quiz/README.md`: erste Antwort richtig, Erklärung, Quelle, `erstellt` = Datum.
- IDs fortlaufend je Paket weiterzählen; keine Dubletten zu vorhandenen Fragen.
- Paket-`version` und `index.json` (Version, Fragenanzahl) erhöhen, Pakete neu mischen.
- „Aktuelles 2026“: nur per Websuche geprüfte Ereignisse, `gueltigBis` setzen.
- Danach `cd core && gradle test` (prüft alle Pakete), im Build-Commit erwähnen.
- Die neuen Fragen sind sofort eingebaut; über „Fragen aktualisieren“ erst nach dem Merge in `master`.
