# Manueller Rauchtest (Schritt 14)

`./gradlew build` läuft grün (Lint + 94 JVM-Unit-Tests + Debug-/Release-Assemble). Was nicht
automatisiert geprüft werden kann, ohne ein echtes Gerät/Emulator zu belegen — analog zum
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

## Bewusst nicht scharf geschaltet

Der Notruf-/Lock-Task-Drill (`WardenLockTaskGate`/`WardenLockTaskManager`) bleibt wie im
Quellprojekt **unbewaffnet** — `emergencyCallDrillPassed` wird nirgends automatisch auf `true`
gesetzt, `startLockTask()` wird von keinem regulären Codepfad aufgerufen. Das Gate selbst ist
JVM-unit-getestet (`WardenLockTaskGateTest`, beide Werte). Ein echter Lock-Task-Test auf dem Gerät
ist für diese Runde bewusst nicht vorgesehen (Risiko: hängt das Testgerät im Kiosk-Modus fest,
falls der Notruf-Escape-Pfad nicht wie erwartet funktioniert).

## Reduzierte Instrumented-Tests (kompiliert, nicht auf einem Gerät ausgeführt)

`./gradlew :app:connectedDebugAndroidTest` auf einem angeschlossenen Gerät/Emulator ausführen, um
die in Schritt 13 portierten Instrumented-Tests (`crypto/*`, `logging/*`, `registry/*`,
`pin/WardenPinStoreInstrumentedTest`) tatsächlich laufen zu lassen — sie kompilieren bereits
(`:app:compileDebugAndroidTestKotlin`), wurden aber in dieser Umgebung mangels Gerät nicht
ausgeführt.
