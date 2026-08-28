package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ble1st.warden.appmanagement.SentinelInstallStatus
import de.ble1st.warden.domain.pin.LockdownTriggerProfile
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.profile.WardenProfileSpec

/**
 * Untermenü "Geräteschutz ▸ Safeguards". Profile (Alltag / Reise / Maximal) apply a named set
 * through Concord in one authorized call; individual switches remain for fine-tuning.
 */
data class SafeguardToggleState(
    val locked: Boolean?,
    val onToggle: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeguardsScreen(
    cameraLocked: SafeguardToggleState,
    screenCaptureLocked: SafeguardToggleState,
    microphoneMuted: SafeguardToggleState,
    clockIntegrity: SafeguardToggleState,
    selfUninstallProtection: SafeguardToggleState,
    forceStopProtection: SafeguardToggleState,
    credentialConfigLockdown: SafeguardToggleState,
    physicalMediaMountLockdown: SafeguardToggleState,
    keyguardHardening: SafeguardToggleState,
    accessibilityLockdown: SafeguardToggleState,
    inputMethodLockdown: SafeguardToggleState,
    securityLogging: SafeguardToggleState,
    networkLogging: SafeguardToggleState,
    passwordComplexity: SafeguardToggleState,
    autoLockTimeout: SafeguardToggleState,
    backupServiceLockdown: SafeguardToggleState,
    systemUpdatePolicy: SafeguardToggleState,
    lockScreenPrivacy: SafeguardToggleState,
    usbAutoLock: SafeguardToggleState,
    usbPermanentlyDisabled: SafeguardToggleState,
    sentinelUninstallProtection: SafeguardToggleState,
    installUnknownSourcesDisabled: SafeguardToggleState,
    factoryResetDisabled: SafeguardToggleState,
    safeBootDisabled: SafeguardToggleState,
    factoryResetProtection: SafeguardToggleState,
    modifyAccountsDisabled: SafeguardToggleState,
    debuggingFeaturesDisabled: SafeguardToggleState,
    addUserDisabled: SafeguardToggleState,
    cellular2gDisabled: SafeguardToggleState,
    configVpnDisabled: SafeguardToggleState,
    usbFileTransferDisabled: SafeguardToggleState,
    nfcRadioDisabled: SafeguardToggleState,
    bluetoothSharingDisabled: SafeguardToggleState,
    factoryResetProtectionAccounts: String,
    factoryResetProtectionAgentAvailable: Boolean,
    onSaveFactoryResetProtectionAccounts: (String) -> Unit,
    lockdownModeActive: Boolean?,
    profileApplyWarning: String?,
    onApplyProfile: (WardenProfile) -> Unit,
    emergencyDrillConfirmed: Boolean,
    emergencyDrillConfirmedAtText: String?,
    onConfirmEmergencyDrill: () -> Unit,
    onRevokeEmergencyDrill: () -> Unit,
    autoEngageOnCriticalThreat: Boolean,
    onAutoEngageOnCriticalThreatChange: (Boolean) -> Unit,
    sentinelLockTaskAuthorized: Boolean?,
    sentinelInstallStatus: SentinelInstallStatus,
    onInstallSentinel: () -> Unit,
    onRefreshSentinelInstallStatus: () -> Unit,
    lockdownTriggerProfile: LockdownTriggerProfile,
    onLockdownTriggerProfileChange: (LockdownTriggerProfile) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safeguards") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Geräteweite DevicePolicyManager-Sperren — sofort wirksam, jederzeit reversibel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            ProfilePicker(onApplyProfile = onApplyProfile)
            if (profileApplyWarning != null) {
                Text(
                    text = "⚠ $profileApplyWarning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Alltagsbetrieb — Zurücksetzen und Schnellzugriff.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            ResetProtectionEntryRow(
                label = "Zurücksetzen in den Einstellungen blockieren",
                state = factoryResetDisabled,
                supportingText = "Blockiert Werksreset unter Einstellungen. Kein wipeData().",
            )
            HorizontalDivider()
            SafeguardEntryRow(
                label = "Abgesicherten Modus blockieren",
                state = safeBootDisabled,
                supportingText = "Verhindert Safe Boot als Recovery-nahen Umgehungsweg.",
            )
            HorizontalDivider()
            FactoryResetProtectionAccountsField(
                initialValue = factoryResetProtectionAccounts,
                onSave = onSaveFactoryResetProtectionAccounts,
            )
            HorizontalDivider()
            ResetProtectionEntryRow(
                label = "Nach Recovery-Wipe Konto verlangen",
                state = factoryResetProtection,
                supportingText = buildString {
                    append(
                        "Recovery-Wipe bleibt möglich, Gerät ist danach ohne dieses Konto nicht " +
                            "neu einzurichten. Wirkt nur bei gesperrtem Bootloader — bei OEM-Unlock " +
                            "wirkungslos. Braucht Google-Play-Dienste / FRP-Agent. ⚠ Auf echter " +
                            "Hardware (Samsung SM-A156B, 2026-08-25) hat ein Recovery-Wipe trotz " +
                            "korrekt gesetzter Policy keine Konto-Abfrage ausgelöst — nicht als " +
                            "verlässlichen Schutz behandeln, s. FactoryResetProtectionSafeguard.",
                    )
                    if (!factoryResetProtectionAgentAvailable) {
                        append(
                            " ⚠ Google-Play-Dienste nicht gefunden — Schalter zeigt \"aktiv\", " +
                                "wird vom FRP-Agenten aber vermutlich nicht durchgesetzt.",
                        )
                    }
                },
                toggleEnabled = factoryResetProtection.locked != null &&
                    (factoryResetProtectionAccounts.isNotBlank() || factoryResetProtection.locked == true),
            )
            HorizontalDivider()
            ResetProtectionEntryRow(
                label = "Konten in den Einstellungen nicht ändern lassen",
                state = modifyAccountsDisabled,
                supportingText = "Verhindert, dass jemand das Entsperrkonto vor dem Wipe löscht.",
            )
            HorizontalDivider()
            ConfirmBeforeEnableEntryRow(
                label = "Entwickleroptionen/USB-Debugging sperren",
                state = debuggingFeaturesDisabled,
                supportingText = "Verhindert adb-Zugriff durch Angreifer mit physischem Zugriff " +
                    "(Feature 35). ⚠ Lässt sich laut Android nicht mehr über die " +
                    "Entwickleroptionen zurücknehmen, solange aktiv — auf einem per USB an einen " +
                    "Entwicklungsrechner angeschlossenen Gerät kappt das Einschalten " +
                    "wahrscheinlich sofort die eigene adb-Verbindung.",
                confirmTitle = "Entwickleroptionen/USB-Debugging wirklich sperren?",
                confirmText = "Kappt vermutlich sofort jede bestehende adb-Verbindung zu diesem " +
                    "Gerät und lässt sich laut Android-Dokumentation nicht mehr über die " +
                    "Entwickleroptionen selbst zurücknehmen — nur noch über diesen Schalter in " +
                    "Warden. Nur auf einem Gerät aktivieren, das nicht mehr per USB entwickelt wird.",
            )
            HorizontalDivider()
            SafeguardEntryRow(
                label = "Schnellzugriff auf dem Sperrbildschirm sperren",
                state = lockScreenPrivacy,
                supportingText = "Keine Shortcuts, Kamera, Widgets oder Klartext-Benachrichtigungen.",
            )
            Text(
                text = "Bootloader/OEM-Unlock sitzt in den Entwickleroptionen. Die öffentliche " +
                    "Device-Owner-API kann das nicht einzeln sperren, ohne USB-Debug mit " +
                    "abzuschalten — das macht erst der Lockdown-Modus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Sensoren.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("Kamera sperren", cameraLocked)
            HorizontalDivider()
            SafeguardEntryRow("Bildschirmaufnahme sperren", screenCaptureLocked)
            HorizontalDivider()
            SafeguardEntryRow("Mikrofon sperren", microphoneMuted)

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Anti-Tamper — schützt Warden selbst vor Deaktivierung.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("Uhrzeit-Manipulation verhindern", clockIntegrity)
            HorizontalDivider()
            SafeguardEntryRow("Deinstallation von Warden blockieren", selfUninstallProtection)
            HorizontalDivider()
            SafeguardEntryRow("Erzwinge-Stopp/Akku-Optimierung blockieren", forceStopProtection)
            HorizontalDivider()
            SafeguardEntryRow("Zertifikat-/Anmeldedaten-Installation blockieren", credentialConfigLockdown)
            HorizontalDivider()
            SafeguardEntryRow("Externe Datenträger (SD/USB) sperren", physicalMediaMountLockdown)
            HorizontalDivider()
            SafeguardEntryRow("Installation aus unbekannten Quellen blockieren", installUnknownSourcesDisabled)
            HorizontalDivider()
            SafeguardEntryRow(
                label = "Zusätzliche Nutzer/Gastprofil verbieten",
                state = addUserDisabled,
                supportingText = "Ein neu angelegtes Profil startet ungehärtet — die meisten " +
                    "Schalter hier wirken nutzerbezogen, das Gerät ist aber dasselbe.",
            )

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Anti-Diebstahl — lokal, kein Standort/Fernzugriff.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("Nur PIN/Passwort (Smart Lock/Biometrie sperren)", keyguardHardening)
            HorizontalDivider()
            SafeguardEntryRow("USB-Datenverkehr bei Sperre automatisch deaktivieren", usbAutoLock)
            HorizontalDivider()
            SafeguardEntryRow("USB-Datenverkehr dauerhaft deaktivieren", usbPermanentlyDisabled)
            HorizontalDivider()
            SafeguardEntryRow(
                label = "USB-Dateiübertragung (MTP/PTP) sperren",
                state = usbFileTransferDisabled,
                supportingText = "Schwächste der drei USB-Stufen: Laden und Zubehör bleiben " +
                    "nutzbar, nur der Dateizugriff fällt weg.",
            )
            HorizontalDivider()
            // Live-Drill-Fund (2026-08-26/2026-08-27): registriert im Katalog seit Sentinels
            // Silent-Install, hatte aber nie eine eigene UI-Zeile — s.
            // SentinelUninstallProtectionSafeguard-Klassendoc, das genau diese Sichtbarkeit
            // versprach.
            SafeguardEntryRow("Sentinel-Deinstallation sperren", sentinelUninstallProtection)

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "App-Kontrolle — sperrt Dritt-Dienste, die als Keylogger/Screen-Scraper " +
                    "missbraucht werden könnten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("Nur System-Bedienungshilfen erlauben", accessibilityLockdown)
            HorizontalDivider()
            SafeguardEntryRow("Nur System-Tastatur erlauben", inputMethodLockdown)

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Forensik/Audit — invasiver als die übrigen Schalter, deshalb bewusst " +
                    "separat und standardmäßig aus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("System-Sicherheitslog", securityLogging)
            HorizontalDivider()
            SafeguardEntryRow("Netzwerk-Metadaten-Log", networkLogging)

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Passwort/Backup — eigene Ergänzung (2026-08-22): starker Sperrbildschirm-" +
                    "PIN nützt wenig ohne kurze Auto-Sperrzeit und ohne Schutz vor Datenabfluss " +
                    "über Cloud-/adb-Backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("Starke Sperrbildschirm-PIN erzwingen", passwordComplexity)
            HorizontalDivider()
            SafeguardEntryRow("Kurze Auto-Sperrzeit erzwingen (30s)", autoLockTimeout)
            HorizontalDivider()
            SafeguardEntryRow("Backup-Dienst sperren", backupServiceLockdown)

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Weitere Härtung — eigene Ergänzung, dritte Runde (2026-08-22).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow("Sicherheitsupdates automatisch installieren", systemUpdatePolicy)

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Funk & Netz — Angriffsfläche bei physischer Nähe (2026-08-28). Spürbare " +
                    "Einschränkungen im Alltag, deshalb erst im Maximal-Profil enthalten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            SafeguardEntryRow(
                label = "2G/GSM-Rückfall verbieten",
                state = cellular2gDisabled,
                supportingText = "2G authentisiert das Netz nicht — der Standardweg für " +
                    "IMSI-Catcher ist der erzwungene Downgrade. ⚠ In reinen 2G-Funklöchern " +
                    "fallen damit auch normale Anrufe/SMS aus; Notrufe bleiben laut Android " +
                    "ausgenommen.",
            )
            HorizontalDivider()
            SafeguardEntryRow(
                label = "NFC-Radio abschalten",
                state = nfcRadioDisabled,
                supportingText = "Gegen Relay-/Skimming-Angriffe aus nächster Nähe. ⚠ Damit " +
                    "endet auch kontaktloses Bezahlen und Transponder-Nutzung.",
            )
            HorizontalDivider()
            SafeguardEntryRow(
                label = "VPN-Einrichtung sperren",
                state = configVpnDisabled,
                supportingText = "Verhindert, dass jemand mit kurzem Zugriff den gesamten " +
                    "Verkehr über einen fremden Endpunkt umleitet. ⚠ Auch du kannst dann kein " +
                    "eigenes VPN mehr anlegen oder ändern; bestehende Verbindungen laufen weiter.",
            )
            HorizontalDivider()
            SafeguardEntryRow(
                label = "Bluetooth-Dateifreigabe sperren",
                state = bluetoothSharingDisabled,
                supportingText = "Nur der Dateikanal (OPP) — Kopfhörer, Uhr und " +
                    "Freisprecheinrichtung funktionieren weiter.",
            )

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "Lockdown-Modus — presence-gated, kein einfacher Schalter (2026-08-22).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            LockdownStatusRow(lockdownModeActive)
            HorizontalDivider()
            Text(
                text = "App-Lock (LockMode) — echter Kiosk-Modus über die separate Sentinel-App.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            LockTaskSection(
                sentinelLockTaskAuthorized = sentinelLockTaskAuthorized,
                sentinelInstallStatus = sentinelInstallStatus,
                onInstallSentinel = onInstallSentinel,
                onRefreshSentinelInstallStatus = onRefreshSentinelInstallStatus,
                emergencyDrillConfirmed = emergencyDrillConfirmed,
                emergencyDrillConfirmedAtText = emergencyDrillConfirmedAtText,
                onConfirmEmergencyDrill = onConfirmEmergencyDrill,
                onRevokeEmergencyDrill = onRevokeEmergencyDrill,
                autoEngageOnCriticalThreat = autoEngageOnCriticalThreat,
                onAutoEngageOnCriticalThreatChange = onAutoEngageOnCriticalThreatChange,
                lockdownTriggerProfile = lockdownTriggerProfile,
                onLockdownTriggerProfileChange = onLockdownTriggerProfileChange,
            )
        }
    }
}

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25), seit "Sentinel: eigenständige Kiosk-PIN-App"
 * überarbeitet. Drei Teile: der aktuelle Sentinel-Autorisierungsstatus (nur informativ — Warden
 * kann den Kiosk-Zustand selbst nicht mehr beenden, s. u.), die einmalige Notruf-Drill-Bestätigung
 * (Voraussetzung für jedes echte Scharfschalten, manuell wie automatisch, s.
 * `de.ble1st.warden.pin.WardenLockTaskDrillStorage`-Klassendoc) und der eigene, separate
 * Auto-Engage-Opt-in für kritische Bedrohungsfunde
 * (`de.ble1st.warden.pin.WardenLockTaskAutoEngageStore`) — nur bei bestätigtem Drill überhaupt
 * anwählbar, sonst wäre der Schalter wirkungslos (das Gate verweigert trotzdem).
 */
