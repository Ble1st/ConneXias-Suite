# Warden

Android-Geräteverwaltungs- und Härtungs-App (`de.ble1st.warden`) mit **Device-Owner**-Rechten,
zwei Gradle-Module: `:app` und `:sentinel` (eine bewusst minimale, eigenständige Kiosk-PIN-APK,
die `:app` als Asset mitliefert und still installiert). Kein Google-Play-Services-/proprietärer-
Google-Dienst verbaut; Jetpack Compose, Material 3 mit Terminal-naher Formsprache. Sicherheits-
kritische Kryptografie (AES-256-GCM, Argon2id, HKDF, Ed25519) läuft in einer eigenen Rust-Engine
über UniFFI.

Warden hat **keinen Fernkanal** — kein Push, kein Server, kein Konto. Alles, was die App tut,
entscheidet sie lokal auf dem Gerät. Das ist die Grundentscheidung, aus der sich fast jede andere
hier ableitet.

## Umfang

### Härtung

- **32 einzeln schaltbare Safeguards** (`registry/`) über `DevicePolicyManager`: Kamera,
  Bildschirmaufnahme, Selbstdeinstallations-/Force-Stop-Schutz, Keyguard-Härtung, Accessibility-/
  Eingabemethoden-Sperre, Sicherheits-/Netzwerkprotokollierung, Passwortkomplexität,
  Auto-Sperr-Timeout, Backup-Dienst, System-Update-Politik, Sperrbildschirm-Privatsphäre,
  permanente USB-Datenabschaltung, Factory Reset Protection, Sentinel-Deinstallationsschutz,
  Zusatznutzer-/2G-/NFC-/VPN-Konfigurations-/USB-Dateitransfer-/Bluetooth-Sharing-Sperren.
  Jeder Safeguard ist idempotent; `isActive()` fragt immer den echten DPM-Zustand ab, nie einen
  Cache.
- **Drei Profile** (Alltag/Reise/Maximal) wenden je eine Teilmenge in einem Aufruf an — optional
  automatisch nach Uhrzeit oder Bedrohungslage umgeschaltet. Eine Automatik darf nie unter eine
  manuell gesetzte Stufe zurückfallen; Eskalation ist immer erlaubt.
- **Boot-Abgleich**: `RegistryReconciler` vergleicht Soll- gegen Ist-Zustand nach jedem Neustart
  und korrigiert Drift, für einzelne IDs ausdrücklich nur in Richtung „strenger".
- **Master-Switch** (Alles-aus-Notschalter) und ein **Offline-Failsafe** (Ed25519-Challenge/
  Response) für den Fall, dass PIN-Blob und Biometrie beide nicht mehr verfügbar sind.

### Zugangsschutz

- **WardenLock**: Die App selbst ist gesperrt. Ohne Nachweis (Biometrie oder lokale PIN) rendert
  das Dashboard nicht. Die Sitzung lebt nur im Prozessspeicher und wird verworfen, sobald keine
  Warden-Activity mehr sichtbar ist — Navigation innerhalb der App löst das nicht aus.
- **Lokale PIN** (Argon2id, hash-verketteter, versionierter Blob mit Replay-Schutz) mit
  exponentiellem Backoff ab dem 5. Fehlversuch, neustartfest.
- **Duress-PIN**: sieht aus wie eine falsche PIN (gleiche Meldung, gleicher Fehlversuchszähler),
  startet das Gerät aber still nach BFU neu — verschlüsselter Speicher ist danach wieder
  unzugänglich, ohne dass Warden Nutzerdaten löscht. Vorbild ist GrapheneOS.
- **Presence-Gate für destruktive Befehle**: Neustart, Sofortsperre, Lockdown scharfschalten,
  Kiosk starten, Registry-Revert und Wipe laufen alle über Bestätigungssatz + Nachweis +
  Ratenbegrenzung + Audit-Eintrag. `WIPE_DATA` ist bewusst ein reiner Stub — die einzige Aktion
  ohne Weg zurück.

