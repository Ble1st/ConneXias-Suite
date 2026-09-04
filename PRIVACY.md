# Datenschutzerklärung — ConneXias Suite

Gilt für die vier Apps **Warden** (`de.ble1st.warden`), **ConneXias Files** (`de.ble1st.files`),
**ConneXias Kamera** (`de.ble1st.camera`) und **ConneXias Galerie** (`de.ble1st.gallery`).

Verantwortlich ist der Betreiber dieses Repositories; Kontakt über ein Issue oder GitHubs private
Sicherheitsmeldung (s. [SECURITY.md](SECURITY.md)).

Stand: 2026-09-04.

## Kurzfassung

**Keine der vier Apps erhebt, überträgt oder speichert Daten auf Servern des Herausgebers.** Es
gibt keine Analyse-, Absturz- oder Werbe-Bibliothek in irgendeiner der vier Apps, keine
Google-Play-Dienste und keine Konten-Anmeldung. Alles läuft lokal auf dem Gerät.

Das Gerät nimmt nur in drei Fällen von sich aus Verbindung nach außen auf — jedes Mal zu einem
Ziel, das **Sie selbst** eingetragen haben, und nie zu einer Adresse des Herausgebers:

| Funktion | App | Ziel |
|---|---|---|
| WebDAV-Netzwerkspeicher | Files | Ihr Server |
| WebDAV-Cloud-Sicherung | Galerie | Ihr Server |
| ChildVPN | Warden | Ihr VPS |

Dazu kommt ein Fall, in dem das Gerät Verbindungen **annimmt** statt aufzubaut: die WLAN-/
Hotspot-Ordnerfreigabe in Files (s. unten).

Die ConneXias Kamera hat **überhaupt keine `INTERNET`-Berechtigung** — sie kann technisch nichts
senden, unabhängig davon, was diese Erklärung behauptet. Das ist auch der Grund, warum keine der
vier Apps selbst auf Updates prüft: die dafür nötige regelmäßige Verbindung wäre genau der
Hintergrundverkehr, den diese Suite vermeidet. Der Über-/Einstellungs-Bildschirm verlinkt
stattdessen die Releases-Seite, die Sie selbst antippen; geöffnet wird sie von Ihrem Browser.

## Was auf dem Gerät gespeichert wird

### Alle Apps

- Einstellungen der App in app-privaten `SharedPreferences` (Ansichtsmodus, Sortierung,
  Sucher-Einstellungen, Favoriten, benutzerdefinierte Alben). Diese Daten sind für andere Apps
  nicht lesbar und verschwinden beim Deinstallieren.
- `android:allowBackup="false"` in allen vier Apps: nichts davon wandert in ein Android-Backup
  oder in eine Cloud-Sicherung des Geräteherstellers.

### Warden

- **Audit-Protokoll**: sicherheitsrelevante Ereignisse (Profilwechsel, ausgelöste Alarme,
  Entsperrversuche, Deinstallationsversuche), verschlüsselt und hash-verkettet im
  app-privaten Speicher. Bleibt lokal, solange Sie es nicht selbst exportieren.
- **Standort**: nur beim Auslösen eines Diebstahl-Alarms, und nur als Eintrag im obigen
  Protokoll. Keine laufende Standortaufzeichnung, keine Übertragung.
- **BLE-Umgebung**: der Tracker-Wächter sieht Bluetooth-Kennungen in der Nähe; sie werden zur
  Auswertung im Speicher gehalten und nicht dauerhaft abgelegt.
- **Installierte Pakete**: der App-Scanner liest die Paketliste des Geräts (`QUERY_ALL_PACKAGES`),
  um verdächtige Apps zu melden. Die Auswertung läuft vollständig auf dem Gerät.
- **Mobilfunk-Kennwerte** (`READ_PHONE_STATE`): für die Verdachtsheuristik zur Basisstation.
  Lokal, s. Einschränkung in `analyse.md` 6.1.

> **Vor dem Teilen eines Warden-Audit-Log-Exports**: die Datei kann rohe GPS-Koordinaten aus
> Diebstahl-Alarmen enthalten und landet dort, wohin der Dateiauswahl-Dialog zeigt — unter
> Umständen in einem cloud-synchronisierten Ordner. Das ist der beabsichtigte Zweck beider
> Funktionen, aber vor dem Weitergeben wissenswert.

### ConneXias Files

- **Vollzugriff auf den Speicher** (`MANAGE_EXTERNAL_STORAGE`): notwendig, damit ein Dateimanager
  seine Aufgabe erfüllen kann. Die App liest nichts von sich aus ein und legt keinen Index an;
  gelesen wird nur der Ordner, den Sie gerade offen haben.
