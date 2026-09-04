# Signaturschlüssel — Erzeugung, Aufbewahrung, Wiederherstellung

Alle vier Apps der Suite werden mit **demselben** Schlüssel signiert. Diese Datei beschreibt,
wie er entsteht, wo er liegen muss und was passiert, wenn er verloren geht.

## Warum das kritischer ist als bei einer Play-Store-App

Bei einer über den Play Store verteilten App gibt es Play App Signing: Google hält den
Upload-Schlüssel vor, ein Verlust ist ein Support-Ticket. Diese Suite wird per Sideload
verteilt — **es gibt keine solche Instanz.**

Android bindet die Identität einer installierten App an ihr Signaturzertifikat. Eine APK mit
einem anderen Zertifikat ist für das System eine *andere* App, kein Update; die Installation
scheitert mit `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Der Schlüssel ist damit die einzige
Verbindung zwischen dem, was heute installiert ist, und allem, was danach kommt.

Konkret bei Schlüsselverlust:

| App | Folge |
|---|---|
| Files, Kamera, Galerie | Kein Update mehr möglich. Nutzer müssen deinstallieren (app-privater Speicher weg: WebDAV-Zugänge, Favoriten, Alben, Papierkorb-Verweise) und neu installieren. Ärgerlich, aber überlebbar. |
| Warden | **Kein Weg zurück ohne Werksreset.** Warden ist Device Owner und schützt sich selbst vor Deinstallation; ein Deinstallieren zum Neuinstallieren ist genau das, was die App verhindert. |
| Sentinel | Sentinel und Warden sind über eine `signature`-geschützte Permission gekoppelt (s. `warden/sentinel/build.gradle.kts`). Zwei unterschiedlich signierte APKs erkennen sich gegenseitig nicht — der einzige Ausgang aus dem Kiosk-Modus fällt aus. |

Der Release-Bau prüft die Kopplung Warden ↔ Sentinel deshalb aktiv: stimmen die
Zertifikat-Fingerabdrücke der beiden APKs nicht überein, bricht `release.yml` ab, statt ein
Paar zu veröffentlichen, das auf dem Gerät nicht zusammenpasst.

## Schlüssel erzeugen

Einmalig, auf einem Rechner ohne Netzwerkzugriff bzw. offline, und **nicht** im Repo-Ordner:

```
keytool -genkeypair -v \
  -keystore connexias-release.keystore \
  -storetype PKCS12 \
  -alias connexias \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -dname "CN=ConneXias Suite, O=ConneXias, C=DE"
```

- **PKCS12**, nicht das alte JKS-Format — JKS ist seit Java 9 abgekündigt und `keytool` warnt
  bei jedem Zugriff darauf.
- **4096 Bit RSA**: der Schlüssel muss länger halten als jede einzelne App-Version.
- **10950 Tage (30 Jahre)**: läuft das Zertifikat ab, lässt sich keine neue Version mehr
  signieren, die als Update der alten durchgeht. Google fordert für Play-Apps eine Gültigkeit
  bis mindestens 2033; für einen Device-Owner-Vertrieb ohne Store gibt es keinen Grund, knapper
  zu planen.
- Store- und Key-Passwort **unterschiedlich** wählen, beide aus einem Passwortmanager.

Fingerabdruck notieren — er steht später auf jeder Release-Seite und ist das, woran Nutzer die
Herkunft einer APK prüfen:

```
keytool -list -v -keystore connexias-release.keystore -alias connexias | grep 'SHA256:'
```

## Aufbewahrung

Der Keystore existiert an **drei** unabhängigen Orten. Zwei reichen nicht: ein Ort ist keine
Sicherung, zwei Orte am selben physischen Platz sind ein Ort.

1. **Passwortmanager** (verschlüsselter Anhang) — Keystore-Datei plus beide Passwörter plus
   Alias plus notierter SHA-256-Fingerabdruck.
2. **Offline-Datenträger** an einem anderen physischen Ort (verschlüsselter USB-Stick,
   Bankschließfach, Tresor). Datenträger altern; alle zwei Jahre umkopieren und lesbar prüfen.
3. **GitHub-Secrets** dieses Repositories — das ist die *Arbeitskopie* für CI, **keine
   Sicherung**. GitHub gibt ein einmal gesetztes Secret nicht wieder heraus; wer den Keystore
   nur dort hat, hat ihn nicht.

Die Passwörter gehören **nicht** neben den Keystore auf denselben Datenträger.

### GitHub-Secrets setzen

Vier Secrets, gelesen von `.github/workflows/release.yml` und `release-apps.yml`:

| Secret | Inhalt |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 connexias-release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Store-Passwort |
| `RELEASE_KEY_ALIAS` | `connexias` |
| `RELEASE_KEY_PASSWORD` | Key-Passwort |

```
gh secret set RELEASE_KEYSTORE_BASE64 < <(base64 -w0 connexias-release.keystore)
gh secret set RELEASE_KEYSTORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