### Kiosk-Modus (Sentinel)

`:sentinel` ist eine eigene APK mit eigenem Prozess/UID, eigenem PIN-Blob, ohne Launcher-Symbol.
Beim Scharfschalten sperrt sich **Sentinel** in den Lock-Task-Modus, nicht Warden — Warden kann
`stopLockTask()` für einen fremden Prozess nicht aufrufen, **der einzige Ausgang ist Sentinels
eigene PIN**. Notruf und Keyguard bleiben erreichbar. Beide Richtungen sind über
`signature`-Permissions abgesichert (deshalb müssen beide Module dasselbe Zertifikat tragen).
Ein `IBinder.DeathRecipient`-Watchdog erkennt das Abschießen von Sentinel kernel-vermittelt und
zieht bei 3 Todesfällen in 60 s die Lock-Task-Freigabe zurück. Der Eskalationspfad ist am
2026-08-30 auf echter Hardware end-to-end nachgewiesen.

### Bedrohungserkennung

- **Verdächtige-App-Scanner**: prüft *deklarierte* Manifest-Fähigkeiten, nicht nur aktivierte —
  Geräteadmin-Fähigkeit, Accessibility-Dienst, Overlay-Berechtigung,
  Benachrichtigungs-Listener, Installationsquelle (Sideload), Signaturzertifikatswechsel,
  Versions-Downgrade und neu hinzugekommene gefährliche Berechtigungen nach einem Update.
  Zehn Signale, drei Schweregrade, schlechtestes Signal gewinnt.
- **Reaktionen**: Einfrieren (reversibel), Deinstallieren, Daten löschen, gefährliche
  Berechtigungen entziehen — automatisch erst ab „Warnung", nie bei einem bloßen Info-Signal.
  Deinstallieren und Daten löschen laufen zwingend über einen Bestätigungsbildschirm mit
  WardenLock-Nachweis, nie direkt aus einer Benachrichtigung.
- **Berechtigungs-Audit** pro App und ein lokaler **Leistungs-/Akkumonitor**.
- **Security Score** aus vier gewichteten Kategorien (Bedrohungen 35 %, Rechte-Hygiene 25 %,
  Geräte-Integrität 20 %, Härtung 20 %) mit 30-Tage-Verlauf — hinter einem „Berechnen"-Knopf,
  bewusst ohne periodischen Worker, weil der Scan teuer ist. Eine tägliche Erinnerung meldet
  sich, wenn 30 Tage lang kein Wert berechnet wurde.

### Lokale Auslöser, die ohne den Besitzer feuern

Ohne Fernkanal war „das Gerät ist weg" lange fast nicht abgedeckt. Diese Auslöser schließen das
lokal:

- **SIM-Wechsel** (melden/sperren/neustarten) — unterscheidet strikt zwischen „nicht lesbar"
  (reagiert nie) und „gelesen, keine SIM" (echtes Signal).
- **Mobilfunk-Auffälligkeiten** — Vergleich gegen die *unmittelbar vorherige* Messung statt gegen
  eine wachsende Whitelist. Ausdrücklich eine Verdachtsheuristik, keine IMSI-Catcher-Erkennung.
- **Neustart nach N Fehlversuchen** am System-Sperrbildschirm, direct-boot-fähig — greift also
  auch im BFU-Fenster.
- **Unbekanntes WLAN** (melden oder Netzsperre scharfschalten).
- **Anti-Diebstahl-Alarm**: Bewegung bei gesperrtem Bildschirm oder abgezogenes Ladekabel lösen
  einen Alarm auf dem Wecker-Audiokanal aus. Der Alarm endet **nur durch ein echtes Entsperren** —
  keine Benachrichtigungsaktion, kein Timeout. Der letzte bekannte Standort wird best-effort
  mitprotokolliert, ohne je eine frische Ortung anzufordern.
