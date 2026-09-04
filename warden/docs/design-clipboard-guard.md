# Design-Dokument: Zwischenablage-Wächter (ClipboardGuard)

**Datum:** 2026-09-03
**Status:** Phase 1 **umgesetzt und auf dem physischen Testgerät live verifiziert** (Abschnitt 2,
Ergebnis s. Abschnitt 2.5). Phase 2 (Abschnitt 3.2, Nutzerentscheidung: AccessibilityService statt
IME) — Signal 1 (Toast-Interception, 3.2.4) **falsifiziert** (3.2.5). Signal 2 (3.2.6) **funktioniert
technisch**, liest aber, entgegen der ursprünglichen 3.2.1-Annahme ("nur Ereignis, kein Inhalt"),
den tatsächlichen Bildschirmtext jeder fremden App im Klartext — ein wesentlich größerer Eingriff
als die Konzept-Prämisse "Zwischenablage-Wächter". Nutzerentscheidung dazu (Abschnitt 5, Frage 4):
**Option 3, voller Funktionsumfang mit expliziter UI-Aufklärung** — **umgesetzt und auf dem
physischen Testgerät end-to-end live verifiziert** (3.2.7), standardmäßig weiterhin **aus** (Opt-in
über die neue "Cross-App-Erkennung"-UI plus die manuelle System-Bedienungshilfen-Freigabe).
**Anlass:** Nutzeranfrage — "einen Clipboard-Manager integrieren, der andere Apps überwacht,
Clipboard periodisch cleared etc."

---

## 0. Realitätscheck zuerst

Die wörtliche Anfrage ("andere Apps überwachen" + "periodisch clearen") kollidiert mit Androids
Zwischenablage-Datenschutzmodell seit API 29 — und Warden hat `minSdk = 35` (Android 15), läuft
also nie auf einem Gerät, das diese Einschränkungen nicht schon hätte. Wie beim FRP-Befund und dem
Kamera-`dumpsys`-Blindspot in `CLAUDE.md` gilt: erst die Plattformgrenze dokumentieren, dann bauen
— nicht ein Feature verkaufen, dessen Name mehr verspricht als die Plattform hergibt.

**Kernaussage:** "Sehen, was App X kopiert/einfügt" ist mit Warden als gewöhnlicher (nicht
priv-app-signierter) Device-Owner-App **nicht** erreichbar. Was erreichbar ist: Warden leert
**seine eigene Sicht** auf die Zwischenablage zuverlässig in jedem Moment, in dem es selbst
Fensterfokus hat, führt darüber ein Audit-Log und macht die längst vorhandenen OS-Schutzmechanismen
für den Nutzer sichtbar, statt sie stillschweigend zu duplizieren.

---

## 1. Plattformgrenzen

- **Android 10+ (API 29+):** `getPrimaryClip()`/`setPrimaryClip()`/
  `addPrimaryClipChangedListener()` wirken nur für die App im Fensterfokus oder die
  Standard-Tastatur (IME). Jede andere App — Device Owner eingeschlossen — bekommt leere Daten
  bzw. der Aufruf verpufft. Es gibt **keine** `DevicePolicyManager`-Methode, die das umgeht (anders
  als z. B. `setCameraDisabled`); geprüft gegen die aktuelle `DevicePolicyManager`-Referenz.
- **Android 12+ (API 31+):** Das OS zeigt selbst einen Toast ("App eingefügt aus Zwischenablage"),
  sobald eine App die Zwischenablage einer anderen liest. Kein programmatischer Hook dafür — Warden
  kann diesen Toast weder lesen noch abonnieren.
- **Android 13+ (API 33+):** Das OS leert die Zwischenablage automatisch nach einer internen
  Zeitspanne (öffentlich nicht konfigurierbar, kein API dafür).
- Es gibt keinen für eine reguläre App (auch Device Owner) erreichbaren Hebel, anderen Apps
  pauschal den Zwischenablage-Zugriff zu entziehen. Der bekannte
  `cmd appops set <pkg> READ_CLIPBOARD ignore`-Trick läuft über eine Shell/ADB-UID, nicht über
  DPM — Warden selbst kann `cmd appops` nicht aufrufen.
- Cross-Profile-Einschränkungen (`DISALLOW_SHARE_INTO_MANAGED_PROFILE`) betreffen nur
  Profile-Owner/Work-Profile-Szenarien. Warden ist reiner Device Owner ohne Work Profile — nicht
  anwendbar.

**Folge:** Weil `minSdk = 35`, sind Android 12's Zugriffs-Toast und Android 13's Auto-Clear auf
jedem Warden-Gerät bereits aktiv, bevor Warden auch nur eine Zeile Code dazu beiträgt. Alles unten
ist bewusst als **Ergänzung** dazu gerahmt (kürzeres Zeitfenster, sichtbares Audit-Log, manuelle
Kontrolle), nicht als Ersatz oder als Reimplementierung von OS-Funktionalität, die Warden ohnehin
nicht unterbieten kann.

---

## 2. Phase 1 — tatsächlich umsetzbar

### 2.1 Auslöser mit nachweisbarem Fokus (Warden darf hier leeren)

- `WardenStatusActivity.onResume()` — bei jedem Öffnen des Dashboards.
- Neuer, **ungegateter** Dashboard-Button "Zwischenablage jetzt leeren" — wie `LOCK_NOW` kein
  Presence-Gate nötig (reversibel, harmlos, Nutzer löst es selbst auf entsperrtem Gerät aus, siehe
  `ConcordBus.lockNow()`-Präzedenzfall in `CLAUDE.md`).
