package de.ble1st.warden.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ble1st.warden.domain.sim.SimChangeReaction
import de.ble1st.warden.domain.profile.AutoProfileConfig
import de.ble1st.warden.ui.theme.WardenAccent

/**
 * Untermenü "Einstellungen" — bisher stand der Akzentfarb-Umschalter lose unten auf dem
 * Dashboard (s. vorheriger Chat-Turn "Warden-Theme + HTML-Beispiel"); jetzt eigene Unterseite,
 * über das Zahnrad in der Dashboard-TopAppBar erreichbar statt zwischen den übrigen
 * Sicherheits-Menüpunkten zu stehen — eine reine Darstellungspräferenz gehört nicht optisch
 * gleichrangig neben "Sensible Aktion"/"Offline-Failsafe".
 *
 * Vier Ebenen: "Darstellung" (Akzent, [de.ble1st.warden.ui.theme.WardenThemePrefs]), ein
 * Menüeintrag "Namensvergebung" ins gleichnamige Untermenü ([NamingSettingsScreen] — dortiges
 * Klassendoc für die drei gebündelten Freitextfelder), "Härtung" mit dem Auto-Reboot-Zeitfenster
 * ([AutoRebootField]/[de.ble1st.warden.autoreboot.AutoRebootController], 2026-08-22) und "Info"
 * mit den Open-Source-Lizenzen ([LicensesScreen], 2026-08-24 — Repo-Vorbereitung public). Die drei
 * Namensfelder standen vorher als eigener "Sperrbildschirm"-Abschnitt direkt hier — mit dem
 * Auto-Reboot-Feld als weiterem Abschnitt wurde die flache Liste zu unübersichtlich, daher jetzt
 * ausgelagert (2026-08-22, auf Nutzerwunsch). Navigation zwischen Haupt- und den Unterseiten ist
 * rein lokaler Compose-State ([showNamingSettings], [showLicenses]) statt weiterer Einträge im
 * [de.ble1st.warden.ui.WardenStatusActivity]-weiten `WardenScreen` — keiner der Unterseiten
 * braucht einen eigenen Wieder-Einstiegspunkt vom Dashboard aus, nur von hier.
 *
 * Der Inhalt ist jetzt scrollbar ([androidx.compose.foundation.verticalScroll]) statt einer reinen
 * `Column` ohne Scroll-Modifier — mit drei Abschnitten (künftig ggf. mehr) reicht ein kleiner
 * Bildschirm sonst nicht mehr aus, ohne dass Inhalt unerreichbar unten abgeschnitten wird.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accent: WardenAccent,
    onAccentChange: (WardenAccent) -> Unit,
    lockScreenText: String?,
    onLockScreenTextChange: (String?) -> Unit,
    organizationName: String?,
    onOrganizationNameChange: (String?) -> Unit,
    supportMessage: String?,
    onSupportMessageChange: (String?) -> Unit,
    autoRebootThresholdHours: Int?,
    onAutoRebootThresholdHoursChange: (Int?) -> Unit,
    failedAttemptsRebootThreshold: Int?,
    secureLockScreenConfigured: Boolean,
    onFailedAttemptsRebootThresholdChange: (Int?) -> Unit,
    simChangeReaction: SimChangeReaction?,
    onSimChangeReactionChange: (SimChangeReaction?) -> Unit,
    autoProfileConfig: AutoProfileConfig,
    onAutoProfileConfigChange: (AutoProfileConfig) -> Unit,
    onBack: () -> Unit,
) {
    var showNamingSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    // Vorschlag V-8 (2026-08-29) — echter Navigationsfehler, nicht nur Kosmetik: die beiden
    // Unterseiten sind reiner lokaler Compose-State (s. Klassendoc), und der einzige registrierte
    // BackHandler lag bis hierher eine Ebene höher in `WardenRoot`. Der schaltet unbedingt auf
    // `WardenScreen.Status` — die Zurück-Geste aus "Namensvergebung"/"Lizenzen" sprang also direkt
    // aufs Dashboard und übersprang die Einstellungen-Ebene, aus der man gerade gekommen war. Nur
    // der Zurück-Pfeil in der TopAppBar der Unterseite verhielt sich richtig. BackHandler werden
    // in umgekehrter Registrierungsreihenfolge abgearbeitet, der hier gewinnt also gegen den in
    // `WardenRoot`, solange er aktiv ist.
    BackHandler(enabled = showNamingSettings || showLicenses) {
        showNamingSettings = false
        showLicenses = false
    }

    if (showNamingSettings) {
        NamingSettingsScreen(
            lockScreenText = lockScreenText,
            onLockScreenTextChange = onLockScreenTextChange,
            organizationName = organizationName,
            onOrganizationNameChange = onOrganizationNameChange,
            supportMessage = supportMessage,
            onSupportMessageChange = onSupportMessageChange,
            onBack = { showNamingSettings = false },
        )
        return
    }

    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SectionLabel("Darstellung")
            AccentPickerRow(
                selected = accent,
                onSelect = onAccentChange,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionLabel("Sperrbildschirm")
            MenuRow(
                title = "Namensvergebung",
                subtitle = "Zusatztext, Organisationsname, Support-Hinweis",
                tag = "AA",
                onClick = { showNamingSettings = true },
            )

            SectionLabel("Härtung")
            AutoRebootField(
                selectedHours = autoRebootThresholdHours,
                onSelect = onAutoRebootThresholdHoursChange,
                modifier = Modifier.padding(top = 4.dp),
            )
            FailedAttemptsRebootField(
                selectedThreshold = failedAttemptsRebootThreshold,
                secureLockScreenConfigured = secureLockScreenConfigured,
                onSelect = onFailedAttemptsRebootThresholdChange,
                modifier = Modifier.padding(top = 16.dp),
            )
            SimChangeField(
                selectedReaction = simChangeReaction,
                onSelect = onSimChangeReactionChange,
                modifier = Modifier.padding(top = 16.dp),
            )
            AutoProfileField(
                config = autoProfileConfig,
                onChange = onAutoProfileConfigChange,
                modifier = Modifier.padding(top = 16.dp),
            )

            SectionLabel("Info")
            MenuRow(
                title = "Lizenzen",
                subtitle = "Open-Source-Bestandteile dieser App",
                tag = "LZ",
                onClick = { showLicenses = true },
            )
        }
    }
}
