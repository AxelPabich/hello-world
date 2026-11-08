---
name: ci-run
description: Build (android.yml) auf dem Arbeits-Branch sicher per workflow_dispatch anstoßen und das Ergebnis auswerten – keine doppelten Läufe, Fehler zusammengefasst. Nutzen, nachdem Axel ausdrücklich einen Build angewiesen hat ("Erstelle Build …", "Rollback auf Build N", "… als Major Release"); löst selbst keinen Build aus eigenem Antrieb aus.
---

# Build sicher anstoßen und auswerten

Gilt für den Workflow `android.yml` in diesem Repository (siehe `CLAUDE.md`, Abschnitte „Builds und
Commits“ und „Build-Infrastruktur“). Nur ausführen, wenn Axel den Build bereits angewiesen hat.

1. **Laufenden Build ausschließen:** `mcp__github__actions_list` (`list_workflow_runs`) für `android.yml`
   auf dem aktuellen Arbeits-Branch, Status `in_progress`/`queued` filtern. Läuft oder wartet schon ein
   Lauf, das Axel melden (mit Link) und hier aufhören – keinen zweiten Build anstoßen.
2. **Vorbereitung je nach Auftrag:**
   - Normaler Build: oben in `aenderungen/aenderungen.json` steht bereits ein Eintrag `"build": 0` mit den
     Änderungen; sonst vorher anlegen.
   - Major Release: zuerst die Hauptversion in `VERSION` erhöhen, Release-Notizen in
     `docs/release/<Version>.md` schreiben, dann mit Input `major: true` starten.
   - Rollback: mit Input `rollback_to: "build-<N>"` starten (baut den alten Stand als neuen Build).
3. **Starten:** `mcp__github__actions_run_trigger` (`run_workflow`) für `android.yml` auf dem Arbeits-Branch,
   mit den passenden Inputs aus Schritt 2.
4. **Abfragen:** Die neue Run-ID über `mcp__github__actions_list` ermitteln, dann mit `mcp__github__actions_get`
   (`get_workflow_run`) in Abständen (z. B. 20 s, als Hintergrundaufgabe warten, nicht mit `sleep` blockieren)
   abfragen, bis `status: completed`.
5. **Bei Erfolg zusammenfassen:** Build-Nummer (= Run-Nummer), Release-Link
   (`https://github.com/<owner>/<repo>/releases/tag/build-<Nr>`), welche APK-Dateien entstanden sind. Danach
   die Build-Nummer in `aenderungen/aenderungen.json` eintragen (`"build": 0` → tatsächliche Nummer) und mit
   `[skip ci]` committen und pushen (siehe `CLAUDE.md`).
6. **Bei Fehler zusammenfassen:** Mit `mcp__github__actions_list` (`list_workflow_jobs`) den fehlgeschlagenen
   Job finden, mit `mcp__github__get_job_logs` die Logs holen und den entscheidenden Ausschnitt zeigen
   (fehlgeschlagener Schritt, Fehlermeldung).
   - **Bekannter Fall – Maven-429:** Scheitert `cd core && gradle test` mit „429“/„Too Many Requests“ beim
     Laden von Abhängigkeiten, das als Ursache benennen und vorschlagen, es in ein paar Minuten erneut zu
     versuchen – nicht an Abhängigkeits-Versionen oder Code ändern, um es zu umgehen.
   - Jeder andere Fehler: Ursache knapp beschreiben und auf Axels Anweisung warten, bevor etwas am Code
     geändert und erneut gebaut wird.

Antwort an Axel immer auf Deutsch, kurz und mit den konkreten Zahlen/Links.
