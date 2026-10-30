# Übergabe für KI-Assistenten (Buzzer-App)

Diese Datei richtet sich an jeden KI-Assistenten (Claude, Codex, Cursor, Copilot, Gemini …), der an
diesem Projekt weiterarbeitet. `CLAUDE.md` enthält dieselben Arbeitsregeln für Claude Code; bei
Widersprüchen gilt die neuere Anweisung von Axel.

## Das Projekt in Kürze

- Android-App „Buzzer“ (Kotlin, Jetpack Compose) für Quiz- und Buzzer-Runden ohne Router: ein Handy ist
  Spielleiter, die anderen sind Spieler, verbunden über Google Nearby Connections (`P2P_STAR`).
- Besitzer und Ansprechpartner: Axel. **Antworten an Axel immer auf Deutsch.**
- Vollständige fachliche Beschreibung: [SPEZIFIKATION.md](SPEZIFIKATION.md) (nummerierte Abschnitte).
  Wünsche, Testergebnisse und Erledigtes: [OFFENE_PUNKTE.md](OFFENE_PUNKTE.md) (neueste Punkte oben im
  Abschnitt „Offen“). Release-Notizen: `docs/release/`.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `core/` | Spiellogik in reinem Kotlin ohne Android (Protokoll `MessageCodec`, `MasterEngine`, `PlayerEngine`, Uhrenabgleich, Quiz-Statistik) mit Unit-Tests. |
| `app/` | Android-App: `BuzzerViewModel.kt` verbindet Nearby (`NearbyLink.kt`), Spiellogik und Oberfläche (`ui/`). |
| `quiz/` | Fragenpakete (JSON) und `index.json` mit Stufen und Paketen; Format in `quiz/README.md`. |
| `.github/workflows/android.yml` | Build, Tests, Release-Seiten (`buzzer-latest`, `build-<Nr>`, `v<Version>.<Nr>`). |

Wichtig für die Kompatibilität: Nachrichten sind `TYP|k=v` (URL-kodiert); unbekannte Nachrichten werden
von älteren Apps ignoriert. Neue Funktionen müssen mit älteren Builds der Mitspieler zurechtkommen.

## Arbeiten und prüfen

- Tests der Spiellogik: `cd core && gradle test` (prüft auch alle Fragenpakete). Muss vor jedem Commit grün sein.
- Die App selbst wird über GitHub Actions gebaut (lokal: Android Studio, JDK 17). Kompilierfehler in `app/`
  zeigen sich erst im Build – Änderungen dort besonders sorgfältig lesen.
- Signatur: `app/buzzer-debug.keystore` liegt bewusst im Repo, damit jeder Build die installierten Apps
  ohne Datenverlust aktualisieren kann. Nicht ersetzen.
- Öffentliches Repository: keine Tokens oder Geheimnisse einchecken. Der Diagnose-Token lebt nur
  verschlüsselt auf dem Gerät des Spielleiters.

## Arbeitsregeln (von Axel festgelegt)

- Gebaut wird nur auf Anweisung („Erstelle Build …“): Workflow `android.yml` per `workflow_dispatch` auf dem
  Arbeits-Branch; die Build-Nummer ist die Run-Nummer. Major Release: Input `major = true`, vorher `VERSION`
  erhöhen und `docs/release/<Version>.md` schreiben. Rollback: Input `rollback_to = build-N`.
- Vor jedem Build: Änderungen oben in `aenderungen/aenderungen.json` als Eintrag `"build": 0` (siehe `CLAUDE.md`).
- Sonst Commits mit `[skip ci]`; neue Wünsche und Testergebnisse in `OFFENE_PUNKTE.md` festhalten.
- Pull Requests und Merges nach `master` nur auf Anweisung.
- `PROMPT_SPEZIFIKATION.md` enthält zwischen den Markern immer eine aktuelle Kopie von `SPEZIFIKATION.md`.
- Fragendatenbank: siehe den Abschnitt in `CLAUDE.md` (zurzeit: Stufen je Kategorie angleichen, danach
  keine automatischen neuen Fragen mehr).
- Diagnosen und Spielstatistik liegen im privaten Repository `buzzer-diagnose` (eigenes Backup).

## Backup und Umzug

Siehe [WIEDERHERSTELLEN.md](WIEDERHERSTELLEN.md).