- Optional: Quick-Settings-Tile nach `SentinelQuickTile`-Vorbild, das über
  `Tile.startActivityAndCollapse` eine minimale, sofort `finish()`-ende Activity öffnet und dort
  leert — der `TileService`-Prozess selbst hat keinen Fensterfokus, ein Leeren direkt aus dem Tile
  heraus würde also verpuffen.

### 2.2 Auslöser, die vor dem Verbau live verifiziert werden müssen

- **Screen-Lock-getriggertes Leeren** nach `UsbLockStateReceiver`-Vorbild (`ACTION_SCREEN_OFF`,
  dynamisch registrierter Receiver, `RECEIVER_NOT_EXPORTED`). Der Broadcast kommt zuverlässig im
  Hauptprozess an — ob `setPrimaryClip()` in genau diesem Moment noch als "fokussiert" zählt, ist
  nicht dokumentiert. Gleiche Vorsicht wie beim FRP-Befund: erst auf dem physischen Testgerät
  messen, dann als aktiv bewerben.
- **Periodisches Leeren via WorkManager** (analog `UsbAutoLockWorker`, 15-Min-Takt): ein
  Hintergrund-Worker hat praktisch nie Fensterfokus — dieser Pfad würde in den allermeisten Ticks
  still zum No-Op. Nicht als Hauptmechanismus einplanen, höchstens als Nebeneffekt, falls Warden
  zufällig gerade offen ist.

### 2.3 Architektur (folgt dem etablierten `domain/*` + Framework-Package-Muster)

- `domain/clipboard/ClipboardClearDecision` — reine Funktion: (aktuelle `ClipDescription` inkl.
  `getTimestamp()`, konfigurierte Alters-Schwelle, Feature-Toggle-Zustand) → `Clear`/`Skip` + Grund.
  Framework-frei, JVM-unit-testbar wie `WardenPinDecision`.
- `clipboard/ClipboardGuardController` — ruft `setPrimaryClip(ClipData.newPlainText("", ""))`; das
  ist der einzige Weg zu "leeren", ein Löschen ohne neuen (leeren) Eintrag existiert nicht.
- `clipboard/ClipboardGuardStorage` — plain `SharedPreferences` (Toggle an/aus, Alters-Schwelle,
  Zeitpunkt letztes Leeren), analog `UsbAutoLockStorage`/`LockdownTriggerProfileStore`. Kein
  `EnvelopeFile` nötig — keine Geheimnisse, gleiche Begründung wie `RevokedPermissionStore`/
  `CellSecurityStorage`.
- **Kein `Safeguard`-Katalogeintrag:** Es gibt keine DPM-Operation dahinter, also passt das
  `Safeguard`-Interface (`apply()`/`revert()`/`isActive()` gegen echten Plattformzustand) nicht —
  das ist ein reiner App-Feature-Toggle, kein Command gegen die Plattform.
- **Audit-Log:** jeder erfolgreiche Clear schreibt einen Eintrag in `logging/HashChainLogStore`
  (Concords Hash-Chain) — **niemals den Zwischenablage-Inhalt selbst**, nur Metadaten (Zeitpunkt,
  Auslöser: manuell/Resume/Tile, ggf. geschätzte Zeichenlänge). Gleiches Prinzip wie
  `RevokedPermissionStore` für Berechtigungsnamen: kein Klartext ins Log.

### 2.4 UI

- Eigene kleine Sektion im Dashboard-Menü (kein `SafeguardsScreen`/`SafeguardUiCatalog`-Eintrag,
  s. o.) zwischen "Zugriff & Bestätigung" und "Wiederherstellung": Umschalter "Automatisch leeren"
  (`Switch`, gleiches Muster wie `SecurityScannerScreen`s Scanner-Toggle) + Subtitle, die explizit
  sagt, dass das nur bei Warden-Fokus wirkt (nicht "überwacht andere Apps"), MenuRow
  "Zwischenablage jetzt leeren", Zeile "Zuletzt geleert: <Zeitstempel>" sobald vorhanden.

### 2.5 Ergebnis — umgesetzt und live verifiziert (2026-09-03, physisches Testgerät)

Umgesetzt wie oben beschrieben: `domain/clipboard/ClipboardClearDecision` (+ 6 Unit-Tests, alle
grün), `clipboard/ClipboardGuardStorage`, `clipboard/ClipboardGuardController`,
`ConcordBus.isClipboardGuardEnabled`/`setClipboardGuardEnabled`/`clipboardLastClearedAt`/
`clearClipboardNow` (alle über `authorize()`, `NON_DESTRUCTIVE_SWITCH`/`READ` wie
`isUsbAutoLockEnabled`-Vorbild), `WardenStatusActivity.onWindowFocusChanged` (bewusst nicht
`onResume()`, s. Klassendoc dort) ruft `checkAndClearIfStale()`, neue Dashboard-Sektion (2.4).
`./gradlew build` (Lint + Unit-Tests + Debug-/Release-Assemble) durchgehend grün.

**Live-Test auf dem Testgerät ([[warden-physical-test-device]], Samsung SM-A156B, Android 16):**

1. Manueller Pfad: "Zwischenablage jetzt leeren" getippt → `ClipboardGuardController.clearNow()`
   lief erfolgreich, "Zuletzt geleert: 03.09.2026 12:21" erschien sofort in der UI (verifiziert via
   `uiautomator dump`, da Wardens Screens `FLAG_SECURE` tragen — `adb shell screencap` liefert dort
   immer Schwarzbild, s. `CLAUDE.md` "R8"-Abschnitt für dieselbe Einschränkung).
