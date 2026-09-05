# Manueller Rauchtest (Schritt 14)

`./gradlew build` läuft grün (Lint + JVM-Unit-Tests + Debug-/Release-Assemble). **Stand
"LockMode/Threat-Protection-Ausbau" (2026-08-25): in der Cloud-Sandbox, die diese Runde
umgesetzt hat, war `./gradlew` nicht ausführbar** — der konfigurierte Gradle-Toolchain-Download
(JDK 25 via `api.foojay.io`) und das Android-Gradle-Plugin (`dl.google.com`) sind von der
Netzwerk-Policy dieser Sandbox blockiert (403), unabhängig von diesem Projekt. Alle Änderungen
dieser Runde sind deshalb nur durch sorgfältige manuelle Code-Durchsicht geprüft, **nicht**
tatsächlich kompiliert oder getestet — `./gradlew build` auf einer Maschine mit vollem
Internetzugriff nachholen, bevor diese Änderungen als verifiziert gelten. Was davon unabhängig
nicht automatisiert geprüft werden kann, ohne ein echtes Gerät/Emulator zu belegen — analog zum
Quellprojekt (`ConneXias-Framework/CLAUDE.md`, Abschnitt "Build & Test").

## Device-Owner-Setup

Auf einem frischen Testgerät/Emulator (noch kein Google-Konto eingerichtet — DO-Provisionierung
scheitert sonst):

```
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner de.ble1st.warden/de.ble1st.warden.admin.WardenDeviceAdminReceiver
```

Danach `WardenStatusActivity` (Launcher-Icon "Warden") öffnen und prüfen:

1. **WardenLock**: App aus dem Launcher öffnen — vor dem Dashboard muss ein Presence-Bildschirm
   ("Warden gesperrt") erscheinen (Biometrie automatisch angestoßen, alternativ Warden-PIN). Nach
   erfolgreichem Nachweis: App per Home-Taste in den Hintergrund schicken, kurz warten, wieder
   öffnen — der Presence-Bildschirm muss erneut erscheinen (jeder Resume verlangt einen frischen
   Nachweis). Danach: innerhalb der App zwischen Bildschirmen navigieren (z. B. Safeguards →
   Status) — dabei darf **kein** erneuter Presence-Bildschirm erscheinen (reine In-App-Navigation
   invalidiert die Sitzung nicht). Auf einem frischen Gerät **vor** der ersten PIN-Einrichtung darf
   WardenLock nicht blockieren (Bootstrap-Ausnahme) — erst nach dem Einrichten einer PIN greift der
   Presence-Bildschirm.
2. **DO-Status** wird als aktiv angezeigt, App-Version sichtbar.
3. Auf einem `userdebug`/`eng`-Build erscheint die "Debuggable OS"-Warnung (Konzept 2b/(5)) —
   auf einem `user`-Build nicht.
4. **Kamera-Schalter** — an/aus schalten, mit `adb shell dumpsys device_policy` gegenprüfen, dass
   `DISALLOW_CAMERA` tatsächlich gesetzt/entfernt wird.
5. **App-Verwaltung** öffnen — erwartet: vollständige installierte-Apps-Liste (nicht nur ein paar
   wenige — das würde auf eine fehlende `QUERY_ALL_PACKAGES`-Wirkung hindeuten, s. `AndroidManifest.xml`).
   Eine Fremd-App einfrieren/entfrieren, prüfen dass sie aus dem Launcher verschwindet/wieder
   erscheint.
6. **Sicherheits-Scanner** öffnen, Scanner-Schalter aktivieren, eine Test-App mit
   Geräteadministrator- oder Bedienungshilfen-Recht installieren und beobachten, dass sie nach dem
   nächsten periodischen Lauf (bis zu 15 Min, `SuspiciousAppScanWorker`) automatisch eingefroren
   und in der Funde-Liste auftaucht; über "Vertrauen" wieder freigeben.
7. **Sensible Aktion** öffnen, beide Presence-Wege durchspielen:
   - Biometrie (falls auf dem Gerät eingerichtet).
   - Warden-PIN (`WardenPinActivity`): Ersteinrichtung, danach falsche PIN mehrfach eingeben und
     den Anti-Hammering-Backoff beobachten (ab dem 5. Fehlversuch, s.
     `WardenAntiHammeringDecision`), danach PIN ändern.
   - **WardenLock-Wiederverwendung**: für REBOOT/LOCK_NOW/LOCKDOWN_MODE_ARM/MASTER_SWITCH_REVERT
     darf jetzt nur noch ein einzelner "Bestätigen"-Button erscheinen (kein zweiter Biometrie-/
     PIN-Prompt) — die App-Eintritts-Sitzung aus Schritt 1 deckt die Presence-Prüfung ab. Für
     WIPE_DATA müssen weiterhin beide alten Buttons ("Mit Biometrie"/"Mit Warden-PIN bestätigen")
     erscheinen.
8. **Offline-Failsafe** öffnen — Ablauf nur mit dem zugehörigen Ed25519-Schlüsselpaar aus
   `rust/engine/src/bin/failsafe_keytool.rs` sinnvoll durchspielbar; ohne hinterlegten Schlüssel
   muss die UI klar "kein Schlüssel konfiguriert" anzeigen, nie stillschweigend "erfolgreich".
   - **Geänderter Signieraufruf (2026-08-28):** die neue Geräte-PIN gehört seitdem mit in die
     signierte Nachricht, `sign` nimmt drei Argumente:
     `failsafe-keytool sign <secret_key_hex> <challenge_hex> <neue_geraete_pin>`. Auf dem Gerät
     muss **exakt dieselbe** PIN eingetippt werden.
   - **Gegenprobe (der eigentliche Zweck der Änderung):** dieselbe Response ein zweites Mal mit
     einer *abweichenden* Geräte-PIN einreichen — muss "Response ungültig" liefern, nicht die PIN
     setzen. Vorher ging das durch, weil die Signatur nur die Challenge abdeckte.
   - **Challenge ist ein Einmal-Nachweis:** nach einem akzeptierten Durchlauf muss ein erneutes
     Einreichen derselben Response "Keine Challenge ausstehend" ergeben — auch dann, wenn der
     Geräte-PIN-Reset selbst fehlgeschlagen ist (die UI weist in dem Fall explizit auf die neu zu
     erzeugende Challenge hin).
9. **Log-Ansicht** über den entsprechenden Button öffnen — verlangt denselben Presence-Nachweis
   wie "Sensible Aktion", zeigt danach die Hash-Ketten-Einträge.
