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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
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
    /** Der in der Registry hinterlegte **Soll**-Zustand (TestDPC-Übernahme, 2026-09-05).
     * `null` = keiner hinterlegt (oder nicht lesbar), *nicht* "aus" — s.
     * [de.ble1st.warden.bus.ConcordBus.safeguardDesiredStates]. Weicht er vom Ist-Zustand
     * [locked] ab, zeigt die Zeile das ausdrücklich an, statt beides stillschweigend zu einem
     * einzigen Schalterbild zu verschmelzen. */
    val desired: Boolean? = null,
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
    /** Zuletzt angewandtes Profil (manuell oder automatisch); `null` = noch nie eines angewandt.
     * Vorschlag V-1 (2026-08-29), s. [ProfilePicker]-Doc. */
    activeProfile: WardenProfile?,
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
                title = { Text(stringResource(R.string.menu_safeguards_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
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
                text = stringResource(R.string.safeguards_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            ProfilePicker(activeProfile = activeProfile, onApplyProfile = onApplyProfile)
            if (profileApplyWarning != null) {
                Text(
                    text = stringResource(R.string.safeguards_profile_apply_warning, profileApplyWarning),
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
                label = { Text(stringResource(R.string.safeguards_search_label)) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { query = "" }) { Text(stringResource(R.string.safeguards_search_clear)) }
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
                    text = stringResource(R.string.safeguards_search_no_match, query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }

            // Ab hier stehen nur noch Abschnitte, die keine gewöhnlichen Schalter sind — sie
            // bleiben von der Suche unberührt, damit der Kiosk-/Lockdown-Teil immer erreichbar ist.

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Text(
                text = stringResource(R.string.safeguards_lockdown_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            LockdownStatusRow(lockdownModeActive)
            HorizontalDivider()
            Text(
                text = stringResource(R.string.safeguards_applock_intro),
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
            Text(text = stringResource(R.string.safeguards_sentinel_label))
            Text(
                text = when (sentinelInstallStatus) {
                    SentinelInstallStatus.NotInstalled -> stringResource(R.string.safeguards_sentinel_not_installed)
                    is SentinelInstallStatus.Installed -> stringResource(
                        R.string.safeguards_sentinel_installed,
                        sentinelInstallStatus.versionName ?: "?",
                        sentinelInstallStatus.versionCode,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRefreshSentinelInstallStatus) { Text(stringResource(R.string.safeguards_check_status_action)) }
        TextButton(onClick = onInstallSentinel) {
            Text(
                stringResource(
                    if (sentinelInstallStatus is SentinelInstallStatus.Installed) {
                        R.string.safeguards_update_install_action
                    } else {
                        R.string.safeguards_install_action
                    },
                ),
            )
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
                    text = stringResource(R.string.safeguards_sentinel_pin_configured_label),
                    // Nur das eindeutige "nein" wird rot — "unbekannt" ist kein Fehlerzustand,
                    // sondern eine Wissenslücke, s. u.
                    color = if (sentinelPinConfigured == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = stringResource(
                        when (sentinelPinConfigured) {
                            true -> R.string.safeguards_sentinel_pin_yes
                            false -> R.string.safeguards_sentinel_pin_no
                            // Bewusst als eigener dritter Zustand statt als "nein": Warden kann
                            // Sentinels PIN-Blob nicht lesen (eigene UID) und erfährt den Zustand
                            // nur aus Sentinels eigener Meldung. Ein frisch installiertes, nie
                            // geöffnetes Sentinel hat wirklich keine PIN — ein seit Monaten
                            // eingerichtetes, seit dem letzten Warden-Datenlöschen nicht geöffnetes
                            // hat eine.
                            null -> R.string.safeguards_sentinel_pin_unknown
                        },
                    ),
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
                Text(text = stringResource(R.string.safeguards_applock_active_label), color = MaterialTheme.colorScheme.error)
                Text(
                    text = stringResource(R.string.safeguards_applock_active_detail),
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
            Text(text = stringResource(R.string.safeguards_drill_label))
            Text(
                text = if (emergencyDrillConfirmed) {
                    stringResource(R.string.safeguards_drill_confirmed_detail, emergencyDrillConfirmedAtText.orEmpty())
                } else {
                    stringResource(R.string.safeguards_drill_unconfirmed_detail)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (emergencyDrillConfirmed) {
            TextButton(onClick = onRevokeEmergencyDrill) { Text(stringResource(R.string.action_reset)) }
        } else {
            TextButton(onClick = { confirmDrill = true }) { Text(stringResource(R.string.action_confirm)) }
        }
    }
    if (confirmDrill) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDrill = false },
            title = { Text(stringResource(R.string.safeguards_drill_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.safeguards_drill_dialog_body))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.safeguards_drill_dialog_type_phrase, EMERGENCY_DRILL_CONFIRMATION_PHRASE)) },
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
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDrill = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    HorizontalDivider()
    SafeguardToggleRow(
        label = stringResource(R.string.safeguards_auto_engage_label),
        supportingText = stringResource(R.string.safeguards_auto_engage_supporting),
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
        Text(stringResource(R.string.safeguards_trigger_profile_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.safeguards_trigger_profile_description) +
                if (!enabled) stringResource(R.string.safeguards_trigger_profile_requires_drill) else "",
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

/**
 * **Zeigt seit 2026-08-29 (V-1) das aktive Profil an.** Vorher standen hier drei gleichwertige
 * Knöpfe ohne jede Markierung — die Frage "in welchem Profil bin ich gerade?" war aus der UI
 * überhaupt nicht zu beantworten. Das wiegt schwerer, seit `AutoProfileController` das Profil auch
 * *ohne Zutun* umschaltet (Nachtfenster, Eskalation bei kritischem Fund): eine automatische
 * Umschaltung war bis hierher unsichtbar, solange man nicht das Audit-Log öffnete.
 *
 * [activeProfile] ist `AutoProfileStorage.loadLastEffective` — das zuletzt *überhaupt* angewandte
 * Profil, manuell wie automatisch (`ConcordBus.applyProfile` schreibt es, s. dessen Doc). `null`
 * heißt "seit der Installation wurde nie ein Profil angewandt", nicht "Alltag": einzelne Schalter
 * können längst gesetzt sein, ohne dass je ein Profil lief — die Anzeige rät das nicht.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePicker(activeProfile: WardenProfile?, onApplyProfile: (WardenProfile) -> Unit) {
    var pending by remember { mutableStateOf<WardenProfile?>(null) }
    Text(
        text = stringResource(R.string.safeguards_profile_section_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = stringResource(R.string.safeguards_profile_section_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (profile in WardenProfile.entries) {
            // FilterChip statt TextButton: der ausgewählte Zustand ist hier eine echte Information,
            // und `selected` trägt ihn auch an Vorlesehilfen weiter ("ausgewählt") — ein fett
            // gesetzter Knopftext täte das nicht.
            FilterChip(
                selected = profile == activeProfile,
                onClick = { pending = profile },
                label = { Text(profile.label) },
            )
        }
    }
    Text(
        text = if (activeProfile == null) {
            stringResource(R.string.safeguards_profile_active_none)
        } else {
            stringResource(R.string.safeguards_profile_active, activeProfile.label)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
    val profile = pending
    if (profile != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.safeguards_profile_apply_dialog_title, profile.label)) },
            text = {
                Text(WardenProfileSpec.description(profile) + stringResource(R.string.safeguards_profile_apply_dialog_suffix))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyProfile(profile)
                        pending = null
                    },
                ) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LockdownStatusRow(active: Boolean?) {
    // stringResource() braucht einen Composable-Aufrufkontext — innerhalb der .semantics{}-Lambda
    // unten (kein Composable-Scope) nicht verfügbar, deshalb hier vorab aufgelöst.
    val activeDescription = stringResource(R.string.safeguards_state_description_active)
    val inactiveDescription = stringResource(R.string.safeguards_state_description_inactive)
    val unknownDescription = stringResource(R.string.safeguards_state_description_unknown)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .semantics {
                stateDescription = when (active) {
                    true -> activeDescription
                    false -> inactiveDescription
                    null -> unknownDescription
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = stringResource(R.string.safeguards_lockdown_row_label))
            Text(
                text = stringResource(R.string.safeguards_lockdown_row_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when (active) {
                true -> stringResource(R.string.safeguards_lockdown_state_active)
                false -> stringResource(R.string.safeguards_lockdown_state_inactive)
                null -> stringResource(R.string.safeguards_lockdown_state_unknown)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (active == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (active == null) {
        ErrorStateRow(
            headline = stringResource(R.string.error_status_unreadable_headline),
            detail = stringResource(R.string.error_status_no_device_owner_detail),
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
        Text(text = stringResource(R.string.safeguards_frp_accounts_label), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.safeguards_frp_accounts_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.safeguards_frp_accounts_field_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { onSave(draft) }, enabled = changed) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(
                onClick = {
                    draft = ""
                    onSave("")
                },
                enabled = draft.isNotEmpty() || initialValue.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_delete))
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
/**
 * "Soll ≠ Ist" für einen einzelnen Safeguard (TestDPC-Übernahme, 2026-09-05).
 *
 * Beide Richtungen sind echte, verschiedene Befunde: **Soll an, Ist aus** heißt, dass Warden den
 * Schalter will und ihn nicht durchsetzen kann (Gerät unterstützt die Restriction nicht, oder ein
 * zweiter Admin hat gewonnen — s. Systemdiagnose ▸ Richtlinien-Koexistenz). **Soll aus, Ist an**
 * heißt, dass jemand *anderes* die Richtlinie gesetzt hat; Warden würde sie beim nächsten
 * Boot-Abgleich zurücknehmen, sofern die ID nicht in `neverWeaken` steht.
 */
@Composable
private fun SafeguardDivergenceRow(desired: Boolean, actual: Boolean) {
    Text(
        text = stringResource(
            R.string.safeguards_divergence_row,
            stringResource(if (desired) R.string.safeguards_state_on else R.string.safeguards_state_off),
            stringResource(if (actual) R.string.safeguards_state_on else R.string.safeguards_state_off),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun CatalogEntryRow(
    entry: SafeguardUiCatalog.Entry,
    state: SafeguardToggleState,
    factoryResetProtectionAccounts: String,
    factoryResetProtectionAgentAvailable: Boolean,
) {
    val isFrp = entry.id == FactoryResetProtectionSafeguard.ID
    val supportingText = if (isFrp && !factoryResetProtectionAgentAvailable) {
        entry.supportingText.orEmpty() + stringResource(R.string.safeguards_frp_no_agent_suffix)
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
            headline = stringResource(R.string.error_status_unreadable_headline),
            detail = stringResource(R.string.error_status_no_device_owner_detail),
        )
    } else if (state.desired != null && state.desired != state.locked) {
        // Nur bei echter Abweichung — die Zeile ist ein Befund, kein Dauerdetail. Bei
        // übereinstimmenden Zuständen sagt der Schalter selbst schon alles, und eine zusätzliche
        // "Soll = Ist"-Zeile unter jedem der 32 Einträge wäre reines Rauschen.
        SafeguardDivergenceRow(desired = state.desired, actual = state.locked)
    }
    if (pendingConfirm) {
        val enabling = entry.riskSide == SafeguardUiCatalog.RiskSide.ENABLING
        AlertDialog(
            onDismissRequest = { pendingConfirm = false },
            title = { Text(entry.confirmTitle ?: stringResource(R.string.safeguards_disable_confirm_title, entry.label)) },
            text = {
                Text(entry.confirmText ?: stringResource(R.string.safeguards_disable_confirm_body_default))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.onToggle(enabling)
                        pendingConfirm = false
                    },
                ) { Text(stringResource(if (enabling) R.string.safeguards_lock_action else R.string.safeguards_disable_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
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
        stringResource(R.string.safeguards_toggle_no_profile)
    } else {
        stringResource(R.string.status_profile, profiles.sortedBy { it.strength }.joinToString { it.label })
    }
    // stringResource() braucht einen Composable-Aufrufkontext — innerhalb der .semantics{}-Lambda
    // unten (kein Composable-Scope) nicht verfügbar, deshalb hier vorab aufgelöst.
    val lockedDescription = stringResource(R.string.safeguards_toggle_state_locked)
    val unlockedDescription = stringResource(R.string.safeguards_toggle_state_unlocked)
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
                stateDescription = if (checked) lockedDescription else unlockedDescription
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