2. Auto-Clear-Pfad, die eigentlich entscheidende Frage aus Abschnitt 2.1/2.2: Text in Chrome
   kopiert (`com.android.chrome`, cross-app — anderer Prozess als Warden), Warden per
   `am start`/Task-Wechsel in den Vordergrund geholt. `onWindowFocusChanged(true)` feuerte,
   `checkAndClearIfStale()` las Chromes `ClipDescription` erfolgreich (Zeitstempel exakt der
   Kopierzeitpunkt) und entschied `Clear` — **bestätigt: Warden kann, sobald es selbst
   nachweislich Fensterfokus hat, die von einer fremden App geschriebene Zwischenablage lesen und
   leeren**, das zentrale Fokus-Argument aus Abschnitt 1/2.1 hält empirisch. Nach dem Clear zeigte
   Chromes eigene "Von dir kopierter Text"-Vorschlagszeile keinen Inhalt mehr (Tap auf die Zeile
   löste keine Navigation/Texteinfügung aus) — zweite, unabhängige Bestätigung neben dem Log.
3. **Bekannte kosmetische Lücke, nicht sicherheitsrelevant:** die "Zuletzt geleert"-Zeile lädt nur
   einmal pro `WardenScreen.Status`-Komposition (`LaunchedEffect(Unit)`) — ein automatischer Clear,
   der passiert, während der Nutzer bereits auf dem Dashboard verweilt (z. B. durch einen erneuten
   Fokuswechsel ohne den Status-Zweig zu verlassen), aktualisiert die Anzeige nicht sofort, erst
   beim nächsten Wiederbetreten des Status-Bildschirms. Der Clear selbst (Storage, Audit-Log)
   passiert trotzdem korrekt — nur die Dashboard-Anzeige kann kurz nachhinken. Nicht behoben in
   dieser Iteration (geringer Schaden, kein Bug im eigentlichen Mechanismus).

---

## 3. Cross-App-Monitoring (Phase 2, Nutzerentscheidung: AccessibilityService)

### 3.1 Warden als Standard-IME (Passthrough-Tastatur) — geprüft und verworfen

Einzige Möglichkeit an einen fokusunabhängigen *Inhalts*zugriff zu kommen (IME-Ausnahme in Androids
Modell). Kosten: Warden müsste eine vollwertige Tastatur implementieren oder per
`InputMethodService`-Proxy zur echten Tastatur durchreichen — großer Umfang, hohe
Fehleranfälligkeit, und ironischerweise genau die Rechte-Kombination (voller Zugriff auf alles
Getippte inkl. Passwörter), die `AccessibilityServiceScanner`/`SuspiciousAppScanController` bei
*anderen* Apps als `WARNING`/`CRITICAL` einstuft. Auf Nutzerentscheidung 2026-09-03 hin **nicht**
weiterverfolgt — 3.2 ist der gewählte Weg.

### 3.2 Eigener AccessibilityService — gewählter Ansatz für Phase 2

**Wichtige Einschränkung vorab:** Ein AccessibilityService bekommt zu keinem Zeitpunkt
Zwischenablage-*Inhalte*. Was er liefern kann, ist ein **Ereignis-Signal**: "App X hat um HH:MM
etwas kopiert/eingefügt" — nie das *was*. Das ist strukturell dieselbe Grenze aus Abschnitt 1, nur
von der anderen Seite betrachtet: `ClipboardManager` bleibt für Warden weiterhin gesperrt, ein
AccessibilityService liest stattdessen die UI-Ereignis-Ebene, die davon unabhängig ist.

#### 3.2.1 Zwei unabhängige Signalquellen, unterschiedlich zuverlässig

1. **System-Toast abfangen (primärer Kandidat, Zuverlässigkeit ungeprüft).** Der
   Android-12+-Zugriffs-Toast ("*App* hat aus deiner Zwischenablage eingefügt", s. Abschnitt 1) ist
   ein gewöhnlicher `Toast` — Toasts werden seit jeher als `AccessibilityEvent
   .TYPE_NOTIFICATION_STATE_CHANGED` an aktive AccessibilityServices gemeldet (Grundlage jeder
   TalkBack-Vorlesefunktion für Toasts). Ein `AccessibilityService`, der `TYPE_NOTIFICATION_STATE_
   CHANGED` abonniert, sollte diesen Toast also grundsätzlich sehen können.
   - `event.getPackageName()` zeigt bei einem System-Toast vermutlich das System-Package (`android`
     bzw. den Package, der den Toast rendert), **nicht** die lesende App — die einzige Quelle für
     "wer" ist der lokalisierte Toast-**Text** selbst (`event.getText()`), der den App-*Namen*
     (nicht den Package-Namen) enthält. Textparsing eines lokalisierten Systemstrings ist fragil:
     abhängig von Android-Version, Sprache, und möglicher OEM-Überlagerung (Samsung One UI stylt/
     ersetzt System-Toasts teils eigenständig — auf dem Testgerät bislang nicht für diesen
     spezifischen Toast geprüft).
   - Aus dem App-*Namen* lässt sich der Package-Name nur über einen Zusatzschritt rückgewinnen
     (`PackageManager.getInstalledApplications()` + Label-Abgleich) — mehrdeutig bei
     namensgleichen Apps, im Audit-Log ohnehin nur als Bestlabel zu vermerken, nicht als
     verlässliche Identität.
   - Falls der Nutzer den Toast in den System-Einstellungen deaktiviert hat (dokumentiert
     abschaltbar, s. Quellen unten), verschwindet dieses Signal komplett — Warden hat keine
     Möglichkeit, das zu erkennen oder zu verhindern.
