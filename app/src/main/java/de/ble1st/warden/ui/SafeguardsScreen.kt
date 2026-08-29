package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ble1st.warden.appmanagement.SentinelInstallStatus
import de.ble1st.warden.domain.pin.LockdownTriggerProfile
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.profile.WardenProfileSpec
import de.ble1st.warden.registry.FactoryResetProtectionSafeguard
import de.ble1st.warden.registry.UserRestrictionSafeguard

/**
 * Untermenü "Geräteschutz ▸ Safeguards". Profile (Alltag / Reise / Maximal) apply a named set
 * through Concord in one authorized call; individual switches remain for fine-tuning.
 *
 * **Aus [SafeguardUiCatalog] gespeist statt aus 33 Parametern (Vorschlag U-1, 2026-08-29):** die
 * Schalterzeilen selbst kommen jetzt aus einer Datenliste; diese Funktion nimmt dafür genau einen
 * Zugriffspunkt entgegen ([toggleFor]) statt eines Parameters pro Schalter. Die Begründung steht
 * im [SafeguardUiCatalog]-Klassendoc. Was hier an Parametern bleibt, sind ausschließlich die
 * Dinge, die *keine* gewöhnlichen Schalter sind: das FRP-Kontenfeld, der Lockdown-Status und der
 * gesamte LockTask-/Kiosk-Abschnitt.
 */