Die Pipelines dekodieren den Keystore nach `$RUNNER_TEMP` und löschen ihn in einem
`if: always()`-Schritt wieder — auch wenn der Bau dazwischen fehlschlägt.

## Lokal signiert bauen

Nur nötig, um vor einem Release dieselbe APK lokal zu erzeugen. Für einen gewöhnlichen Testbau
**nicht** nötig: ohne die Env-Vars liefert `assembleRelease` eine unsignierte APK, und
`assembleDebug` signiert mit dem Debug-Schlüssel des SDK.

```
# Warden (+ Sentinel, wird transitiv mitgebaut)
export WARDEN_RELEASE_STORE_FILE=/pfad/zu/connexias-release.keystore
export WARDEN_RELEASE_STORE_PASSWORD=...
export WARDEN_RELEASE_KEY_ALIAS=connexias
export WARDEN_RELEASE_KEY_PASSWORD=...
cd warden && ./gradlew assembleRelease

# Files / Kamera / Galerie
export CONNEXIAS_RELEASE_STORE_FILE=/pfad/zu/connexias-release.keystore
export CONNEXIAS_RELEASE_STORE_PASSWORD=...
export CONNEXIAS_RELEASE_KEY_ALIAS=connexias
export CONNEXIAS_RELEASE_KEY_PASSWORD=...
cd files && ./gradlew assembleRelease
```

Der Keystore gehört **nicht** in den Repo-Ordner — `.gitignore` ist die zweite
Verteidigungslinie, nicht die erste.

## Wiederherstellung

1. Keystore aus dem Passwortmanager oder vom Offline-Datenträger holen.
2. Fingerabdruck gegen den notierten Wert prüfen — und gegen den, der auf der letzten
   Release-Seite steht:
   ```
   keytool -list -v -keystore connexias-release.keystore -alias connexias | grep 'SHA256:'
   ```
   Stimmt er nicht, ist es der falsche Keystore. Damit zu signieren erzeugt APKs, die niemand
   installieren kann, der die Suite bereits hat.
3. Die vier GitHub-Secrets neu setzen (s. oben).
4. Probelauf: `release-apps.yml` manuell mit `bump: patch` für eine App starten. Die Pipeline
   legt einen **Entwurf** an — der lässt sich prüfen und wieder verwerfen, ohne dass jemand ihn
   je zu sehen bekommt. Fingerabdruck im erzeugten Release-Text mit dem der Vorversion
   vergleichen.

## Wenn der Schlüssel kompromittiert ist

Es gibt keinen Widerruf — ein Android-Signaturzertifikat kennt keine Sperrliste.

1. Alle vier GitHub-Secrets sofort löschen, damit kein weiterer Bau damit signiert.
2. Neuen Schlüssel nach obiger Anleitung erzeugen.
3. Auf der Releases-Seite und im README unmissverständlich vermerken: **ab Version X gilt ein
   neuer Fingerabdruck, alle Versionen bis X-1 sind als potenziell fremd signiert zu
   betrachten.** Den alten Fingerabdruck dabei explizit nennen, damit Nutzer erkennen, was sie
   installiert haben.
4. Bestehende Installationen können **nicht** aktualisiert werden. Für Files/Kamera/Galerie
   heißt das: deinstallieren und neu installieren. Für ein Warden-Device-Owner-Gerät: Werksreset
   und neu einrichten.

Punkt 4 ist der Grund, warum Punkt "Aufbewahrung" drei Orte vorsieht.

## Ab wann etwas anderes sinnvoll wäre

Ab dem Punkt, an dem die Suite auf Geräten läuft, die nicht dem Maintainer gehören, sollte der
Schlüssel nicht mehr allein bei einer Person liegen — dann wird aus dem Passwortmanager-Eintrag
ein Verfahren mit einer zweiten berechtigten Person (Shamir-Aufteilung, geteilter Tresor,
Hardware-Token). Solange die Suite auf eigenen und Familiengeräten läuft, ist das drei-Orte-
Verfahren oben angemessen.
