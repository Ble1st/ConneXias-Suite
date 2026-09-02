package de.ble1st.warden.appmanagement

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.presence.WardenLockActivity
import de.ble1st.warden.presence.WardenLockSession
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs

/**
 * Befund S-5 (2026-08-28, aus der Code-/Sicherheitsanalyse): "Deinstallieren" und "Daten
 * löschen" waren die beiden einzigen Aktionen in der ganzen App, die eine Device-Owner-Wirkung
 * mit einem einzigen Tap auf ein *entsperrtes* Gerät auslösten — direkt aus der
 * Benachrichtigungsschublade auf [SuspiciousAppActionReceiver], an
 * [de.ble1st.warden.presence.WardenLockActivity] komplett vorbei. Der Sperrbildschirm selbst ist
 * sauber gelöst (Kanal `VISIBILITY_SECRET`, redigierte `publicVersion` ohne Aktionsknöpfe); bei
 * entsperrtem Gerät fehlte aber jeder Nachweis, dass es tatsächlich die Besitzerin ist, die
 * gerade tippt. `clearApplicationUserData()` ist nicht rückholbar, eine Deinstallation nur über
 * eine erneute Installation.
 *
 * **"Einfrieren" seit analyse.md (2026-09-02, Hoch) ebenfalls hier statt als direkter Broadcast**
 * ([SuspiciousAppActionReceiver] hat `ACTION_FREEZE` seither nicht mehr) — die alte Begründung
 * ("reversibel, dieselbe Einstufung wie ein gewöhnlicher Safeguard-Schalter") übersah, dass ein
 * gewöhnlicher Safeguard-Schalter seit [de.ble1st.warden.presence.WardenLockActivity]
 * (Finalisierungsphase 2026-08-24) selbst nur noch über `WardenStatusActivity` erreichbar ist,
 * also längst hinter demselben Nachweis steht. Ein 1-Tap-Freeze direkt aus der
 * Benachrichtigungsschublade auf ein entsperrtes, aber ansonsten fremdes Gerät war die einzige
 * verbliebene Ausnahme davon — reversibel macht das Fehlen des Nachweises nicht harmlos, nur
 * rückgängig machbar, nachdem der Schaden (App-Störung) schon eingetreten ist.
 *
 * Diese Activity ersetzt für alle drei Aktionen den direkten Receiver-Aufruf: die
 * Benachrichtigung führt jetzt per `PendingIntent.getActivity()` hierher, mit Paketname und
 * Aktion als Extra, und diese Activity zeigt einen expliziten Bestätigungsschritt — erst danach
 * ruft sie [SuspiciousAppScanController.handleFreezeAction]/`handleUninstallAction`/
 * `handleClearDataAction` auf.
 *
 * **Kein zweiter Presence-Mechanismus**, sondern derselbe App-Eintritts-Nachweis wie überall
 * sonst: anders als [de.ble1st.warden.presence.SensitiveActionActivity]/[de.ble1st.warden
 * .presence.LogViewerActivity] (die über `finishIfWardenLockSessionMissing` einfach beenden, weil
 * unter ihnen im Task-Stack ohnehin `WardenStatusActivity` liegt und den Nachweis nachholt) wird
 * diese Activity frisch aus der Benachrichtigungsschublade gestartet und hat oft **keinen**
 * Warden-Task im Hintergrund. Sie übernimmt deshalb exakt [de.ble1st.warden.ui
 * .WardenStatusActivity]s eigenes `onResume()`-Muster (bis hin zum identisch benannten
 * `authenticated`/`lockRequestInFlight`-Zustand): ohne gültige [WardenLockSession] öffnet sie
 * [WardenLockActivity] per `startActivityForResult`, erst nach `RESULT_OK` erscheint der
 * Bestätigungsdialog. Ein abgebrochener Nachweis beendet die Activity ohne jede Wirkung — kein
 * Fallback, keine stille Ausführung.
 *
 * [SuspiciousAppScanController.handleUninstallAction]/`handleClearDataAction` behalten ihre
 * eigene [de.ble1st.warden.domain.appmanagement.SuspiciousAppNotificationActionDecision]-Prüfung
 * (Gerät entsperrt, Fund noch offen) — diese Activity kommt zusätzlich obendrauf, nicht anstelle
 * davon: eine bestätigte, aber inzwischen zurückgezogene Warnung darf trotzdem nichts mehr
 * auslösen.
 */