data class SafeguardToggleState(
    val locked: Boolean?,
    val onToggle: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeguardsScreen(
    /** Liefert den Zustand für eine Katalog-ID. Die Aufrufstelle bildet
     * [SafeguardUiCatalog.USB_AUTO_LOCK_ID] auf die lokale Präferenz ab, alles andere auf die
     * Registry — s. dessen Doc. */
    toggleFor: (String) -> SafeguardToggleState,
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
    /** `null` = Sentinel hat sich noch nie gemeldet, *nicht* "keine PIN" — s.
     * `SentinelPinStateStore` (Vorschlag U-8, 2026-08-29). */
    sentinelPinConfigured: Boolean?,
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

            // Vorschlag U-3 (2026-08-29): 33 Schalter ohne Filter waren nur noch durch Scrollen
            // erreichbar. Die Suche greift auf Titel *und* Beschreibungstext, damit z. B. "IMSI"
            // den 2G-Schalter findet — s. SafeguardUiCatalog.Entry.matches.
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Schalter suchen") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { query = "" }) { Text("Leeren") }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            // Vorschlag U-1 (2026-08-29): eine Schleife über SafeguardUiCatalog statt 33
            // handverdrahteter Zeilen. Ein neuer Safeguard braucht hier keine Änderung mehr.
            var matchCount = 0
            for (group in SafeguardUiCatalog.groups) {
                val visible = group.entries.filter { it.matches(query) }
                if (visible.isEmpty()) continue
                matchCount += visible.size

                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                Text(
                    text = group.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                for ((index, entry) in visible.withIndex()) {
                    if (index > 0) HorizontalDivider()
                    CatalogEntryRow(
                        entry = entry,
                        state = toggleFor(entry.id),
                        factoryResetProtectionAccounts = factoryResetProtectionAccounts,
                        factoryResetProtectionAgentAvailable = factoryResetProtectionAgentAvailable,
                    )
                    // Das FRP-Kontenfeld ist kein Schalter und steht deshalb nicht im Katalog —
                    // seine Position ist trotzdem inhaltlich festgelegt: unmittelbar vor dem
                    // Schalter, der ohne hinterlegtes Konto gar nicht erst aktivierbar ist.
                    // Bei aktiver Suche entfällt es, sonst stünde ein Textfeld ohne Bezug in einer
                    // gefilterten Liste.
                    if (query.isBlank() && entry.id == UserRestrictionSafeguard.SAFE_BOOT_DISABLED_ID) {
                        HorizontalDivider()
                        FactoryResetProtectionAccountsField(
                            initialValue = factoryResetProtectionAccounts,
                            onSave = onSaveFactoryResetProtectionAccounts,
                        )
                    }
                }
                if (query.isBlank() && group.footnote != null) {
                    Text(
                        text = group.footnote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            if (matchCount == 0) {
                Text(
                    text = "Kein Schalter passt zu \"$query\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }

            // Ab hier stehen nur noch Abschnitte, die keine gewöhnlichen Schalter sind — sie
            // bleiben von der Suche unberührt, damit der Kiosk-/Lockdown-Teil immer erreichbar ist.

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
                sentinelPinConfigured = sentinelPinConfigured,
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
    sentinelPinConfigured: Boolean?,
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

    // Vorschlag U-8 (2026-08-29): dritte Vorbedingung neben Installation und Notruf-Drill. Ohne
    // eingerichtete Sentinel-PIN lehnt SentinelLockTaskGate jedes Scharfschalten ab — das war
    // bisher nirgends *vorher* sichtbar, sondern erst als Ablehnungsmeldung im Ernstfall.
    // Nur bei installiertem Sentinel gezeigt: ohne Installation sagt die Zeile darüber bereits
    // alles, und eine zweite Warnung zum selben Sachverhalt liest sich wie ein zweites Problem.
    if (sentinelInstallStatus is SentinelInstallStatus.Installed) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sentinel-PIN eingerichtet",
                    // Nur das eindeutige "nein" wird rot — "unbekannt" ist kein Fehlerzustand,
                    // sondern eine Wissenslücke, s. u.
                    color = if (sentinelPinConfigured == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = when (sentinelPinConfigured) {
                        true -> "Ja — Sentinel hat eine benutzbare PIN gemeldet. Sie ist der " +
                            "einzige Ausstieg aus dem Kiosk."
                        false -> "NEIN — Sentinel würde jedes Scharfschalten ablehnen. Sentinel " +
                            "öffnen und eine PIN einrichten."
                        // Bewusst als eigener dritter Zustand statt als "nein": Warden kann
                        // Sentinels PIN-Blob nicht lesen (eigene UID) und erfährt den Zustand nur
                        // aus Sentinels eigener Meldung. Ein frisch installiertes, nie geöffnetes
                        // Sentinel hat wirklich keine PIN — ein seit Monaten eingerichtetes, seit
                        // dem letzten Warden-Datenlöschen nicht geöffnetes hat eine.
                        null -> "Unbekannt — Sentinel hat sich noch nicht gemeldet. Einmal öffnen " +
                            "genügt; der Zustand wird dabei automatisch übermittelt."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }

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

/**
 * Eine Katalogzeile (Vorschlag U-1, 2026-08-29) — ersetzt die früheren drei fast identischen
 * Varianten `SafeguardEntryRow`/`ResetProtectionEntryRow`/`ConfirmBeforeEnableEntryRow`. Welche
 * davon zutrifft, entscheidet jetzt [SafeguardUiCatalog.Entry.riskSide] statt die Wahl des
 * Aufrufers an der jeweiligen Zeile:
 *
 * - [SafeguardUiCatalog.RiskSide.NONE] — ein Tap, wie bisher.
 * - [SafeguardUiCatalog.RiskSide.DISABLING] — Reset-Schutz: **Ab**schalten erst nach Rückfrage,
 *   Einschalten bleibt ungegatet (der risikofreie Weg).
 * - [SafeguardUiCatalog.RiskSide.ENABLING] — Spiegelbild dazu, derzeit nur die
 *   Entwickleroptionen-Sperre: **Ein**schalten erst nach Rückfrage, weil es die eigene
 *   adb-Verbindung kappt und sich danach nur noch hier zurücknehmen lässt.
 *
 * Kein Presence-Gate in beiden Fällen (kein PIN/Biometrie, kein neuer
 * [de.ble1st.warden.domain.bus.BusCommand]) — nur ein Tippfehler-sicherer Zwischenschritt, damit
 * diese Schalter nicht so beiläufig umgelegt werden wie jeder andere reversible Safeguard.
 *
 * **Die zwei dynamischen Sonderfälle von `factory_reset_protection`** stehen bewusst als
 * sichtbares `if` hier statt als generische Override-Lambda in der Signatur: der Warnzusatz hängt
 * davon ab, ob die Google-Play-Dienste überhaupt gefunden wurden, und aktivierbar ist der Schalter
 * nur mit hinterlegtem Konto. Ein Sonderfall, den man liest, ist besser als eine Abstraktion, die
 * ihn versteckt.
 */
@Composable
private fun CatalogEntryRow(
    entry: SafeguardUiCatalog.Entry,
    state: SafeguardToggleState,
    factoryResetProtectionAccounts: String,
    factoryResetProtectionAgentAvailable: Boolean,
) {
    val isFrp = entry.id == FactoryResetProtectionSafeguard.ID
    val supportingText = if (isFrp && !factoryResetProtectionAgentAvailable) {
        entry.supportingText.orEmpty() +
            " ⚠ Google-Play-Dienste nicht gefunden — Schalter zeigt \"aktiv\", " +
            "wird vom FRP-Agenten aber vermutlich nicht durchgesetzt."
    } else {
        entry.supportingText
    }
    val toggleEnabled = when {
        state.locked == null -> false
        isFrp -> factoryResetProtectionAccounts.isNotBlank() || state.locked == true
        else -> true
    }

    var pendingConfirm by remember { mutableStateOf(false) }
    SafeguardToggleRow(
        label = entry.label,
        supportingText = supportingText,
        profiles = SafeguardUiCatalog.profilesContaining(entry.id),
        checked = state.locked == true,
        onCheckedChange = { requested ->
            val needsConfirm = when (entry.riskSide) {
                SafeguardUiCatalog.RiskSide.NONE -> false
                SafeguardUiCatalog.RiskSide.DISABLING -> !requested && state.locked == true
                SafeguardUiCatalog.RiskSide.ENABLING -> requested && state.locked == false
            }
            if (needsConfirm) pendingConfirm = true else state.onToggle(requested)
        },
        toggleEnabled = toggleEnabled,
    )
    if (state.locked == null) {
        ErrorStateRow(
            headline = "Status konnte nicht gelesen werden",
            detail = "Vermutlich kein Device Owner aktiv.",
        )
    }
    if (pendingConfirm) {
        val enabling = entry.riskSide == SafeguardUiCatalog.RiskSide.ENABLING
        AlertDialog(
            onDismissRequest = { pendingConfirm = false },
            title = { Text(entry.confirmTitle ?: "\"${entry.label}\" deaktivieren?") },
            text = {
                Text(
                    entry.confirmText
                        ?: "Dieser Schalter gehört zum Reset-Schutz. Ohne ihn lässt sich das Gerät " +
                        "nach einem Wipe leichter wieder in Betrieb nehmen.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.onToggle(enabling)
                        pendingConfirm = false
                    },
                ) { Text(if (enabling) "Sperren" else "Deaktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = false }) { Text("Abbrechen") }
            },
        )
    }
}

/**
 * **Ganze Zeile antippbar (Vorschlag U-2, 2026-08-29):** vorher war dies ein schlichtes [Row], bei
 * dem nur der [Switch] selbst traf — bei so vielen dicht gesetzten Zeilen ergonomisch teuer, und
 * für TalkBack zerfiel eine Zeile in mehrere unverbundene Knoten (Titel, Beschreibung, Schalter).
 * `Modifier.toggleable(role = Role.Switch)` auf der Zeile plus `Switch(onCheckedChange = null)`
 * macht daraus einen einzigen, korrekt annoncierten Bedienknoten mit voller Trefferfläche; der
 * Switch bleibt sichtbar, ist aber nur noch Anzeige.
 *
 * [profiles] (Vorschlag U-3) zeigt, zu welchen Profilen der Schalter gehört. Das ist keine
 * Dekoration: seit Befund Q-1 ist genau das der Unterschied zwischen "mein manuell gesetzter
 * Schalter bleibt" und "wird beim nächsten Zeitplanlauf zurückgesetzt" — ein Schalter, der in
 * keinem Profil steht, überlebt keine Profilanwendung.
 */
@Composable
private fun SafeguardToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleEnabled: Boolean = true,
    supportingText: String? = null,
    profiles: Set<WardenProfile> = emptySet(),
) {
    val haptic = LocalHapticFeedback.current
    val profileText = if (profiles.isEmpty()) {
        "In keinem Profil — eine Profilanwendung schaltet ihn wieder aus."
    } else {
        "Profil: " + profiles.sortedBy { it.strength }.joinToString { it.label }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = toggleEnabled,
                role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(it)
                },
            )
            .padding(vertical = 14.dp)
            .semantics {
                stateDescription = if (checked) "gesperrt" else "entsperrt"
                // Ohne dies liest TalkBack Titel, Beschreibung und Profilzeile als drei getrennte
                // Fragmente vor; toggleable oben macht die Zeile ohnehin schon zu *einem* Knoten.
                contentDescription = listOfNotNull(label, supportingText, profileText)
                    .joinToString(". ")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = label)
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = profileText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // onCheckedChange = null: die Zeile selbst ist der Bedienknoten (s. Klassendoc).
        Switch(checked = checked, enabled = toggleEnabled, onCheckedChange = null)
    }
}