2. **Copy/Paste-UI-Aktionen in fremden Fenstern (sekundäres, ergänzendes Signal).**
   `AccessibilityNodeInfo.ACTION_PASTE`/`ACTION_COPY`/`ACTION_CUT`, ausgelöst über
   Kontextmenü-Auswahl, sind als Actions auf dem Node sichtbar, aber es gibt **kein** dediziertes
   `AccessibilityEvent`, das "gerade wurde kopiert" meldet — beobachtbar ist bestenfalls
   `TYPE_VIEW_TEXT_SELECTION_CHANGED` gefolgt von einer Menüinteraktion, ein Heuristik-Signal, kein
   zuverlässiges Ereignis. Nützlich höchstens als Zusatzindiz, nicht als Primärquelle.

Beide Signale sind **Hypothesen**, keine verifizierten Tatsachen — im Unterschied zu Abschnitt 1
(offizielle Android-Dokumentation) beruht 3.2.1 auf Ableitung aus bekanntem
Toast-Accessibility-Verhalten, nicht auf einem bestätigten Testlauf. Genau wie beim FRP-Befund und
dem Kamera-`dumpsys`-Blindspot in `CLAUDE.md` gilt: nicht als funktionierendes Feature einplanen,
bevor es auf dem physischen Testgerät ([[warden-physical-test-device]]) bestätigt ist.

#### 3.2.2 Der Zielkonflikt, den dieser Ansatz eingeht

Warden würde selbst eine `AccessibilityService`-Deklaration tragen — exakt die Kapazität, die
`AccessibilityServiceScanner`/`ThreatSeverity` bei einer *fremden* App als `WARNING`-Signal
einstuft ("newly activated accessibility" sogar als `CRITICAL`-Transition, s. `CLAUDE.md`
Abschnitt "Threat severity"). Zwei Folgen, beide vor Bau explizit zu entscheiden:
- Wardens eigener Scanner müsste Wardens eigenes Package von der Selbstmeldung ausnehmen (Analogie
  zu `AppManagementController.SUITE_PACKAGE_NAMES`, das Sentinel vor Wardens Freeze-Pfad schützt) —
  sonst meldet Warden sich selbst als verdächtig.