@Composable
private fun LockTaskSection(
    sentinelLockTaskAuthorized: Boolean?,
    sentinelInstallStatus: SentinelInstallStatus,
    onInstallSentinel: () -> Unit,
    onRefreshSentinelInstallStatus: () -> Unit,
    emergencyDrillConfirmed: Boolean,
    emergencyDrillConfirmedAtText: String?,
    onConfirmEmergencyDrill: () -> Unit,
    onRevokeEmergencyDrill: () -> Unit,
    autoEngageOnCriticalThreat: Boolean,
    onAutoEngageOnCriticalThreatChange: (Boolean) -> Unit,
    lockdownTriggerProfile: LockdownTriggerProfile,
    onLockdownTriggerProfileChange: (LockdownTriggerProfile) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = "Sentinel (Kiosk-App)")
            Text(
                text = when (sentinelInstallStatus) {
                    SentinelInstallStatus.NotInstalled ->
                        "Nicht installiert — ohne Sentinel bleibt App-Lock wirkungslos " +
                            "(SentinelLockdownEngager meldet \"nicht installiert\")."
                    is SentinelInstallStatus.Installed ->
                        "Installiert, Version ${sentinelInstallStatus.versionName ?: "?"} " +
                            "(${sentinelInstallStatus.versionCode})."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRefreshSentinelInstallStatus) { Text("Status prüfen") }
        TextButton(onClick = onInstallSentinel) {
            Text(if (sentinelInstallStatus is SentinelInstallStatus.Installed) "Update installieren" else "Installieren")
        }
    }
    HorizontalDivider()

    if (sentinelLockTaskAuthorized == true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = "App-Lock AKTIV (Sentinel autorisiert)", color = MaterialTheme.colorScheme.error)
                Text(
                    text = "Notruf/Keyguard bleiben erreichbar — sonst nur Sentinels PIN-Bildschirm. " +
                        "Ausstieg ausschließlich über Sentinels eigene PIN auf dem Gerät selbst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }

    var confirmDrill by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = "Notruf-Drill bestätigt")
            Text(
                text = if (emergencyDrillConfirmed) {
                    "Seit $emergencyDrillConfirmedAtText — Voraussetzung für jedes echte App-Lock " +
                        "(manuell oder automatisch)."
                } else {
                    "Erst nach einem echten, manuell durchgeführten Test bestätigen: Notruf " +
                        "(112/911) während App-Lock aktiv wählen und Erreichbarkeit prüfen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (emergencyDrillConfirmed) {
            TextButton(onClick = onRevokeEmergencyDrill) { Text("Zurücksetzen") }
        } else {
            TextButton(onClick = { confirmDrill = true }) { Text("Bestätigen") }
        }
    }
    if (confirmDrill) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDrill = false },
            title = { Text("Notruf-Drill wirklich bestätigen?") },
            text = {
                Column {
                    Text(
                        "Nur bestätigen, wenn der Notruf-Test auf DIESEM Gerät tatsächlich " +
                            "durchgeführt wurde. Ohne echten Test riskiert ein späteres App-Lock, " +
                            "dass ein Notruf nicht mehr funktioniert.",
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Tippe $EMERGENCY_DRILL_CONFIRMATION_PHRASE") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed == EMERGENCY_DRILL_CONFIRMATION_PHRASE,
                    onClick = {
                        onConfirmEmergencyDrill()
                        confirmDrill = false
                    },
                ) { Text("Bestätigen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDrill = false }) { Text("Abbrechen") }
            },
        )
    }
    HorizontalDivider()
    SafeguardToggleRow(
        label = "App-Lock automatisch bei kritischem Fund",
        supportingText = "Nur wirksam nach bestätigtem Notruf-Drill und scharf geschaltetem " +
            "Lockdown-Modus (Sensible Aktion). Alarmiert per Benachrichtigung, aktiviert das " +
            "App-Lock beim nächsten Öffnen von Warden.",
        checked = autoEngageOnCriticalThreat,
        onCheckedChange = onAutoEngageOnCriticalThreatChange,
        toggleEnabled = emergencyDrillConfirmed,
    )
    HorizontalDivider()
    LockdownTriggerProfilePicker(
        current = lockdownTriggerProfile,
        enabled = emergencyDrillConfirmed,
        onChange = onLockdownTriggerProfileChange,
    )
}