10. Boot-Reconciliation: Gerät neu starten, vor dem Entsperren prüfen (`adb logcat` direkt nach
    Boot, noch vor PIN/Muster-Eingabe), dass `RegistryReconciliationReceiver` feuert und die
    Kamera-/Bildschirm-Sicherungen aus dem persistierten Soll-Zustand wiederherstellt — das ist der
    eigentliche Zweck von `ACTION_LOCKED_BOOT_COMPLETED` + `directBootAware="true"`.
11. **App-Lock (LockMode) über Sentinel** — Safeguards ▸ App-Lock (LockMode). Seit "Sentinel:
    eigenständige Kiosk-PIN-App" (2026-08-26) läuft der eigentliche Kiosk-Zustand in einer
    separaten APK, nicht mehr in Warden selbst — s. Abschnitt "Bewusst nicht scharf geschaltet"
    unten für die Begründung, warum `startLockTask()` selbst weiterhin nicht real ausgelöst wird.
    - **Sentinel installieren**: "Installieren" antippen — Status muss von "Nicht installiert" auf
      "Installiert, Version …" wechseln (kein Bestätigungsdialog, Silent-Install per Device-Owner-
      Recht). "Status prüfen" muss den aktuellen `PackageManager`-Zustand live nachladen. Mit
      `adb shell pm list packages | grep sentinel` gegenprüfen.
    - Notruf-Drill bestätigen (Bestätigungstext `NOTRUF GEPRÜFT` eintippen) — Status wechselt auf
      "Seit …". "Zurücksetzen" muss den Status sofort wieder auf unbestätigt setzen und den
      Auto-Engage-Schalter mit ausschalten.
    - Manueller Weg: Sensible Aktion ▸ "App-Lock (Lock-Task) jetzt aktivieren" auswählen,
      Bestätigungstext `LOCKTASK` eintippen, bestätigen — auf einem Debug-Build muss
      `describeDecision` "Debug-Build — destruktive Kommandos hart abgeschaltet" zeigen, **nicht**
      real `SentinelLockdownEngager.engage()` auslösen (Sentinels `SentinelActivity` startet in
      diesem Fall gar nicht).
    - Automatischer Weg (zusätzlich zum Drill: Lockdown-Modus scharf UND "App-Lock automatisch bei
      kritischem Fund" aktiviert): einen `DEVICE_ADMIN_NEWLY_ACTIVATED`/
      `ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED`/`SIGNING_CERT_CHANGED`/`VERSION_DOWNGRADED`-Fund
      auslösen (z. B. Test-App mit Geräteadmin aktivieren), beobachten, dass
      `WardenLockTaskPendingEngageStore` eine Anforderung vormerkt und beim nächsten Öffnen von
      Warden (`consumePendingLockTaskEngage`) verarbeitet wird — auf dem Debug-Testgerät weiterhin
      hart blockiert (`DestructiveCommandGuard`), im Audit-Log aber sichtbar als "Gate verweigert".
    - "Sentinel (Kiosk-App)"-Statuszeile (nur die DPM-Whitelist-Autorisierung, nicht ob Sentinel
      *gerade tatsächlich* im Kiosk-Zustand ist, s. `loadSentinelLockTaskAuthorizedSafely`-
      Kommentar in `WardenStatusActivity.kt`) aktualisiert sich erst beim nächsten Öffnen des
      Safeguards-Bildschirms, nicht live während `SensitiveActionActivity` offen ist.
    - **Kein "App-Lock beenden"-Button auf Wardens Seite mehr** — anders als vor diesem Port kann
      Warden den Kiosk-Zustand nicht mehr selbst beenden (Warden ist ja nicht mehr die eingesperrte
      App). Der einzige Ausweg ist Sentinels eigene, separate PIN direkt auf dem Gerät.
    - **Kiosk ohne eingerichtete Sentinel-PIN muss verweigert werden (2026-08-28):** frisch
      installiertes Sentinel, noch keine Sentinel-PIN vergeben, dann `LOCKDOWN_TASK_ENGAGE`
      auslösen. Erwartet: **kein** Kiosk-Zustand, stattdessen im Warden-Audit-Log
      "Sentinel hat das Scharfschalten abgelehnt: keine benutzbare Sentinel-PIN eingerichtet —
      Kiosk läuft NICHT, Wächter wird entschärft" und anschließend eine wieder leere
      Lock-Task-Whitelist (`adb shell dumpsys device_policy | grep -i locktask`). Vorher startete
      der Kiosk und bot dort die PIN-Ersteinrichtung an — also seinen eigenen Ausstieg.
    - **Sentinel-PIN zuerst vergeben:** Sentinel einmal ohne Engage öffnen, PIN setzen, danach den
      Kiosk regulär durchspielen. Erst dieser Weg testet den Kiosk, der eigentlich gemeint ist.
12. **Real-Time Threat Protection**: eine Test-APK per `adb install` nachinstallieren, während
    Warden im Hintergrund läuft — beobachten, dass die Sicherheitsbenachrichtigung/der
    Sicherheits-Scanner-Fund binnen Sekunden erscheint (`PackageChangeReceiver` →
    `SuspiciousAppScanWorker.scheduleImmediate`), nicht erst nach bis zu 15 Minuten.
13. **Permission-Audit** öffnen, "Scannen" antippen — erwartet: Liste installierter Fremd-Apps mit
    Anzahl gefährlicher/spezieller Rechte, "Details" zeigt die einzelnen Rechtenamen, Apps mit ≥5
    gefährlichen Rechten sind rot markiert.
14. **Performance-Monitor** öffnen — erwartet: Speicherbalken (`ActivityManager.MemoryInfo`),
    Akkustand/Temperatur/Lade-Status (`BatteryManager`), nach ausreichend Wartezeit (mehrere
    `BatterySamplingWorker`-Läufe, 30-Minuten-Intervall) eine Drain-Rate. App-Aktivität zeigt
    "Nutzungsdatenzugriff nicht erteilt", bis unter Einstellungen manuell freigegeben — danach
    Vordergrund-Nutzungszeit je App, verdächtige Pakete (aus dem Sicherheits-Scanner) mit ⚠
    markiert.
15. **Entwickleroptionen/USB-Debugging sperren** (Safeguards ▸ Alltagsbetrieb) — **nur auf einem
    Gerät aktivieren, das nicht mehr per USB/adb entwickelt wird** (kappt vermutlich sofort die
    adb-Verbindung und lässt sich laut Android-Dokumentation nicht mehr über die
    Entwickleroptionen zurücknehmen). Auf dem Haupt-Testgerät nur den Bestätigungsdialog bis zum
    Abbrechen durchspielen, nicht tatsächlich bestätigen.

## Offene Prüfprozeduren (vorbereitet 2026-08-28, noch nicht durchgeführt)

Elf Pfade sind gebaut und unit-getestet, aber nie real ausgelöst worden. Jeder Abschnitt nennt
die Vorbedingungen, die konkreten Schritte und — wo es schon Fehlversuche gab — was **nicht**
funktioniert, damit derselbe Weg nicht zweimal probiert wird.

### P-1 — Watchdog-Eskalation: 3 Sentinel-Prozesstode in 60 s

Prüft `SentinelWatchdogController`/`SentinelDeathWatchdog` → `SentinelWatchdogDecision.escalate()`.
Die reine Entscheidungslogik ist JVM-unit-getestet; ungeprüft ist, ob drei echte Prozesstode am
Gerät auch drei `binderDied()`-Aufrufe erzeugen und die Eskalation real auslösen.

**Vorbedingungen:** Non-Debug-Build (`DestructiveCommandGuard` blockiert `engage()` auf jedem
Debug-Build), bestätigter Notruf-Drill, Sentinel installiert, Kiosk per `LOCKDOWN_TASK_ENGAGE`
scharf. `adb` als Rettungsleine angeschlossen lassen.

**Bereits gescheitert — nicht erneut probieren (2026-08-26):**
- `am force-stop de.ble1st.warden.sentinel` — versetzt das Paket in den dauerhaften
  Stopped-State; ein Rebind ist danach überhaupt nicht mehr möglich, der Zähler kommt nie über 1.
- `am crash de.ble1st.warden.sentinel` — nur der erste Aufruf tötet den Prozess wirklich, danach
  greift Androids eigene Crash-Loop-Drosselung und die Aufrufe verpuffen still.
- `kill -9` aus der Shell — `Operation not permitted` (fremde UID).

**Empfohlener Weg stattdessen:** einen temporären, `de.ble1st.warden.sentinel.permission.ENGAGE`-
geschützten Debug-Auslöser in `SentinelActivity` einbauen, der `Process.killProcess(Process.myPid())`
aufruft, und ihn dreimal per `adb shell run-as de.ble1st.warden.sentinel am start --user 0 -a
<action> -n de.ble1st.warden.sentinel/.SentinelActivity` feuern (`run-as` + `--user 0` ist der
Weg, der bei den Netz-Sperre-Tests als einziger gegen einen permission-geschützten Einstieg
funktioniert hat). Zwischen den Aufrufen jeweils warten, bis der Watchdog neu gebunden hat —
sonst zählt Warden weniger Tode als ausgelöst. Der Auslöser muss danach wieder raus, wie die
temporäre Instrumentierung der Netz-Sperre auch.

**Erwartetes Ergebnis:** nach dem dritten Tod innerhalb von 60 s zieht Warden die Lock-Task-
Autorisierung zurück — `adb shell dumpsys device_policy` zeigt keine `LockTaskPolicy` für
`de.ble1st.warden.sentinel` mehr, und die Log-Einsicht in Warden enthält den Eskalationseintrag.

### P-2 — Quick-Settings-Kachel und STRICT-Drill-Frische

**Kachel** (`SentinelQuickTile`, alle drei Auslöse-Profile): Kachel einmal aus der
Schnelleinstellungen-Bearbeitung hinzufügen, dann je Profil (Safeguards ▸ App-Lock) antippen —
- STRICT: Kachel darf **nie** direkt auslösen, sondern muss `SensitiveActionActivity` mit
  vorausgewählter Aktion öffnen (dort zusätzlich voller Biometrie-/PIN-Pfad plus Kühlzeit).
- STANDARD: Ja/Nein-Dialog unmittelbar vor dem Scharfschalten.
- FAST: löst sofort aus, nur haptisches Feedback.
Ungeprüft ist bisher ausschließlich die Kachel-eigene Verdrahtung (`unlockAndRun`/
`startActivityAndCollapse`) — deshalb einmal ausdrücklich **bei gesperrtem Bildschirm** antippen,
nicht nur bei entsperrtem Gerät. Der dahinterliegende Pfad
(`WardenLockTaskPendingEngageStore` → `WardenStatusActivity.performPendingLockTaskEngage()` →
`DestructiveCommandGuard`) ist derselbe wie beim Dashboard-Button und bereits live verifiziert.

**Drill-Frische unter STRICT** (`WardenLockTaskDrillFreshnessDecision`, 30-Tage-Grenze): Bit und
Zeitstempel liegen in den SharedPreferences `warden_lock_task_drill`
(`confirmed`/`confirmed_at_millis`). Zwei Wege, den Ablauf zu erzwingen:
1. Debug-Build: `adb shell run-as de.ble1st.warden --user 0 cat
   /data/data/de.ble1st.warden/shared_prefs/warden_lock_task_drill.xml`, `confirmed_at_millis` auf
   einen über 30 Tage alten Wert setzen, danach das Gerät neu starten — SharedPreferences werden
   im Prozess gecacht, ein bloßes Zurückwechseln in die App liest die Datei nicht neu.
2. Non-Debug-Build: Systemzeit um 31 Tage vorstellen. Vorher den Safeguard "Datum/Uhrzeit nicht
   ändern lassen" (`UserRestrictionSafeguard.configDateTimeDisabled`) deaktivieren, sonst lässt
   das Gerät die Änderung gar nicht zu — und hinterher beides zurücksetzen.

**Erwartetes Ergebnis:** unter STRICT verweigert der Auslöser den Start und verlangt eine erneute
Drill-Bestätigung; unter STANDARD/FAST bleibt die alte Bestätigung unverändert gültig (dort wird
die Frische bewusst nicht geprüft).

### P-3 — Factory Reset Protection erneut verifizieren

Der bisher einzige echte Test (2026-08-25, SM-A156B) ist **fehlgeschlagen**: Policy war laut
`dumpsys device_policy` korrekt gesetzt (`factoryResetProtectionEnabled=true`, Konto vorhanden),
der Recovery-Wipe lief bei gesperrtem Bootloader — und die Ersteinrichtung fragte trotzdem kein
Konto ab. Bis zu einer erfolgreichen Wiederholung gilt dieser Safeguard nicht als Diebstahlschutz.

**⚠ Dieser Test zerstört den Gerätezustand vollständig** (Werksreset, Device Owner und Warden-PIN
weg, danach volle Neuprovisionierung nötig) — nicht nebenbei einplanen.

**Zum Google-Konto — der Teil, an dem dieser Test scheitern kann, bevor er etwas aussagt:**
- Es muss ein **echtes Google-Konto** sein; Samsung- oder andere Herstellerkonten akzeptiert der
  FRP-Agent nicht.
- Passwort **und** zweiter Faktor müssen außerhalb dieses Geräts verfügbar sein. Liegt der zweite
  Faktor nur auf dem Testgerät, ist es nach dem Wipe genau so gesperrt, wie es der Test beweisen
  sollte — nur eben dauerhaft.
- Das Konto muss zum Zeitpunkt des Wipes noch existieren; ein zwischenzeitlich gelöschtes Konto
  macht das Gerät nicht frei, sondern unbrauchbar.
- Google-Play-Dienste müssen installiert sein (die UI warnt sonst bereits) — ohne FRP-Agent wird
  die Policy zwar gesetzt, aber niemand setzt sie durch.

**Schritte:** Konto unter Safeguards ▸ "Entsperrkonto nach Wipe" eintragen und **"Speichern"
antippen** (Texteingabe allein persistiert nichts, der abhängige Schalter bleibt sonst
deaktiviert) → Schalter "Nach Recovery-Wipe Konto verlangen" aktivieren → per
`adb shell dumpsys device_policy | grep -A5 FactoryResetProtection` bestätigen, dass Policy und
Konto wirklich gesetzt sind → **danach mehrere Stunden, besser einen Tag warten und das Gerät
zwischendurch neu starten** (die Durchsetzung hängt an einem asynchronen GMS-Round-Trip, den
`isActive()` von außen nicht bestätigen kann — beim Fehlversuch 2026-08-25 lag zwischen Setzen und
Wipe nur wenig Zeit, das ist der plausibelste Unterschied für einen zweiten Anlauf) → erst dann
den Recovery-Wipe auslösen.

**Erwartetes Ergebnis:** die Ersteinrichtung nach dem Wipe verlangt genau dieses Google-Konto.
Tut sie es wieder nicht, ist der Befund bestätigt und der Safeguard sollte in der UI dauerhaft als
"auf dieser Hardware wirkungslos" markiert bleiben statt weiter als Schutz zu gelten.

### P-4 — Automatische Profilumschaltung nimmt keine manuelle Härtung zurück

Prüft die Korrektur von Befund Q-1 (2026-08-28) auf dem echten Gerät. Der Fehler war nur über die
Uhr zu sehen, deshalb wird das Nachtfenster hier bewusst auf die nächsten Minuten gelegt statt auf
22:00 gewartet.

1. Automatische Profilumschaltung einschalten, Nachtprofil **Reise**, Tagesprofil **Alltag**,
   Nachtfenster so setzen, dass gerade **Tag** gilt. Einen Lauf abwarten (der Worker läuft alle
   15 Minuten) — im Audit-Log muss "Profil automatisch auf Alltag geschaltet (Zeitplan)" stehen.
2. Jetzt von Hand auf **Maximal** schalten und mit `adb shell dumpsys device_policy` an einem
   Maximal-Merkmal bestätigen, dass es wirkt (z. B. `no_config_vpn` in den User-Restrictions).
3. Nachtfenster so verschieben, dass **jetzt Nacht** ist, und wieder einen Lauf abwarten.

**Erwartetes Ergebnis:** Maximal bleibt stehen, das Nachtprofil Reise wird **nicht** angewendet;
`no_config_vpn` ist weiterhin gesetzt. Vor der Korrektur wurde an dieser Stelle heruntergeschaltet.

**Gegenprobe, dass der Zeitplan noch funktioniert:** dieselbe Konfiguration ohne den manuellen
Eingriff aus Schritt 2 — dann muss die Umschaltung Alltag ↔ Reise regulär stattfinden, weil das
wirkende Profil in dem Fall von der Automatik selbst stammt.

**Zweite Gegenprobe (Verschärfen bleibt immer erlaubt):** von Hand auf **Alltag**, dann
"Bei kritischem Fund auf Maximal" einschalten und einen kritischen Fund erzeugen (Testpaket mit
geändertem Signaturzertifikat, s. Schritt 5). Erwartet: die Automatik schaltet trotz der manuellen
Lockerung auf Maximal hoch.

### P-5 — Audit-Log: keine Lese-Einträge mehr, Aufbewahrungsgrenze prüfbar

Prüft die Korrektur der Befunde Q-2/Q-3 (2026-08-28). Beides ist am fertigen Gerät nur über das
Log selbst sichtbar.

1. Log-Einsicht öffnen (Presence bestätigen), die höchste Sequenznummer notieren.
2. Safeguards-Screen öffnen, einmal durchscrollen, zurück, ein Profil anwenden.
3. Log-Einsicht erneut öffnen.

**Erwartetes Ergebnis:** die Sequenznummer ist um wenige Einträge gewachsen (Profil-Apply,
Schaltvorgänge) — **keine** `cmd=isSafeguardActive`/`cmd=safeguardStates`-Zeilen. Vorher kamen pro
Bildschirmaufbau 33 Lese-Einträge dazu. Der Screen muss außerdem kurz "lädt" zeigen statt beim
Öffnen zu stocken; ein leerer Zustand ohne Ladeanzeige wäre ein Fehler (dann verwechselt die UI
"lädt noch" mit "nicht lesbar").

**Abgelehnter Lesezugriff wird weiterhin protokolliert:** das ist der Teil, der bleiben muss. Am
einfachsten über das Rate-Limit zu provozieren (sehr schnelles wiederholtes Öffnen/Wechseln); im
Log muss dann `allowed=false` mit `class=READ` auftauchen.

**Aufbewahrungsgrenze:** mit einem Wegwerf-Build und `LogStorage.KEEP_ARCHIVED_LOG_SEGMENTS = 1`
sowie `HashChainLogStore.DEFAULT_SEGMENT_CAPACITY = 5` genug Einträge erzeugen, dass mehrfach
rotiert wird. Erwartet: die Log-Einsicht zeigt weiterhin "Kette gültig" **und** die Zeile
"Ältere Einträge bis #N wurden nach der Aufbewahrungsgrenze verworfen". Meldet sie stattdessen
"Kette gebrochen", ist der Retention-Anker nicht geschrieben worden — das wäre der schwerwiegende
Fehlerfall, weil dann eine reguläre Kürzung wie eine Manipulation aussieht.

### P-6 — Benachrichtigungsaktionen verlangen jetzt einen Presence-Nachweis

Prüft die Korrektur von Befund S-5 (2026-08-28). Braucht eine zweite, unwichtige Test-App auf dem
Gerät (Wegwerf-APK, kein Geräteadmin) als Ziel.

1. Verdachtsscanner einschalten (Safeguards ▸ Sicherheits-Scanner), einen Fund für die Test-App
   provozieren (z. B. `adb install` einer signaturunbekannten APK).
2. Warden vollständig verlassen (Home-Taste, nicht nur "zurück"), damit
   `WardenLockSession` invalidiert.
3. In der Benachrichtigungsschublade "Deinstallieren" antippen.

**Erwartetes Ergebnis:** **kein** sofortiger Deinstallations-Dialog des Systems — stattdessen
öffnet sich Wardens eigener Presence-Screen (`WardenLockActivity`, Biometrie oder Warden-PIN wie
beim App-Start). Erst nach erfolgreichem Nachweis erscheint "Deinstallieren bestätigen" mit
Paketname; erst der zweite Tap auf "Bestätigen" löst die echte Deinstallation aus. Ein
abgebrochener Nachweis (Zurück-Geste am Presence-Screen) darf **nichts** auslösen — die Test-App
muss danach noch installiert sein. "Daten löschen" analog prüfen. "Einfrieren" bleibt bewusst ein
einzelner Tap ohne Presence-Schritt (reversibel).

### P-7 — Auto-Einfrieren wirkt nicht mehr auf reine Info-Funde

Prüft die Korrektur von Befund S-7 (2026-08-28).

1. Eine Test-App mit unbekannter Installationsquelle (`adb install`, nicht über den Play Store)
   und ohne weitere Signale installieren — erzeugt nur `UNKNOWN_INSTALL_SOURCE` (Stufe Info).
2. "Automatisch einfrieren" im Sicherheits-Scanner einschalten (löst laut Klassendoc sofort einen
   `scanAndEnforce()`-Lauf aus).

**Erwartetes Ergebnis:** die Test-App bleibt **nicht** eingefroren — der Fund erscheint weiterhin
in der Funde-Liste und in der Benachrichtigung, aber `AppManagementScreen` zeigt sie als aktiv.
Gegenprobe mit einer Test-App, die zusätzlich Geräteadmin-Fähigkeiten deklariert (Stufe Warnung):
die muss weiterhin automatisch eingefroren werden (bzw. an der bekannten OS-Grenze für
deklarierte Geräteadmins scheitern, s. `AppFreezeManager`-Klassendoc — nicht am neuen Filter).

### P-8 — DPM-Log-Callbacks laufen jetzt asynchron (`goAsync()`)

Prüft die Korrektur von Befund Q-4 (2026-08-29): `onSecurityLogsAvailable`/
`onNetworkLogsAvailable` blockieren das Broadcast-Fenster nicht mehr, verarbeiten die Batches aber
weiterhin vollständig.

1. Sicherheits-/Netzwerk-Logging aktivieren (Safeguards ▸ entsprechende Schalter).
2. Einen Batch auslösen — z. B. `adb shell am start`/`adb install` einer beliebigen Test-App
   (erzeugt Netzwerk-/Security-Log-Ereignisse) und kurz warten, bis das OS `onNetworkLogsAvailable`
   ruft.
3. `adb logcat -s WardenDeviceAdmin` parallel mitlaufen lassen.

**Erwartetes Ergebnis:** die Log-Zeile "… Ereignisse gespeichert" erscheint weiterhin zuverlässig,
und die Ereignisse tauchen in der Log-Ansicht (Systemereignisse) auf — wie vor der Änderung. Der
sichtbare Unterschied ist negativ: **kein** ANR-Dialog und keine spürbare Verzögerung, selbst wenn
kurz hintereinander mehrere Batches eintreffen (z. B. durch mehrere `adb install`s). Ein
fehlendes `pendingResult.finish()` würde sich als "Context.startForegroundService() not allowed"-
artige Folgefehler oder als vom OS gemeldetes "BroadcastReceiver did not call finish()" in Logcat
zeigen — sollte nicht auftreten.

### P-9 — Fehlgeschlagene sensible Aktionen zeigen jetzt einen Fehler statt "real ausgeführt"

Prüft die Korrektur von Befund Q-5 (2026-08-29). Der sicherste reproduzierbare Fehlschlag ohne
echten Kiosk-Einstieg: `LOCKDOWN_TASK_ENGAGE` auslösen, **ohne** dass Sentinel installiert ist.

1. Sicherstellen, dass die Sentinel-App **nicht** installiert ist (`adb shell pm list packages |
   grep sentinel` liefert nichts; ggf. vorher deinstallieren — Warden erlaubt das über die
   Sentinel-Uninstall-Protection nur, solange Sentinel selbst nie installiert war).
2. **Sensible Aktion** öffnen, "App-Lock (Lock-Task) jetzt aktivieren" wählen, Bestätigungstext
   `LOCKTASK` eingeben, Presence erbringen.

**Erwartetes Ergebnis:** die Activity zeigt jetzt
"⚠ Bestätigt, aber Ausführung fehlgeschlagen: SentinelLockdownEngager.engage() abgelehnt: Sentinel
nicht installiert." — **nicht** mehr "✓ Bestätigt — real ausgeführt und protokolliert." Der
Audit-Log-Eintrag zeigte diesen Fehlschlag schon vorher korrekt an; neu ist, dass die UI ihn jetzt
ebenfalls zeigt. Denselben Unterschied zeigt der Dashboard-Kurzweg "Kiosk jetzt" (falls unter
`LockdownTriggerProfile.STANDARD`/`FAST` konfiguriert).

### P-10 — SIM-Fingerabdruck bleibt über das Boot-Fenster hinweg stabil

Prüft die Korrektur von Befund Q-6 (2026-08-29).

1. SIM-Wechsel-Erkennung aktivieren, Reaktion auf "Nur melden" belassen (nicht "Neustart" — sonst
   verdeckt ein echter Neustart genau das Signal, das hier beobachtet werden soll).
2. Gerät neu starten (`adb reboot`), entsperren, App öffnen bzw. warten, bis der Prozess neu
   hochfährt.
3. Sofort nach dem Boot **und** noch einmal nach ca. einer Minute die Log-Ansicht (Audit-Log)
   prüfen.

**Erwartetes Ergebnis:** **kein** "SIM-Wechsel erkannt"-Eintrag direkt nach dem Boot, obwohl sich
zwischen dem (verzögerten) Start-Prüflauf und dem nächsten periodischen Lauf die Carrier-Config
zwischenzeitlich vervollständigt haben kann — der reduzierte Fingerabdruck (MCC/MNC + Carrier-ID
nur wenn bekannt) und die 30-Sekunden-Verzögerung von `SimChangeStartupWorker` sollen genau das
verhindern. Gegenprobe (nur wenn eine zweite Test-SIM verfügbar ist): tatsächlich die SIM
wechseln — ein echter "SIM-Wechsel erkannt"-Eintrag muss weiterhin erscheinen.

### P-11 — Sentinel meldet seinen PIN-Zustand von sich aus

Prüft Vorschlag U-8 (2026-08-29). Der Kanal ist `signature`-geschützt, lässt sich also **nicht**
per `adb shell am broadcast` nachstellen — der Test muss über Sentinel selbst laufen.

1. Sentinel installieren (Safeguards ▸ Kiosk ▸ "Installieren"), aber **noch keine** Sentinel-PIN
   einrichten. Safeguards öffnen.
2. Erwartet: die Zeile "Sentinel-PIN eingerichtet" steht auf **"Unbekannt — Sentinel hat sich noch
   nicht gemeldet."** — nicht auf "nein". Der Unterschied ist der Kern des Vorschlags: Warden
   kann Sentinels PIN-Blob nicht lesen und darf das Fehlen nicht raten.
3. Sentinel öffnen (`adb shell am start -n de.ble1st.warden.sentinel/.SentinelActivity` funktioniert
   nicht — die Activity ist permission-geschützt; stattdessen über den Kiosk-Weg oder Sentinels
   eigenen Einstieg), wieder verlassen, Safeguards erneut öffnen.
4. Erwartet: die Zeile steht jetzt rot auf **"NEIN — Sentinel würde jedes Scharfschalten
   ablehnen."**
5. In Sentinel eine PIN einrichten, Sentinel verlassen, Safeguards erneut öffnen.
6. Erwartet: **"Ja — Sentinel hat eine benutzbare PIN gemeldet."** Genau dieser Schritt prüft den
   `onPause()`-Meldezeitpunkt: nach der Ersteinrichtung kommt kein weiteres `onResume()` mehr.

**Zusätzlich prüfen:** im Audit-Log darf **kein** Eintrag für diese Meldungen stehen (sie sind
Zustand, kein Ereignis — sonst verdrängen sie bei jedem Sentinel-Aufruf die Einträge, für die das
Log da ist), und ein laufender Kiosk darf durch sie **nicht** beendet werden. Letzteres lässt sich
beim regulären Kiosk-Drill mitprüfen: während der Kiosk aktiv ist, kommt die Meldung ebenfalls an —
der Wächter muss scharf bleiben.

### P-12 — Mobilfunkzellen-Auffälligkeitserkennung

Prüft die 2026-08-29 umgesetzte Funktion (Feature 2 aus `docs/umsetzungsplan-7-features.md`, lokal
statt gegen den im Plan fälschlich vorausgesetzten "Barbican-Server" umgesetzt). Ein echter
IMSI-Catcher lässt sich naturgemäß nicht auf Bestellung erzeugen — dieser Test prüft deshalb nur
die Mechanik (Baseline, Persistenz, Benachrichtigung, Reaktionskette), nicht die Trefferquote gegen
eine echte Fake-Basisstation.

1. Einstellungen ▸ "Reaktion auf Mobilfunkzellen-Auffälligkeiten" auf "Nur melden" stellen.
2. Erwartet: sofort ein Audit-Log-Eintrag "Mobilfunkzellen-Baseline gesetzt". Falls stattdessen gar
   nichts passiert: `adb shell dumpsys package de.ble1st.warden | grep ACCESS_FINE_LOCATION` prüfen
   (Selbstfreigabe fehlgeschlagen?) und ob der System-Standortschalter überhaupt an ist — beides
   sind laut `CellObservationReader`-Klassendoc bewusste, stille Abbruchbedingungen ohne Fehlerdialog.
3. Flugmodus kurz ein- und wieder ausschalten (erzwingt einen echten Zellwechsel) und ca. eine
   Minute warten (nächster periodischer `CellSecurityWorker`-Lauf oder App neu öffnen, um den
   Sofortlauf über den Einstellungen-Screen erneut anzustoßen).
4. Erwartet: **kein** neuer "Auffälligkeit erkannt"-Eintrag für den bloßen Zellwechsel (Zell-ID
   *und* Gebietscode ändern sich normalerweise zusammen — das ist laut `CellSecurityDecision`
   explizit der Nicht-Fund-Fall).
5. Reaktion auf "Netz-Sperre aktivieren" umstellen. Erwartet: sofort wieder ein
   "Baseline gesetzt"-Eintrag (Umschalten verwirft den alten Messwert, s. Klassendoc).
6. Wer ein zweites, absichtlich altes 2G-only-Testgerät als Vergleich hat, kann `GENERATION_
   DOWNGRADE` durch Erzwingen von "Nur 2G" im Mobilfunk-Menü (Entwickleroptionen ▸ bevorzugter
   Netzwerktyp) provozieren — erwartet: ein `WARNING`-Log-Eintrag, aber **keine** Netz-Sperre (der
   Indikator allein reicht laut Klassendoc nicht für `CRITICAL`).

**Nicht Bestandteil dieses Tests:** ob die vier Indikatoren tatsächlich einen realen IMSI-Catcher
erkennen würden — das ist ohne eine kontrollierte Rogue-Basestation nicht verifizierbar, s.
`CellSecurityDecision`-Klassendoc ("Verdachts-Indikator, keine belastbare Erkennung").

### P-13 — Permission Auto-Block: manueller Entzug/Wiederherstellung

Prüft den 2026-08-29 geschlossenen Lückenschluss (Feature 3 aus
`docs/umsetzungsplan-7-features.md`): `SuspiciousAppScanController.trust()` konnte zuvor automatisch
entzogene gefährliche Rechte nicht zurückgeben, weil nirgendwo gespeichert war, welche das waren.

1. Eine Fremd-App mit mindestens einem gefährlichen Recht (z. B. Kamera- oder Standort-Zugriff)
   installieren, Permission-Audit öffnen, "Scannen" tippen, die App aufklappen.
2. "Gefährliche Rechte sperren" tippen. Erwartet: die Zeile zeigt sofort "gesperrt", ein
   Audit-Log-Eintrag "Gefährliche Rechte manuell entzogen" erscheint. `adb shell dumpsys package
   <paket> | grep -A2 "runtime permissions"` bestätigt `granted=false` für das betroffene Recht.
3. Die Ziel-App öffnen und die entzogene Berechtigung anfordern (z. B. Kamera-Funktion antippen) —
   erwartet: Anfrage wird kommentarlos verweigert, kein Systemdialog (Device-Owner-`DENIED`
   überschreibt die normale Laufzeitanfrage).
4. Zurück in Warden: "Rechte wiederherstellen" tippen. Erwartet: Zeile zeigt kein "gesperrt" mehr,
   Log-Eintrag "Gefährliche Rechte manuell wiederhergestellt". Die Ziel-App fragt beim nächsten
   Zugriffsversuch wieder normal (nicht automatisch gewährt — `PERMISSION_GRANT_STATE_DEFAULT`, kein
   `GRANTED`).
5. Automatischer Pfad: eine App mit einem `WARNING`+-Verdachtssignal (z. B. Geräteadmin-Fähigkeit)
   im Sicherheits-Scanner auslösen, Auto-Freeze-Scanner aktiviert lassen. Erwartet: Log-Einträge für
   sowohl automatisches Einfrieren als auch "Gefährliche Rechte automatisch entzogen". Den Fund dann
   im Sicherheits-Scanner als vertrauenswürdig markieren — erwartet: zusätzlicher Log-Eintrag
   "Automatisch entzogene Rechte wiederhergestellt", und das Permission-Audit zeigt die App danach
   nicht mehr als "gesperrt".

### P-14 — Sicherheits-Score

Prüft das 2026-08-29 umgesetzte Dashboard (Feature 5). Reine Aggregation bereits bestehender
Scan-Ergebnisse — kein neuer Erkennungsmechanismus, entsprechend kein "Trefferquote"-Aspekt wie bei
P-12.

1. Menüpunkt "Sicherheits-Score" öffnen, "Berechnen" tippen. Erwartet: eine Zahl 0–100 plus
   Einstufung (Sehr gut/Gut/Verbesserungswürdig/Kritisch) und vier Kategorie-Balken (Bedrohungen,
   Rechte-Hygiene, Geräte-Integrität, Härtung).
2. Kein Device Owner aktiv simulieren (`dpm remove-active-admin`, falls testOnly) oder eine der
   zugrunde liegenden Berechtigungen entziehen — erwartet: "Berechnung fehlgeschlagen"-Zustand statt
   eines geratenen/teilweise falschen Werts (Fail-Safe-Prinzip, s. `SecurityScoreCalculator`-Doc).
3. Einen Safeguard aus dem Katalog ein-/ausschalten, "Neu berechnen" tippen — erwartet: der
   Härtung-Balken bewegt sich sichtbar (Anteil aktiver Katalog-Einträge).
4. Einen `CRITICAL`-Verdachtsfund provozieren (z. B. Signaturwechsel simulieren, falls aus P-Reihen
   oben vorbereitet) — erwartet: Bedrohungen-Balken fällt auf 0, unabhängig von sonstigen Funden.

### P-15 — Tier-2-Auswahlmenüs (Einfriermethode, Ortung, Netzzeit, Löschgrenze)

Prüft die vier am 2026-09-05 ergänzten mehrstufigen Einstellungen (Einstellungen ▸ Härtung).
Keine davon ist im Auslieferungszustand aktiv — Schritt 1 prüft genau das.

1. Frische Installation (oder nach Konfigurations-Import): alle vier stehen auf der ersten Stufe
   ("Automatisch" bzw. dreimal "Aus"). Ein Versionswechsel darf hier nichts eingeschaltet haben.
2. Einfriermethode auf "Nur suspendieren" stellen, eine unkritische App einfrieren — erwartet:
   Symbol bleibt im Launcher, ausgegraut, Antippen zeigt den Systemdialog "App ist pausiert".
   Auf "Nur verstecken" umstellen und erneut einfrieren: App verschwindet aus dem Launcher.
   Für eine App, die einen `DeviceAdminReceiver` deklariert, schlägt "Nur verstecken" bewusst fehl
   (dokumentierte OS-Lücke) — "Automatisch" gelingt dort über den Suspend-Rückfallweg.
3. Ortung auf "einschalten und sperren" stellen: `adb shell dumpsys device_policy` zeigt
   `no_config_location` unter Wardens Restriktionen, der Schalter in den Systemeinstellungen ist
   ausgegraut. Zurück auf "Aus" — die Sperre verschwindet wieder (dieser Bit gehört allein diesem
   Controller, s. dessen Klassendoc).
4. Netzzeit auf "erzwingen und Änderung sperren" stellen. Erwartet: `no_config_date_time` gesetzt,
   **und** der Safeguard "Uhrzeit-Manipulation verhindern" steht danach im Safeguards-Bildschirm
   auf "an" — beides ist derselbe Soll-Zustand. Anschließend zurück auf "Aus": die Sperre bleibt
   absichtlich stehen (nur verschärfen, nie lockern), ausgeschaltet wird sie am Safeguards-Schalter.
5. Neustart mit gesetzter Ortungs-/Zeit-Einstellung: nach dem Boot sind beide unverändert. Der
   Store liegt im Device-Protected-Bereich, die Reconciliation läuft also schon vor der ersten
   Entsperrung.
6. Löschgrenze: s. "Bewusst nicht scharf geschaltet" unten — nur die Anzeige wird geprüft.

### P-16 — Richtlinien-Koexistenz und Soll-vs-Ist (Tier 3 / TestDPC-Übernahme)

1. Systemdiagnose öffnen ▸ Abschnitt "Richtlinien-Koexistenz". Erwartet auf dem SM-A156B: Warden
   als Device Owner, plus `com.samsung.android.kgclient` in der Liste der weiteren aktiven Admins.
2. Solange noch keine Rückmeldung eintraf, muss dort ausdrücklich "Bisher keine Rückmeldung"
   stehen — **nicht** "keine Konflikte". Danach einen beliebigen Safeguard umschalten und erneut
   prüfen: jetzt sollte mindestens eine Richtlinie zurückgemeldet worden sein.
3. Einen Safeguard auf einem Gerät einschalten, das die zugehörige Restriktion nicht kennt (oder
   sie über einen zweiten Admin gegenhalten): der Safeguards-Bildschirm zeigt unter der Zeile
   "⚠ Soll: an · Ist: aus" statt eines stumm falschen Schalterbilds.
4. `adb shell dumpsys device_policy` gegenprüfen — die Anzeige darf nie behaupten, etwas sei
   durchgesetzt, was dort nicht steht.

### P-17 — Systemseitiger Diebstahlschutz (nur Verweis)

Systemdiagnose ▸ "Systemseitiger Diebstahlschutz": zeigt ausschließlich, ob eine Bildschirmsperre
eingerichtet ist, plus einen Knopf in die Sicherheitseinstellungen. Erwartet wird **kein**
Aktiv-/Inaktiv-Status der drei Android-15-Funktionen — dafür gibt es keine öffentliche Lese-API,
und ein geratener Wert wäre schlechter als keiner. Der Knopf muss die Systemeinstellungen öffnen
(auf einem Gerät ohne diese Activity passiert nichts, kein Absturz).

## Bewusst nicht scharf geschaltet

**Seit "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26) gibt es einen echten, zweistufigen
Auslöser** (`de.ble1st.warden.sentinelbridge.SentinelLockdownEngager.engage()`, manuell über
`SensitiveAction.LOCKDOWN_TASK_ENGAGE` und automatisch bei kritischen Bedrohungsfunden über
`WardenLockTaskPendingEngageStore`) — Warden autorisiert Sentinels Paket per DPM und startet
Sentinels `SentinelActivity` per `startActivity()`; das eigentliche `Activity.startLockTask()`
läuft danach in Sentinels eigenem, fremden Prozess, nicht mehr in Wardens. Real ausgeführt wird es
auf dem aktuellen Testgerät trotzdem nirgends: `emergencyCallDrillPassed` bleibt strukturell an
`de.ble1st.warden.pin.WardenLockTaskDrillStorage` gebunden (ein manuell in der UI bestätigtes, nie
automatisch gesetztes Bit, von Warden bei jedem Aufruf frisch an Sentinel weitergereicht — Sentinel
speichert/errät diesen Wert nie selbst) UND `DestructiveCommandGuard` blockiert reale Aufrufe
weiterhin hart, solange es sich um einen Debug-Build handelt — das Testgerät läuft ausnahmslos
Debug-Builds. **Nachtrag 2026-08-26/27: inzwischen überholt** — auf einem eigens gebauten, nicht als Debug
markierten Wegwerf-Build (`1.0-livedrill`) wurden Drill-Bestätigung, echtes Kiosk-Engage und der
Ausstieg über Sentinels eigene PIN real durchgeführt, ebenso `REBOOT` und `MASTER_SWITCH_REVERT`.
Für Debug-Builds gilt der Absatz unverändert weiter. Ursprünglicher Stand: **ein echter, manuell
durchgeführter Notruf-Drill auf einem Non-Debug-Build blieb für jene Runde bewusst nicht
durchgeführt** (Risiko: hängt das Testgerät im Lock-Task-Modus fest,
falls der Notruf-Escape-Pfad nicht wie erwartet funktioniert) — die Drill-Bestätigung selbst ist
aber jederzeit gefahrlos in der UI testbar (setzt nur ein lokales Bit, löst kein `startLockTask()`
aus). Die Gate-Logik selbst ist JVM-unit-getestet (`SentinelLockTaskGateTest` in `:sentinel`,
`WardenLockTaskAutoEngageDecisionTest` in `:app`, beide Werte je Bedingung).

**Der Cross-Process-Death-Watchdog** (`SentinelWatchdogController`/`SentinelDeathWatchdog`) startet
zusammen mit `engage()` — auf einem Debug-Build läuft er also nie real an, solange der
Debug-Build-Hardblock den Aufruf davor abfängt. Auf dem Wegwerf-Build hat er sich nach einem
einzelnen echten Prozesstod korrekt neu gebunden; die Eskalation nach drei Toden in 60 s ist
weiterhin nur JVM-unit-getestet (`SentinelWatchdogDecisionTest`) — Prüfprozedur dafür s. P-1
oben.

**Sentinels Silent-Install selbst ist unabhängig vom Lock-Task-Hardblock und gefahrlos jederzeit
testbar** (installiert nur ein zusätzliches Paket, versetzt das Gerät nicht in einen Kiosk-Zustand)
— s. Schritt 11 oben.

**Device-Owner-Übertragung (`SensitiveAction.TRANSFER_OWNERSHIP`, Einstellungen ▸ Erweitert,
2026-09-05) wird bewusst nicht real ausgeführt.** Sie ist die einzige Aktion neben `WIPE_DATA`,
nach der Warden sich selbst nicht mehr helfen kann: die Rolle liegt danach bei der Zielapp, und
gibt die sie nicht zurück, hilft nur ein Werksreset — auf dem Testgerät also dieselbe
Neuprovisionierungs-Runde, die schon `dpm remove-active-admin` gekostet hat (s. `CLAUDE.md`,
"On-device verification"). Gefahrlos prüfbar und auch zu prüfen ist alles davor: dass die Zielliste
nur Apps mit deklariertem `DeviceAdminReceiver` und ohne Warden selbst enthält, dass die Aktion in
`SensitiveActionActivity` **nur** mit übergebenem Ziel überhaupt in der Auswahlliste auftaucht,
dass sie keinen Sitzungs-Kurzweg anbietet (kein "Bestätigen"-Knopf, sondern Biometrie/PIN — wie bei
`WIPE_DATA`), und dass ein Debug-Build sie mit "ExecutionBlocked" abweist.

**Die Löschgrenze nach N Fehlversuchen (`FailedAttemptsWipeThreshold`) wird ebenfalls nicht real
ausgelöst.** Sie steht im ausdrücklichen Widerspruch zur sonstigen "Neustart statt Löschen"-Linie
des Projekts und ist genau deshalb standardmäßig aus (s. deren Klassendoc). Geprüft wird nur, dass
der gesetzte Wert in `adb shell dumpsys device_policy` als
`maximumFailedPasswordsForWipe` erscheint und dass "Aus" ihn wieder auf `0` setzt — die
Fehlversuche selbst werden nicht bis zur Schwelle durchgespielt.

## Reduzierte Instrumented-Tests (kompiliert, nicht auf einem Gerät ausgeführt)

`./gradlew :app:connectedDebugAndroidTest`/`./gradlew :sentinel:connectedDebugAndroidTest` auf
einem angeschlossenen Gerät/Emulator ausführen, um die in Schritt 13 portierten Instrumented-Tests
(`crypto/*`, `logging/*`, `registry/*`, `pin/WardenPinStoreInstrumentedTest`) sowie die neuen
Sentinel-Tests tatsächlich laufen zu lassen — sie kompilieren bereits
(`:app:compileDebugAndroidTestKotlin`), wurden aber in dieser Umgebung mangels Gerät nicht
ausgeführt. Zwei der neuen Tests brauchen zusätzlich echten Device-Owner-Status
(`WardenLockTaskAuthorizerInstrumentedTest`, `SentinelSilentInstallerInstrumentedTest` in `:app`)
— sonst per `assumeTrue` übersprungen (skipped, nicht falsch-grün), s. deren Klassendocs.
`SentinelSignalReceiverInstrumentedTest` (sicherheitskritisch: verifiziert real per `am broadcast`,
dass ein Absender ohne Wardens Signatur am `signature`-Permission-Check scheitert) läuft ohne
Vorbedingungen.