- Nach außen (z. B. gegenüber dem Nutzer beim Berechtigungs-Dialog, oder gegenüber einem
  aufmerksamen Dritten, der Wardens eigene Manifest-Deklarationen prüft) trägt eine
  Hardening-App damit dieselbe Rechte-Kategorie, vor der sie andere Apps warnt. Nicht unlösbar,
  aber im UI/Dashboard offen zu kommunizieren ("Warden nutzt selbst eine Bedienungshilfe, um X zu
  erkennen"), nicht zu verstecken.

#### 3.2.3 Architektur-Skizze

- `clipboard/ClipboardAccessibilityService : AccessibilityService` — abonniert
  `TYPE_NOTIFICATION_STATE_CHANGED` (+ testweise `TYPE_WINDOW_STATE_CHANGED` als Fallback-Signal),
  kein `canRetrieveWindowContent` nötig für den Toast-Pfad, wohl aber für den Node-Actions-Pfad
  (3.2.1 Punkt 2), was den Berechtigungsdialog entsprechend umfangreicher macht.
- `domain/clipboard/ClipboardAccessEvent` — reine Datenklasse (Zeitpunkt, App-Label-Bestwert,
  Quelle: Toast/Node-Heuristik/unbekannt, Konfidenz), von der Framework-Seite befüllt, ab da
  framework-frei weiterverarbeitet (Filterung/Deduplizierung als `domain/clipboard/
  ClipboardAccessDecision`, unit-testbar).
- Ereignisse landen — wie in Abschnitt 2.3 für Clear-Events beschrieben — als Metadaten (nie
  Zwischenablage-Inhalt) im `HashChainLogStore`, zusätzlich sichtbar als kurze Liste im
  ClipboardGuard-UI ("zuletzt beobachtete Zugriffe").
- Eigenes Opt-in erforderlich (Bedienungshilfen-Berechtigung ist ein manueller
  Einstellungen-Umweg, kein DPM-Grant) — UI muss den Nutzer dorthin führen, analog wie andere
  Screens bereits auf externe Settings-Screens verlinken.

#### 3.2.4 Vorgeschlagener erster Schritt: Spike statt Vollbau

Bevor Storage/UI/Audit-Log-Anbindung gebaut werden: ein isolierter Testlauf auf dem physischen
Testgerät, der nur klärt, *ob* Signal 1 (Toast-Interception) überhaupt ankommt, mit welchem
`packageName`/Text, und ob Samsung One UI es verändert. Ergebnis entscheidet, ob 3.2 als
verlässliches Feature taugt oder nur als "Bestes-Indiz, keine Garantie" gekennzeichnet ausgeliefert
werden kann (ähnlich der FRP-Warnung in `SafeguardsScreen.kt`).

#### 3.2.5 Spike-Ergebnis (2026-09-03, physisches Testgerät): Signal 1 falsifiziert

Durchgeführt genau wie 3.2.4 beschrieben, danach vollständig entfernt (temporärer
`ClipboardSpikeAccessibilityService` nur in `app/src/debug/`, `res/xml`-Konfig,
Manifest-Eintrag — gleiche "für den Test hinzugefügt, danach restlos entfernt"-Konvention wie
Sentinels temporärer unprotected Test-Receiver, `CLAUDE.md` "Escalation path live-verified
2026-08-30").

**Aufbau:** Debug-Build-only `AccessibilityService`, abonniert `TYPE_NOTIFICATION_STATE_CHANGED` +
`TYPE_WINDOW_STATE_CHANGED`, loggt jedes Ereignis nach Logcat. Aktiviert via
`adb shell settings put secure enabled_accessibility_services` (kein UI-Einwilligungsdialog nötig,
da über Shell-UID gesetzt) — `dumpsys accessibility` bestätigte den Dienst als gebunden.

**Neuer Fund unterwegs, nicht in Abschnitt 1 vorausgesehen:** `Settings.Global
.clipboard_show_access_notifications` — ein Schalter, der auf diesem Gerät den
Android-12+-Zugriffs-Toast steuert, stand auf `0` (deaktiviert), ohne dass eine offizielle Quelle
das vorab genannt hätte. Für den Test explizit auf `1` gesetzt und per `settings get` bestätigt.

**Testablauf, dreifach unabhängig wiederholt** (`com.android.settings.intelligence`-Suchfeld via
`KEYCODE_PASTE`; `com.spotify.music`-E-Mail-Login-Feld, zweimal mit je frischem, eindeutigem
Zwischenablage-Inhalt): Chrome (`com.android.chrome`) schreibt Text in die Zwischenablage — echter
App-Wechsel, kein Selbst-Lese-Sonderfall (s. Abschnitt 1) —, die Zielapp fügt ihn per
`KEYCODE_PASTE` ein. Der eingefügte Text erschien jedes Mal korrekt im UI-Baum
(`uiautomator dump`) — der Cross-App-Lesezugriff hat also nachweislich stattgefunden.

**Ergebnis: kein einziges Mal ein `TYPE_NOTIFICATION_STATE_CHANGED`-Ereignis für den
Zwischenablage-Toast**, weder mit noch ohne den `clipboard_show_access_notifications`-Schalter.
Der AccessibilityService selbst war nachweislich aktiv und funktionsfähig — er empfing im selben
Testlauf andere, nicht-clipboard-bezogene `TYPE_WINDOW_STATE_CHANGED`-Ereignisse korrekt (z. B. von
Samsungs eigener Tastatur, von Spotifys UI). Es fehlt spezifisch das Toast-Ereignis, nicht die
Ereignis-Zustellung insgesamt. Auch ein `uiautomator dump` unmittelbar nach dem Einfügen zeigte
kein separates Toast-Fenster (kann an knappem Timing liegen — Toasts sind sehr kurzlebig — oder
daran, dass Samsungs eigener Zwischenablage-Dienst (`android.sec.clipboard.IClipboardService`,
neben dem AOSP-`android.content.IClipboard`, s. `adb shell service list`) den Toast über einen
anderen, nicht standardmäßig als Accessibility-Ereignis exponierten Mechanismus rendert, oder ihn
auf diesem Gerät/OS-Stand ganz unterdrückt).

**Folge für Abschnitt 3.2:** Die primäre Signalquelle (3.2.1, Punkt 1: Toast-Interception) ist auf
diesem Testgerät empirisch **nicht** nutzbar — dieselbe "Root-caused, nicht nur behauptet"-Sorgfalt
wie beim Kamera-Befund in `CLAUDE.md`, nur mit umgekehrtem Ausgang (dort bestätigte der zweite,
unabhängige Test die Wirksamkeit; hier widerlegt der Test die Hypothese). Die sekundäre,
ohnehin schon als schwächer eingestufte Heuristik (3.2.1, Punkt 2: Node-Copy/Paste-Aktionen) wurde
nicht separat getestet und bleibt die einzige noch unrefutierte Option für Phase 2 — aber ohne
Garantie, da sie von vornherein als Heuristik ohne dediziertes Ereignis beschrieben war.

**Offene Frage, durch 3.2.6 unten überholt:** ob das Fehlen des Toast-Ereignisses gerätespezifisch
ist, ist inzwischen zweitrangig — der unverfälschte Logcat-Befund in 3.2.6 zeigt, dass auf diesem
Gerät für dieses Szenario **kein** System-Toast/keine Notification überhaupt gerendert wird
(kein Fenster-Add, kein Notification-Post). Jede Methode, die *dieses* Ereignis abfangen wollte
(Toast-Interception hier, aber auch ein alternativ erwogener `NotificationListenerService` —
Toasts sind ohnehin nie echte `Notification`-Objekte, ein Listener hätte also strukturell dasselbe
Problem gehabt), wäre am selben Punkt gescheitert. Root-Ursache blieb offen (Samsungs eigener
`android.sec.clipboard.IClipboardService` neben AOSPs `android.content.IClipboard`, s. `adb shell
service list`, könnte den Mechanismus ersetzt/entfernt haben) — für die Methodenwahl unten nicht
mehr relevant.

### 3.2.6 Zweiter Spike (2026-09-03, "eine andere Methode"): Signal 2 funktioniert — geht aber
weiter als beabsichtigt

Auf Nutzeranfrage ("dann überlege eine andere Methode") ein zweiter, gezielter Test von Signal 2
(3.2.1, Punkt 2), bewusst unabhängig vom widerlegten OS-Toast-Mechanismus: derselbe Debug-only-
`AccessibilityService`-Aufbau wie 3.2.5 (danach ebenso vollständig entfernt), diesmal abonniert auf
`TYPE_VIEW_TEXT_CHANGED`/`TYPE_VIEW_TEXT_SELECTION_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` mit
`canRetrieveWindowContent="true"`, getestet mit einer echten Lang-Druck-Geste (nicht
`KEYCODE_PASTE`) gegen die native "Einfügen"-Textauswahl-Toolbar in `com.android.settings
.intelligence`s Suchfeld (Chrome schreibt den Text, echter Cross-App-Wechsel wie zuvor).

**Ergebnis: eindeutig positiv.** Der Tap auf "Einfügen" löste sofort ein
`TYPE_VIEW_TEXT_CHANGED`-Ereignis aus — `package=com.android.settings.intelligence`,
`viewIdResName=…:id/search_src_text`, **`text=[signal2test]`** (der exakte, gerade eingefügte
Zwischenablage-Inhalt, wortgleich). Reproduzierbar, sofort, ohne die Fragilität des ersten
Durchgangs.

**Das ist aber ein größerer Hebel als Abschnitt 3.2.1 ursprünglich beschrieben hat.** Dort war
Signal 2 als schwache Heuristik gerahmt — "eine Paste-*Aktion* wurde ausgeführt, ohne Inhalt". Der
Test zeigt: mit `canRetrieveWindowContent="true"` liefert `TYPE_VIEW_TEXT_CHANGED` den **tatsächlichen
Text** jeder sichtbaren Textänderung in jedem fremden, nicht besonders geschützten Eingabefeld —
nicht nur bei einem Paste-Vorgang, sondern bei jeder Texteingabe/-änderung, die die Bedienungshilfe-
API generell exponiert. Der Mechanismus unterscheidet strukturell nicht zwischen "Nutzer tippt sein
Passwort" und "Nutzer fügt aus der Zwischenablage ein" — beides sind für die Accessibility-API
identische `TYPE_VIEW_TEXT_CHANGED`-Ereignisse mit demselben `text`-Feld. Anders gesagt: dieselbe
technische Fähigkeit, die "Zwischenablage-Zugriff durch andere Apps sichtbar machen" lösen würde,
**ist bereits ein vollwertiger Screen-Content-/Keylogger** für jedes normale (nicht durch
`FLAG_SECURE` oder Passwort-Feld-Kennzeichnung geschützte) Eingabefeld auf dem Gerät — weit über
"Zwischenablage beobachten" hinaus.

**Konsequenz, bevor hieran weitergebaut wird:** Das ist keine graduelle Verschärfung des in 3.2.2
schon benannten Zielkonflikts (Warden trägt selbst die Rechte-Kategorie, die es bei anderen Apps als
Warnsignal wertet) — es ist eine kategorial andere Aussage. 3.2.2 ging von "Warden beobachtet
Paste-*Ereignisse*" aus; der reale Mechanismus liefert "Warden kann jeden sichtbaren Tastatur-/
Einfüge-Vorgang in jeder fremden App im Klartext mitlesen". Eine Sicherheits-Hardening-App, die
diese Fähigkeit einbaut — und sei es nur mit der Absicht, sie auf Zwischenablage-Momente zu
beschränken —, besitzt strukturell dieselbe Fähigkeit wie die invasivste Kategorie von Spyware, die
`SuspiciousAppScanController` bei anderen Apps erkennen soll; eine reine Absichtserklärung im
eigenen Code ("wir lesen `text` nur bei Paste-Events aus") ist keine technische Schranke, sondern
nur eine Konvention, die jede spätere Codeänderung wieder aufheben kann. Das ist eine Entscheidung,
die der Nutzer bewusst und mit dieser Tragweite treffen muss, bevor irgendein Produktionscode dafür
entsteht — nicht mehr nur "welcher Ansatz ist technisch eleganter", sondern "will Warden diese
Fähigkeit überhaupt im Code tragen". Deshalb hier bewusst gestoppt: kein `clipboard
/ClipboardAccessibilityService`-Vollbau in dieser Iteration, s. Abschnitt 5 für die konkrete
Entscheidungsfrage.

### 3.2.7 Produktionsversion gebaut und live verifiziert (2026-09-03, Nutzerentscheidung "3": voller
Funktionsumfang mit expliziter UI-Aufklärung)

Auf Abschnitt 5, Frage 4, Option 3 hin vollständig als Produktionscode gebaut (nicht mehr `app/
src/debug/`) — Architektur folgt genau der in 3.2.3 skizzierten Trennung, mit den in 3.2.6
angekündigten Anpassungen:

- **Domain:** `domain/clipboard/ClipboardAccessEvent` (Datenklasse), `ClipboardAccessDecision`
  (reine Entscheidung: vier Filter — App-Präferenz, eigenes Package, Passwortfeld,
  Paste-Burst-Schwelle `MIN_BURST_CHARS=3`, s. dortiges Klassendoc für die Begründung jedes
  einzelnen), `ClipboardAccessCodec` (Zeilenformat fürs Ringpuffer-Store, mit Escape statt
  Ersetzen für Zeilenumbrüche im erfassten Text — anders als `SecurityLogCodec`, weil hier der
  Nutzertext selbst die eigentliche Information ist). Alle drei framework-frei, unit-getestet.
- **Framework:** `clipboard/ClipboardAccessibilityService` (der eigentliche `AccessibilityService`,
  abonniert nur `typeViewTextChanged`, `canRetrieveWindowContent="true"`),
  `ClipboardAccessController` (Speichern/Lesen/Löschen, plus die Metadaten-only-Audit-Log-Zeile),
  `ClipboardAccessEventStore`/`ClipboardAccessEventStorage` (eigene `EnvelopeFile`, normaler statt
  Device-Protected Storage — ein `AccessibilityService` läuft nie vor dem ersten Entsperren, s.
  dortiges Klassendoc), `ClipboardAccessibilityStatus` (System-Freigabe-Status +
  Einstellungen-Intent, analog `AppUsageReader` für den Nutzungsdatenzugriff).
- **UI:** eigener Bildschirm `ClipboardCrossAppScreen` (nicht in die Dashboard-Sektion
  gequetscht) mit einer **dauerhaft sichtbaren** Aufklärungskarte (kein Einmal-Dialog — dieselbe
  "bleibt präsent, solange die Funktion aktiv ist"-Überlegung wie die FRP-/`isDebuggableOs`-
  Warnungen auf der StatusCard), eigenem Opt-in-Schalter, System-Freigabe-Statuszeile mit direktem
  Link zu Einstellungen → Bedienungshilfen, und einer Ereignisliste mit standardmäßig maskierter
  Textvorschau (Antippen zeigt Klartext — Schulterblick-Schutz, kein reduzierter Funktionsumfang:
  der Text wird unverändert erfasst/gespeichert, nur die Anzeige ist zurückhaltend).
- **Selbstausnahme bereits vorhanden, keine neue Änderung nötig:** `SuspiciousAppScanDecision`
  schließt `ownPackageName` bereits strukturell von *jedem* Fund aus (`excluded = …+ownPackageName+
  …`, greift für `ACCESSIBILITY_SERVICE_DECLARED` genau wie für jedes andere Signal) — der in 3.2.2
  befürchtete Fall "Warden meldet sich selbst als verdächtig" tritt nicht ein, verifiziert durch
  Lesen von `SuspiciousAppScanDecision.kt`, nicht nur angenommen.

**Live-Verifikation auf dem physischen Testgerät, echte Cross-App-Kette (nicht mehr Debug-Spike):**
Bedienungshilfe über `adb shell settings put secure enabled_accessibility_services` freigegeben
(band beim ersten Versuch, `dumpsys accessibility` zeigte sofort `Bound services` mit
`capabilities=1, eventTypes=TYPE_VIEW_TEXT_CHANGED` — exakt wie in `clipboard_accessibility_config
.xml` deklariert), App-Präferenz direkt in den SharedPreferences gesetzt (`run-as`, echtes Gerät,
kein Emulator). Danach ein **echter** Kopiervorgang in Chrome (Text in die Adressleiste getippt,
per Doppeltipp markiert, "Kopieren" aus der echten Auswahl-Toolbar angetippt — Screenshot bestätigt
die Markierung/Toolbar) und ein **echter** Cross-App-Paste in `com.android.settings.intelligence`s
Suchfeld (Antippen des Tastatur-eigenen Zwischenablage-Vorschlag-Chips, ebenfalls ein echter
Nutzer-Vorgang, kein `KEYCODE_PASTE`-Kurzschluss). Temporär eine einzelne Diagnose-`Log.d`-Zeile in
`ClipboardAccessController.recordAccess` ergänzt (nach dem `try`-Block sitzenden
Erfolgspfad, danach vollständig entfernt — dieselbe Konvention wie jeder andere Spike in diesem
Dokument), um den kompletten Pfad bis zum Store-Schreiben zu bestätigen, nicht nur den
Ereignisempfang:

```
D ClipboardAccess: SPIKE erfasst: pkg=com.android.settings.intelligence
    label=Einstellungsvorschläge textLen=19 text=wardenprodtest99887
```

Zeitgleich bestätigt `run-as … ls -la files/` neu angelegte `clipboard_access_events.{envelope,dek}`
mit demselben Zeitstempel — die `EnvelopeFile`-Verschlüsselung lief ohne Fehler durch, nicht nur
der Ereignisempfang. **Negativkontrolle im selben Durchlauf:** ein 2-Zeichen-Tippvorgang
("ab", `input text` in dasselbe Suchfeld) erzeugte **keinen** Log-Eintrag — der
`MIN_BURST_CHARS=3`-Filter greift live nachweislich, nicht nur in der Unit-Test-Simulation.

**Gerät danach vollständig zurückgesetzt** (Diagnosezeile entfernt, sauberer Build neu installiert,
Bedienungshilfen-Freigabe gelöscht, App-Präferenz auf `false`, Test-Envelope-Dateien gelöscht,
`./gradlew build` erneut grün) — derselbe "sauber hinterlassen"-Standard wie jeder frühere Spike in
diesem Dokument. Die Funktion ist damit fertig gebaut, aber standardmäßig **weiterhin aus** (beide
Opt-ins auf `false`/nicht freigegeben) — Aktivierung bleibt eine bewusste Entscheidung des
Geräteinhabers über die neue UI, nicht etwas, das dieser Build automatisch scharf schaltet.

**Nicht separat live getestet, mit Begründung:** das `isPassword`-Ausschlusskriterium (kein
bequem erreichbares echtes Passwortfeld im Testlauf zur Hand) und der `ownPackageName`-Ausschluss
(würde erfordern, Wardens eigene UI testweise wieder als Ziel zuzulassen — höheres Risiko für
wenig zusätzliche Aussagekraft, da beide Filter dieselbe einfache String-Vergleichslogik sind wie
die bereits live bestätigten, und `ClipboardAccessDecisionTest` beide explizit abdeckt).

### 3.2.8 Sensible-Einfügung-Alarm (2026-09-03, Folgegespräch nach 3.2.7)

Erste tatsächliche Auswertung des in 3.2.7 gebauten Textinhalts, nicht nur Speicherung/Anzeige:
`domain/clipboard/SensitiveContentDetector` (framework-frei, unit-getestet) prüft den von
`ClipboardAccessibilityService` erfassten Feldinhalt auf drei grobe Muster — eine
Seed-Phrase-artige Wortfolge (12/15/18/21/24 durchgehend kleingeschriebene 3-8-Zeichen-Token,
**keine echte BIP-39-Wortliste**, bewusst: die vollen 2048 Wörter wären ein spürbarer
Umfangs-/Pflegeaufwand für eine ohnehin nur grobe Heuristik), eine Kartennummer-artige Ziffernfolge
(13-19 Ziffern, Luhn-validiert, eigene Implementierung statt Bibliothek) und ein API-Key-artiges
Token (bekannte Präfixe wie `sk-`/`AKIA`/`ghp_`/`AIza` oder ein generischer
"lang, gemischtgroß, mit Ziffer"-Heuristik-Fallback). Sucht im **gesamten** erfassten Feldtext nach
einem Treffer, nicht nur im eingefügten Teilstück — derselbe Grund wie in 3.2.6/3.2.7 dokumentiert:
`ClipboardAccessibilityService` liefert immer `event.text` (den kompletten aktuellen Feldinhalt),
nie nur die eingefügte Differenz.

Bei einem Treffer: eine eigene, von der ClipboardGuard-Historie unabhängige Senke —
`ClipboardSensitiveContentNotifier` (`IMPORTANCE_HIGH`, trägt **nie** den erfassten Text selbst,
nur die erkannte Kategorie) plus eine `WARN`-Audit-Log-Zeile. Läuft in
`ClipboardAccessController.recordAccess()` **unabhängig** vom bereits vorhandenen
Historien-Speichervorgang direkt darüber (eigenes try/catch) — ein fehlgeschlagenes Speichern des
Verlaufseintrags darf die Alarmierung nicht mit sich reißen, beides sind unabhängige Senken für
dasselbe Ereignis.

### 3.3 Warum nicht per DevicePolicyManager erzwingen

Es gibt keine `setClipboard*`-Methode in `DevicePolicyManager` — anders als Kamera
(`setCameraDisabled`) oder Screen-Capture. Cross-Profile-Clipboard betrifft nur
Work-Profile-Szenarien, die Warden nicht hat (s. Abschnitt 1).

---

## 4. Vorgeschlagene Umsetzungsreihenfolge (Phase 1)

1. `domain/clipboard/ClipboardClearDecision` + Unit-Tests (framework-frei, sofort testbar).
2. `clipboard/ClipboardGuardStorage` + `ClipboardGuardController` + manueller Dashboard-Button
   (kein Presence-Gate).
3. Audit-Log-Anbindung (`HashChainLogStore`).
4. `StatusCard`-Warnzeile + Toggle-UI.
5. Quick-Settings-Tile (optional, gleiche Iteration oder danach).
6. Erst nach Live-Test auf dem physischen Testgerät: Resume- und Screen-Lock-getriggertes
   Auto-Clear als Standard aktivieren.

## 4a. Umsetzungsreihenfolge Phase 2 (AccessibilityService, nach Phase 1)

1. ~~**Spike zuerst** (3.2.4): Toast-Interception isoliert auf dem physischen Testgerät prüfen~~ —
   **erledigt, Ergebnis negativ** (3.2.5): kein `TYPE_NOTIFICATION_STATE_CHANGED` für den
   Zugriffs-Toast, dreifach reproduziert. Signal 1 damit als Grundlage für 3.2.3 vorerst nicht
   verwendbar.
2. Vor jedem Weiterbau: die offene Frage aus 3.2.5 klären (gerätespezifisch vs. generell) — z. B.
   denselben Spike auf einem zweiten, nicht-Samsung-Testgerät (Stock-Android/AOSP-nah)
   wiederholen, oder Signal 2 (Node-Copy/Paste-Heuristik, 3.2.1 Punkt 2) isoliert spiken, bevor
   `clipboard/ClipboardAccessibilityService` gebaut wird — kein Vollbau auf einer bereits
   widerlegten Annahme.
3. Erst wenn eine tragfähige Signalquelle bestätigt ist: `clipboard/ClipboardAccessibilityService` +
   `domain/clipboard/ClipboardAccessEvent`/`ClipboardAccessDecision` (3.2.3) bauen, inkl.
   Selbstausnahme in `AccessibilityServiceScanner` (3.2.2) und Opt-in-UI-Führung zu den
   Bedienungshilfen-Einstellungen.
4. Zugriffs-Ereignisse ins `HashChainLogStore` + eigene UI-Liste ("zuletzt beobachtete Zugriffe"),
   durchgängig mit "Bestes Indiz, keine Garantie"-Kennzeichnung, solange Signal-Zuverlässigkeit
   nicht über mehrere Apps/Szenarien hinweg bestätigt ist.

---

## 5. Offene Fragen an den Nutzer

Fragen 1–3 sind durch die Umsetzung/Live-Tests inzwischen erledigt (Auto-Clear: sofort bei
Fensterfokus, kein Alters-Schwellwert nötig, s. 2.5; eigene Dashboard-Sektion "Zwischenablage",
s. `WardenStatusActivity.kt`; Spike lief nach Phase 1). Die verbleibende, entscheidende Frage:

4. **Soll Phase 2 (AccessibilityService) überhaupt in Produktionscode gebaut werden — mit dem
   jetzt bekannten tatsächlichen Umfang?** — **Entschieden: Option 3** ("Bauen mit vollem
   Funktionsumfang, mit expliziter Nutzeraufklärung im UI selbst"), umgesetzt und live verifiziert,
   s. Abschnitt 3.2.7. Die drei Optionen waren:
   - Nicht bauen.
   - Bauen, aber hart eingegrenzt (nie `text` lesen).
   - **→ Gewählt:** Bauen mit vollem Funktionsumfang, expliziter UI-Aufklärung
     ([ClipboardCrossAppScreen], dauerhaft sichtbare Aufklärungskarte, kein Einmal-Dialog).