- **WebDAV-Zugangsdaten**: verschlüsselt (`androidx.security.crypto`) im app-privaten Speicher.
- **Papierkorb**: gelöschte Dateien liegen in `.crx-trash` auf demselben Speichervolume, bis Sie
  ihn leeren — sie sind also noch da.
- **„Zuletzt verwendet"**: eine Liste von Pfaden plus Zeitpunkt, app-privat.

### ConneXias Kamera

- Aufnahmen landen im MediaStore des Geräts, wie bei jeder Kamera-App. EXIF-Daten der Aufnahme
  (inkl. eventueller Standortangaben, die das System einträgt) bleiben unverändert erhalten.
- Gescannte QR-/Barcodes werden nur im Speicher gehalten und beim Verlassen des Ergebnis-
  Bildschirms verworfen.

### ConneXias Galerie

- Liest den MediaStore des Geräts (Bilder und Videos). Favoriten und benutzerdefinierte Alben sind
  reine Verweise auf MediaStore-IDs, keine Kopien.
- **WebDAV-Zugangsdaten**: verschlüsselt im app-privaten Speicher.
- Die Cloud-Sicherung überträgt genau die Elemente, die Sie dafür ausgewählt haben, an den von
  Ihnen eingetragenen Server.

## Unverschlüsselte Übertragung

Zwei Stellen erlauben bewusst Klartext:

- **WebDAV über `http://`** (Files und Galerie). Selbst gehostete Server im Heimnetz haben oft kein
  gültiges Zertifikat; ein hartes Verbot würde die Funktion für ihre Zielgruppe unbrauchbar
  machen. Beide Apps warnen sichtbar im Formular. Über `http://` gehen **Benutzername und Passwort
  im Klartext** durchs Netz.
- **WLAN-/Hotspot-Ordnerfreigabe** (Files) ist HTTP ohne TLS, abgesichert nur durch einen Token in
  der URL. Gedacht für das eigene Heimnetz, ausdrücklich nicht für das offene Internet. Sie ist
  nur aktiv, solange Sie sie eingeschaltet lassen, und gewährt ausschließlich Lesezugriff.

## Berechtigungen im Überblick

| App | Berechtigungen |
|---|---|
| Warden | Boot, Internet, Vibration, WLAN-Status, Kamera, Paketliste, Benachrichtigungen, Telefonstatus, Standort (fein/grob), Bluetooth (Scan/Verbindung), Paket-Deinstallation, Nutzungsstatistik, VPN, Vordergrunddienst, Netzwerkstatus, Akku-Optimierung |
| Files | Speicher-Vollzugriff, Speicher lesen/schreiben (API 26–28), Benachrichtigungen, Vordergrunddienst (Datenabgleich), Internet |
| Kamera | Kamera, Mikrofon (nur für Videoton, Ablehnung sperrt nur den Ton), Speicher schreiben (API 26–28) |
| Galerie | Bilder/Videos lesen, Speicher lesen/schreiben (API 26–28), Internet, Vordergrunddienst (Datenabgleich), Benachrichtigungen |

Warum eine App eine bestimmte Berechtigung hat, steht als Kommentar direkt an der jeweiligen
Zeile in ihrer `AndroidManifest.xml`.

## Kinder und überwachte Geräte

Warden kann als Device Owner ein fremdes Gerät verwalten — typischerweise das eines Kindes. Wer
das tut, verarbeitet damit Daten einer anderen Person (Standort bei Alarm, installierte Apps,
Entsperrversuche, bei aktivem ChildVPN auch deren Netzwerkverkehr). Für diese Verarbeitung ist die
einrichtende Person verantwortlich, nicht der Herausgeber der App. Ob und wie darüber aufgeklärt
werden muss, richtet sich nach dem Alter der überwachten Person und dem Recht des jeweiligen
Landes.

## Ihre Rechte

Da keine Daten beim Herausgeber ankommen, gibt es dort auch nichts, worüber Auskunft erteilt oder
was gelöscht werden könnte. Alle Daten liegen auf Ihrem Gerät: Deinstallieren der App entfernt
ihren app-privaten Speicher vollständig. Aufnahmen im MediaStore und Dateien im gemeinsamen
Speicher überleben eine Deinstallation — sie gehören dem Gerät, nicht der App.

## Änderungen

Änderungen an dieser Erklärung stehen in der Git-Historie dieser Datei. Es gibt keinen anderen,
"aktuelleren" Stand irgendwo im Netz.
