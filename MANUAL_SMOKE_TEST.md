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
Debug-Builds. **Ein echter, manuell durchgeführter Notruf-Drill auf einem Non-Debug-Build bleibt
für diese Runde bewusst nicht durchgeführt** (Risiko: hängt das Testgerät im Lock-Task-Modus fest,
falls der Notruf-Escape-Pfad nicht wie erwartet funktioniert) — die Drill-Bestätigung selbst ist
aber jederzeit gefahrlos in der UI testbar (setzt nur ein lokales Bit, löst kein `startLockTask()`
aus). Die Gate-Logik selbst ist JVM-unit-getestet (`SentinelLockTaskGateTest` in `:sentinel`,
`WardenLockTaskAutoEngageDecisionTest` in `:app`, beide Werte je Bedingung).

**Der Cross-Process-Death-Watchdog** (`SentinelWatchdogController`/`SentinelDeathWatchdog`) startet
zusammen mit `engage()` — auch das läuft also nie real an, solange der Debug-Build-Hardblock den
Aufruf davor abfängt. Seine reine Eskalations-Entscheidung ist unabhängig davon JVM-unit-getestet
(`SentinelWatchdogDecisionTest`).

**Sentinels Silent-Install selbst ist unabhängig vom Lock-Task-Hardblock und gefahrlos jederzeit
testbar** (installiert nur ein zusätzliches Paket, versetzt das Gerät nicht in einen Kiosk-Zustand)
— s. Schritt 11 oben.

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
