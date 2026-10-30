# Backup wiederherstellen

Ein Backup besteht aus diesen Dateien (Datum im Namen):

| Datei | Inhalt |
|---|---|
| `buzzer-<Datum>.bundle` | Das komplette Git-Repository: alle Branches, alle Tags (`build-N`, `v2.0.N`), ganze Historie. |
| `buzzer-<Datum>-quelltext.zip` | Nur der aktuelle Stand des Arbeits-Branches als Ordner – zum Ansehen oder Hochladen bei einer KI ohne Git. |
| `buzzer-diagnose-<Datum>.bundle` | Das private Diagnose-Repository (Diagnoseberichte, Spielstatistik). **Enthält persönliche Daten der Mitspieler – nur privat ablegen.** |
| `buzzer-<Datum>.apk` | Die zuletzt gebaute App (falls beigelegt). |

## In ein neues GitHub-Repository einspielen

1. Auf GitHub ein **leeres** Repository anlegen (ohne README, ohne Lizenz).
2. Auf dem Rechner (Git muss installiert sein):

   ```
   git clone buzzer-<Datum>.bundle buzzer
   cd buzzer
   git remote set-url origin https://github.com/<Besitzer>/<Repository>.git
   git push --mirror origin
   ```

   `--mirror` überträgt alle Branches und Tags. Danach `git checkout claude/android-multicast-groups-9b1lkt`
   (Arbeits-Branch) bzw. `master`.
3. Auf GitHub unter **Settings → Actions → General**: Actions erlauben und bei „Workflow permissions“
   **Read and write permissions** wählen (der Workflow legt Release-Seiten an).
4. **Build-Nummern fortsetzen:** Unter **Settings → Secrets and variables → Actions → Variables** eine
   Variable `BUILD_OFFSET` anlegen mit der letzten Build-Nummer des alten Repositorys (steht im Namen des
   Backups bzw. auf dem Handy unter „Über“, z. B. `41`). Der Workflow rechnet Run-Nummer + `BUILD_OFFSET`;
   der erste Build im neuen Repository wird so z. B. Build 42. Ohne diese Variable begänne die Zählung bei
   1, und Android verweigert ein Update auf eine niedrigere Nummer.
5. Einen Build starten: **Actions → Android-Build → Run workflow** auf dem Arbeits-Branch.

Das Diagnose-Bundle genauso in ein **privates** Repository einspielen. In der App lässt sich das
Diagnose-Repository unter „Über & Diagnose“ neu einrichten (Token und `Besitzer/Repository`).

## Wenn das Repository einen anderen Namen bekommt

Die App fragt Updates, Fragenpakete und den Download-Link an einer festen Adresse ab. Sie steht an genau
einer Stelle: `app/src/main/java/de/pabich/buzzer/ProjectRepo.kt` (`NAME = "Besitzer/Repository"`, dazu der
Branch für „Fragen aktualisieren“). Dort ändern, einen Build erzeugen und diesen Build einmal auf alle
Handys bringen (Weitergeben per Bluetooth/QR-Code). Ab dann holen sich die Apps Updates aus dem neuen
Repository; ältere Builds fragen weiterhin das alte.

## Mit einer anderen KI weiterarbeiten

- Mit Git-Zugriff: das neue Repository verbinden; die KI liest `AGENTS.md` (allgemein) bzw. `CLAUDE.md`.
- Ohne Git: das ZIP hochladen und die KI zuerst `AGENTS.md`, `SPEZIFIKATION.md` und `OFFENE_PUNKTE.md`
  lesen lassen.

## Prüfen, ob das Bundle vollständig ist

```
git bundle verify buzzer-<Datum>.bundle
git bundle list-heads buzzer-<Datum>.bundle
```