class SuspiciousAppActionConfirmActivity : ComponentActivity() {

    private val wardenLockSession by lazy { (application as WardenApplication).wardenLockSession }
    private val authenticated = mutableStateOf(false)
    private var lockRequestInFlight = false

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockRequestInFlight = false
        if (result.resultCode == RESULT_OK) {
            authenticated.value = true
        } else {
            // Zurück-Geste/abgebrochener Prompt — ohne Nachweis gibt es nichts sinnvoll
            // anzuzeigen, dieselbe Haltung wie WardenStatusActivity.lockLauncher.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val action = intent.getStringExtra(EXTRA_ACTION)
        val packageName = intent.getStringExtra(SuspiciousAppActionReceiver.EXTRA_PACKAGE_NAME)
        if (action == null || packageName == null) {
            finish()
            return
        }
        val label = runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
        val accent = WardenThemePrefs.load(applicationContext)

        setContent {
            WardenTheme(accent = accent) {
                if (authenticated.value) {
                    ConfirmScreen(
                        action = action,
                        packageName = packageName,
                        label = label,
                        onConfirm = {
                            val controller = (application as WardenApplication).suspiciousAppScanController
                            when (action) {
                                ACTION_FREEZE -> controller.handleFreezeAction(packageName)
                                ACTION_UNINSTALL -> controller.handleUninstallAction(packageName)
                                ACTION_CLEAR_DATA -> controller.handleClearDataAction(packageName)
                            }
                            finish()
                        },
                        onCancel = { finish() },
                    )
                } else {
                    // Wird sofort durch onResume() unten abgelöst; leerer Rahmen, damit kein halb
                    // aufgebauter Bestätigungsdialog aufblitzt, während WardenLockActivity öffnet.
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (wardenLockSession.isAuthenticated()) {
            authenticated.value = true
            return
        }
        authenticated.value = false
        if (!lockRequestInFlight) {
            lockRequestInFlight = true
            lockLauncher.launch(Intent(this, WardenLockActivity::class.java))
        }
    }

    companion object {
        const val EXTRA_ACTION = "de.ble1st.warden.extra.SUSPICIOUS_APP_CONFIRM_ACTION"
        const val ACTION_FREEZE = "freeze"
        const val ACTION_UNINSTALL = "uninstall"
        const val ACTION_CLEAR_DATA = "clear_data"
    }
}

@Composable
private fun ConfirmScreen(
    action: String,
    packageName: String,
    label: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, warning) = when (action) {
        SuspiciousAppActionConfirmActivity.ACTION_FREEZE ->
            stringResource(R.string.suspicious_app_confirm_freeze_title) to
                String.format(stringResource(R.string.suspicious_app_confirm_freeze_warning), label, packageName)
        SuspiciousAppActionConfirmActivity.ACTION_UNINSTALL ->
            stringResource(R.string.suspicious_app_confirm_uninstall_title) to
                String.format(stringResource(R.string.suspicious_app_confirm_uninstall_warning), label, packageName)
        SuspiciousAppActionConfirmActivity.ACTION_CLEAR_DATA ->
            stringResource(R.string.suspicious_app_confirm_clear_data_title) to
                String.format(stringResource(R.string.suspicious_app_confirm_clear_data_warning), label, packageName)
        else -> stringResource(R.string.suspicious_app_confirm_generic_title) to packageName
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
            }
        }
    }
}
