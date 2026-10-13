# Buzzer

Android-App für Quiz-Buzzer-Runden: Ein Handy ist Spielleiter (Master), die anderen
sind Spieler. Verbunden wird über Google Nearby Connections (Bluetooth/WLAN, kein
Router nötig). Die Spielregeln stehen in [SPEZIFIKATION.md](SPEZIFIKATION.md).

## Aufbau

| Ordner | Inhalt |
|---|---|
| `core/` | Spiellogik in reinem Kotlin, ohne Android: Protokoll, Uhrenabgleich, Rangfolge, Master- und Spieler-Ablauf. Mit Unit-Tests. |
| `app/` | Android-App: Nearby-Verbindung, Oberfläche (Jetpack Compose), Berechtigungen, Updates. |
| `quiz/` | Fragenpakete für den Quiz-Modus (JSON, werden eingebaut und lassen sich nachladen). |

Die wichtigsten Dateien:

- `core/.../MasterEngine.kt` – Rundenablauf, Sammelfenster, Gleichstand und Sudden Death, Wertung
- `core/.../PlayerEngine.kt` – Freigabe zum vorgegebenen Zeitpunkt, Reaktionszeit am Touch-Ereignis, Frühstart
- `core/.../ClockSync.kt` – Uhrenabgleich nach NTP-Prinzip
- `app/.../NearbyLink.kt` – Gruppe eröffnen, suchen, beitreten, Nachrichten senden
- `core/.../QuizStats.kt` – Statistik je Frage, gelernte Schwierigkeit, Fragenauswahl
- `app/.../BuzzerViewModel.kt` – verbindet Nearby, Spiellogik und Oberfläche
- `app/.../AppUpdate.kt` – neuere Version prüfen, empfangen, laden und installieren

## Bauen und installieren

1. Projektordner in **Android Studio** öffnen (aktuelle Version, JDK 17 oder neuer).
2. Gradle-Sync abwarten.
3. App auf **mindestens zwei echte Handys** installieren. Nearby Connections funktioniert
   im Emulator nicht.

Oder auf der Kommandozeile (Android SDK nötig, `ANDROID_HOME` gesetzt):

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzungen auf den Handys: Android 7.0 oder neuer, Google Play Services,
Bluetooth und WLAN eingeschaltet (WLAN muss nicht verbunden sein).

## Tests der Spiellogik

```bash
cd core
../gradlew test
```

Die Tests simulieren Geräte mit stark abweichenden Uhren und Funk-Laufzeiten und prüfen
unter anderem: Reihenfolge nach Reaktionszeit statt Ankunftszeit, Frühstart,
Zeitlimit, Gleichstand mit Sudden Death, beide Startmodi und die Wertung.

## So wird gespielt

1. Spielleiter: Namen eingeben, **Gruppe eröffnen**.
2. Spieler: Namen eingeben, **Gruppe suchen**, Gruppe antippen. Beim Spielleiter erscheint
   die Beitrittsanfrage (Annehmen / Ablehnen).
3. Unter **Einstellungen** wählt der Spielleiter Startmodus, Zeiten, Wertung und ob er
   mitspielt.
4. Spielart wählen: **Buzzer** oder **Quiz**.
5. Buzzer: Frage stellen, **Buzzer frei** (Modus B) bzw. **Buzzer scharf** (Modus A); nach
   dem Einfrieren **Richtig** oder **Falsch**, dann **Nächste Runde**.
6. Quiz: Kategorien, Stufen, Anzahl, Antwortzeit und Wertung wählen, **Quiz starten**. Die App
   stellt die Fragen, wertet aus und zeigt nach jeder Frage die Auflösung.