- **BLE-Tracker-Wächter** (opt-in, aus Werk aus): erkennt Find-My-förmige Werbepakete und meldet
  Geräte, die über mehrere Scans hinweg wiederholt in der Nähe sind — der einzige Auslöser hier,
  der den *Besitzer* schützt statt das Gerät.

Alle Auslöser reagieren mit **Neustart nach BFU statt mit einem Wipe**.

### Netz-Sperre und ChildVPN

Eine eigene VPN-/Firewall-Engine in Rust (`rust/barbican`): Always-On-Kill-Switch mit
DNS-Blockliste (Direct-Modus) sowie ChildVPN — ein vollständiger WireGuard-Client über boringtun,
konfigurierbar per QR-Import oder Texteingabe. **ChildVPN ist seit 2026-09-01 auf echter Hardware
end-to-end bestätigt** (Handshake, Verschlüsselung, VPS-Relay, Entschlüsselung, Rückweg).
Der äquivalente Traffic-Test für den Direct-Modus steht noch aus, s. `analyse.md` Abschnitt 6.2.

### Protokolle und Diagnose

- **Eigenes Audit-Log**, hash-verkettet mit Segmentrotation und Aufbewahrungs-Anker: eine von
  Warden selbst gekürzte Kette bleibt verifizierbar, eine manipulierte nicht.
  Als Klartext exportierbar (bewusst ohne die Hash-Felder — außerhalb der App wären sie nicht
  nachprüfbar).
- **System-Ereignisprotokoll**: wertet die DPM-eigenen Sicherheits-/Netzwerkprotokolle aus
  (ADB-Befehle, Paketinstallationen, Keyguard-Fehlversuche, CA-Installationen) statt sie zu
  verwerfen. 2000-Einträge-Ringpuffer.
- **Geräte-Integrität** (Root-/Magisk-Indikatoren, ADB, Entwickleroptionen,
  Speicherverschlüsselung) und ein **Systemdiagnose-Bildschirm**, der zeigt, ob die strukturellen
  Voraussetzungen der lokalen Auslöser überhaupt gegeben sind (zehn periodische Worker, vier
  selbst gewährte Berechtigungen, dynamisch registrierte Empfänger).
- **Konfigurations-Export/-Import** über SAF: überträgt die *Härtungshaltung*, ausdrücklich nicht
  PIN-Blob, Schlüssel, Protokolle oder Sentinel-Zustand. Ein wiederhergestelltes Gerät bekommt
  dieselbe Konfiguration, nie dieselbe Identität.
- **Status-Widget** (Glance) — reine Anzeige, ein einziges Tap-Ziel, das nichts tut außer die App
  über denselben gesperrten Einstiegspunkt zu öffnen. Bewusst ohne Schalter: ein Widget läuft
  außerhalb jeder Activity und ließe sich nicht durch WardenLock führen.

## Build

Warden wird — wie jede App der Suite — aus dem eigenen Ordner gebaut:

```
./gradlew build                 # lint + Unit-Tests + Debug-/Release-Assemble
./gradlew :app:assembleDebug    # installierbare Debug-APK (bündelt die Sentinel-APK mit)
./gradlew test                  # nur JVM-Unit-Tests (domain/*, reines Kotlin)
./gradlew :sentinel:test        # Sentinels eigene Unit-Tests
```

`:app:assembleDebug`/`assembleRelease` bauen `:sentinel` transitiv mit und kopieren dessen APK über
die Variant-API in die generierten Assets. **Die APK niemals von Hand hineinkopieren.** `:sentinel`
**muss** mit demselben Zertifikat signiert sein wie `:app` — der gesamte Warden↔Sentinel-Kanal
hängt an `signature`-Permissions, ein abweichendes Zertifikat bricht ihn auf OS-Ebene.

Lint-**Fehler** brechen den Build ab, nicht nur Warnungen.

Instrumentierte Tests brauchen ein angeschlossenes Gerät oder einen Emulator:

```
./gradlew :app:connectedDebugAndroidTest
```

## Rust-Komponenten