private const val EMERGENCY_DRILL_CONFIRMATION_PHRASE = "NOTRUF GEPRÜFT"

/**
 * "Lockdown-Auslöse-Profil" (2026-08-27) — steuert Dashboard-Button "Kiosk jetzt"
 * (`de.ble1st.warden.ui.WardenStatusActivity`) und die Quick-Settings-Kachel
 * (`de.ble1st.warden.sentinelbridge.SentinelQuickTile`). Dieselbe Gating-Logik wie der
 * Auto-Engage-Schalter direkt darüber: erst nach bestätigtem Notruf-Drill wählbar, sonst wäre ein
 * schnellerer Auslöser wirkungslos (das Gate verweigert trotzdem).
 */
@Composable
private fun LockdownTriggerProfilePicker(
    current: LockdownTriggerProfile,
    enabled: Boolean,
    onChange: (LockdownTriggerProfile) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Lockdown-Auslöse-Profil", style = MaterialTheme.typography.titleSmall)
        Text(
            "Steuert Dashboard-Button \"Kiosk jetzt\" und die Quick-Settings-Kachel. Streng: kein " +
                "Schnellauslöser, volle Presence-Prüfung + Kühlzeit in \"Sensible Aktion\". Standard: " +
                "Schnellauslöser mit Ja/Nein-Bestätigung. Schnell: sofort, ohne Rückfrage." +
                if (!enabled) " Erst nach bestätigtem Notruf-Drill wirksam." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (profile in LockdownTriggerProfile.entries) {
                TextButton(enabled = enabled, onClick = { onChange(profile) }) {
                    Text(profile.label, fontWeight = if (profile == current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun ProfilePicker(onApplyProfile: (WardenProfile) -> Unit) {
    var pending by remember { mutableStateOf<WardenProfile?>(null) }
    Text(
        text = "Profil",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = "Alltag / Reise / Maximal in einem Schritt. Lockdown und Werksreset-Ausführung bleiben unberührt.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (profile in WardenProfile.entries) {
            TextButton(onClick = { pending = profile }) {
                Text(profile.label)
            }
        }
    }
    val profile = pending
    if (profile != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("${profile.label} anwenden?") },
            text = {
                Text(
                    WardenProfileSpec.description(profile) +
                        " Andere reversible Schalter gehen aus.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyProfile(profile)
                        pending = null
                    },
                ) { Text("Anwenden") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun LockdownStatusRow(active: Boolean?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .semantics {
                stateDescription = when (active) {
                    true -> "aktiv"
                    false -> "inaktiv"
                    null -> "unbekannt"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = "Lockdown-Modus")
            Text(
                text = "Scharf-/Zurückschalten unter \"Sensible Aktion\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when (active) {
                true -> "AKTIV"
                false -> "inaktiv"
                null -> "unbekannt"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (active == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (active == null) {
        ErrorStateRow(
            headline = "Status konnte nicht gelesen werden",
            detail = "Vermutlich kein Device Owner aktiv.",
        )
    }
}

@Composable
private fun FactoryResetProtectionAccountsField(
    initialValue: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    val changed = draft.trim() != initialValue.trim()
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = "Entsperrkonto nach Wipe", style = MaterialTheme.typography.labelLarge)
        Text(
            text = "Google-Konto-E-Mail, mit der du das Gerät nach einem Recovery-Wipe " +
                "wieder einrichten darfst. Eine Adresse pro Zeile. Ohne gespeichertes Konto " +
                "wird die Kontosperre nicht gesetzt (kein Brick). ⚠ Es muss ein echtes " +
                "Google-Konto sein — Samsung- oder andere Herstellerkonten werden vom " +
                "FRP-Agenten nicht akzeptiert. Halte Passwort UND zweiten Faktor außerhalb " +
                "dieses Geräts bereit: die Ersteinrichtung nach dem Wipe verlangt genau dieses " +
                "Konto, und wenn der zweite Faktor nur auf diesem Gerät liegt, sperrst du dich " +
                "selbst aus. Das Konto muss zum Zeitpunkt des Wipes noch existieren — ein " +
                "gelöschtes Google-Konto macht das Gerät nicht wieder frei, sondern " +
                "unbrauchbar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Konto-E-Mail") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { onSave(draft) }, enabled = changed) {
                Text("Speichern")
            }
            TextButton(
                onClick = {
                    draft = ""
                    onSave("")
                },
                enabled = draft.isNotEmpty() || initialValue.isNotEmpty(),
            ) {
                Text("Löschen")
            }
        }
    }
}

@Composable
private fun SafeguardEntryRow(
    label: String,
    state: SafeguardToggleState,
    supportingText: String? = null,
    toggleEnabled: Boolean? = null,
) {
    SafeguardToggleRow(
        label = label,
        supportingText = supportingText,
        checked = state.locked == true,
        onCheckedChange = state.onToggle,
        toggleEnabled = toggleEnabled ?: (state.locked != null),
    )
    if (state.locked == null) {
        ErrorStateRow(
            headline = "Status konnte nicht gelesen werden",
            detail = "Vermutlich kein Device Owner aktiv.",
        )
    }
}

/**
 * [SafeguardEntryRow] variant for the reset-path-hardening switches (Werksreset-Sperre, FRP,
 * Konten-Sperre): deaktivieren geht immer noch mit einem Tap, aber erst nach Bestätigungsdialog.
 * Kein Presence-Gate (kein PIN/Biometrie, kein neuer [de.ble1st.warden.domain.bus.BusCommand]) —
 * nur ein Tippfehler-sicherer Zwischenschritt, damit diese drei Schalter nicht so leicht
 * "aus Versehen mit demselben Wisch wie alle anderen" landen wie jeder andere reversible
 * Safeguard. Aktivieren bleibt ungegatet, das ist der risikofreie Fall.
 */
@Composable
private fun ResetProtectionEntryRow(
    label: String,
    state: SafeguardToggleState,
    supportingText: String? = null,
    toggleEnabled: Boolean? = null,
) {
    var confirmDisable by remember { mutableStateOf(false) }
    SafeguardToggleRow(
        label = label,
        supportingText = supportingText,
        checked = state.locked == true,
        onCheckedChange = { requested ->
            if (!requested && state.locked == true) {
                confirmDisable = true
            } else {
                state.onToggle(requested)
            }
        },
        toggleEnabled = toggleEnabled ?: (state.locked != null),
    )
    if (state.locked == null) {
        ErrorStateRow(
            headline = "Status konnte nicht gelesen werden",
            detail = "Vermutlich kein Device Owner aktiv.",
        )
    }
    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text("\"$label\" deaktivieren?") },
            text = {
                Text(
                    "Dieser Schalter gehört zum Reset-Schutz. Ohne ihn lässt sich das Gerät nach " +
                        "einem Wipe leichter wieder in Betrieb nehmen.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.onToggle(false)
                        confirmDisable = false
                    },
                ) { Text("Deaktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) { Text("Abbrechen") }
            },
        )
    }
}

/**
 * [SafeguardEntryRow] variant for switches whose risk sits on **enabling**, not disabling
 * (mirror image of [ResetProtectionEntryRow]) — currently only
 * `UserRestrictionSafeguard.debuggingFeaturesDisabled`, s. its class doc for the adb-cutoff
 * reasoning. Disabling (the risk-free direction, and the only way back once adb itself is gone)
 * stays a plain tap.
 */
@Composable
private fun ConfirmBeforeEnableEntryRow(
    label: String,
    state: SafeguardToggleState,
    supportingText: String? = null,
    confirmTitle: String,
    confirmText: String,
) {
    var confirmEnable by remember { mutableStateOf(false) }
    SafeguardToggleRow(
        label = label,
        supportingText = supportingText,
        checked = state.locked == true,
        onCheckedChange = { requested ->
            if (requested && state.locked == false) {
                confirmEnable = true
            } else {
                state.onToggle(requested)
            }
        },
        toggleEnabled = state.locked != null,
    )
    if (state.locked == null) {
        ErrorStateRow(
            headline = "Status konnte nicht gelesen werden",
            detail = "Vermutlich kein Device Owner aktiv.",
        )
    }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.onToggle(true)
                        confirmEnable = false
                    },
                ) { Text("Sperren") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun SafeguardToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleEnabled: Boolean = true,
    supportingText: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .semantics { stateDescription = if (checked) "gesperrt" else "entsperrt" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = label, modifier = Modifier.semantics { contentDescription = label })
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = toggleEnabled,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(it)
            },
        )
    }
}