`rust/engine` (Krypto) und `rust/barbican` (VPN/Firewall) sind ein eigener Cargo-Workspace und
werden **nicht** von Gradle mitgebaut. Nach einer Änderung an der Rust-API die Bindings und
nativen Bibliotheken von Hand neu erzeugen:

```
cd rust && ./build-android.sh              # Krypto-Engine, alle 4 Android-ABIs
cd rust && ./build-android-barbican.sh     # VPN-/Firewall-Engine
```

Beides braucht `cargo-ndk` und die vier Android-Rustup-Targets. `build-android.sh` frischt auch
Sentinels eigene Kopie derselben `.so`/Bindings auf (bewusste Duplizierung, kein geteiltes Modul).

## Device Owner einrichten

Device-Owner-Status lässt sich auf einem Gerät mit bestehendem Google-Konto nicht per normalem
`adb install` herstellen — es braucht ein frisches Gerät bzw. einen frischen Emulator:

```
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner de.ble1st.warden/de.ble1st.warden.admin.WardenDeviceAdminReceiver
```

Alternativ läuft die Einrichtung über QR-Provisionierung.

Nach der ersten Provisionierung führt der **Ersteinrichtungs-Assistent** durch PIN, Profil,
Sentinel-Installation und Notruf-Drill. Er startet beim ersten Öffnen automatisch, bleibt danach
über das Menü erreichbar und liest den Stand jedes Schritts bei jedem Öffnen frisch aus dem
echten Systemzustand — Pflicht sind nur Device Owner und PIN, alles Weitere hängt davon ab, ob das
Gerät den Kiosk-Modus überhaupt nutzen soll.

`dpm remove-active-admin` funktioniert ohne Werksreset nur, wenn die *installierte* APK
`android:testOnly="true"` trägt — und dieses Flag wird einmalig bei der Admin-Registrierung
festgehalten, nicht durch ein späteres Update nachgezogen.

## Bekannte Einschränkungen

- **Factory Reset Protection ist auf mindestens einem realen OEM-Gerät wirkungslos** (Samsung
  SM-A156B, live geprüft: Konto gesetzt, per `dumpsys` verifiziert, echter Recovery-Wipe — keine
  Kontoabfrage). Nicht als Diebstahlschutz behandeln, solange nicht auf der Zielhardware neu
  verifiziert. Der In-App-Warntext sagt das explizit.
- **Einfrieren scheitert bei manchen Apps still** (`result=false`, keine Exception): bei der
  gerade aktiven Geräteadmin-App, bei debuggable Apps und bei jeder App, die einen
  `DeviceAdminReceiver` auch nur *deklariert*. OS-Grenze, kein Fehler in Warden — für die
  Geräteadmin-Fundkategorie bleiben Deinstallation oder manuelle Deaktivierung.
- **Der Direct-Modus der Netz-Sperre ist nicht end-to-end verifiziert.** ChildVPN ist es (s. oben);
  für den Direct-Modus steht der `dig`/`curl`-Test durch den Tunnel noch aus.
- **`minSdk 35`** — deutlich höher als bei den anderen drei Suite-Apps (dort 26), weil die
  Device-Owner-Voraussetzung ohnehin ein aktuelles System bedingt.
- Destruktive Befehle sind auf **Debug-Builds strukturell blockiert** (`DestructiveCommandGuard`).
  Für einen Live-Test braucht es einen Wegwerf-Release-Build.

`MANUAL_SMOKE_TEST.md` enthält die vollständige manuelle Prüfliste für Verifikation auf echter
Hardware — vor jeder Geräte-Arbeit lesen, inklusive des Abschnitts darüber, was bewusst *nicht*
scharf getestet wird.

## Sicherheit

S. [SECURITY.md](SECURITY.md). Das laufende Suite-Audit steht in
[`../analyse.md`](../analyse.md); Wardens Befunde sind dort Abschnitt 1.

Fremdbibliotheken und ihre Lizenzen: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), in der App
selbst unter „Lizenzen".

## Lizenz

Apache License 2.0 — s. [LICENSE](../LICENSE).
