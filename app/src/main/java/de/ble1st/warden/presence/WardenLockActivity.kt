package de.ble1st.warden.presence

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.domain.pin.WardenPinStateDecision
import de.ble1st.warden.pin.WardenPinStorage
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs

/**
 * "WardenLock" (Finalisierungsphase, 2026-08-24, auf Nutzerwunsch): App-Eintritts-Gate,
 * unabhängig vom Geräte-Entsperrzustand — s. [WardenLockSession]-Klassendoc für die Begründung.
 * [de.ble1st.warden.ui.WardenStatusActivity]`.onResume()` startet diese Activity per
 * `startActivityForResult`, sobald [WardenLockSession.isAuthenticated] `false` ist, und rendert
 * erst nach `RESULT_OK` den echten Dashboard-Inhalt; ein `RESULT_CANCELED` (Zurück-Geste,
 * abgebrochener Prompt) beendet stattdessen die ganze App — es gibt ohne Nachweis nichts sinnvoll
 * anzuzeigen.
 *
 * **Zwei gleichrangige Presence-Wege**, identisches Muster wie [SensitiveActionActivity]/
 * [LogViewerActivity]: Biometrie — hier automatisch beim Öffnen angestoßen, kein Extra-Tap nötig,
 * weil dieser Prompt bei "jedem App-Start/Resume" als WardenLock-Trigger spürbar häufig
 * erscheint — und Wardens lokaler PIN als gleichwertige Alternative für Geräte ohne
 * Class-3-Sensor.
 *
 * **Bootstrap-Ausnahme:** ist noch **gar keine** PIN eingerichtet
 * ([WardenPinStateDecision.LoadResult.NotYetConfigured]), lässt diese Activity sofort mit
 * `RESULT_OK` durch, ohne einen Nachweis zu verlangen — sonst gäbe es nach der
 * Device-Owner-Erstprovisionierung keinen Weg mehr, überhaupt eine erste PIN einzurichten (der
 * dafür nötige "Warden-PIN verwalten"-Menüpunkt liegt hinter genau diesem Gate). Ein bereits
 * einmal gesetzter, aber [WardenPinStateDecision.LoadResult.Corrupted] Zustand fällt **nicht**
 * unter diese Ausnahme — der PIN-Presence-Weg unten (`WardenPinActivity` im
 * `EXTRA_PRESENCE_REQUEST`-Modus) zeigt dafür bereits den vorhandenen Offline-Failsafe-Ausstieg,
 * kein zweiter Mechanismus hier nötig.
 *
 * **Bewusst kein Bestätigungstext wie bei [SensitiveActionActivity]** — WardenLock bestätigt nur
 * "ich bin die Owner-Person", keine konkrete destruktive Aktion, für die ein Tippfehler-Schutz
 * sinnvoll wäre.
 */
class WardenLockActivity : FragmentActivity() {

    private var pendingPinPresenceResult: ((granted: Boolean) -> Unit)? = null

    private val pinPresenceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == RESULT_OK
        val callback = pendingPinPresenceResult
        pendingPinPresenceResult = null
        callback?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val session = (application as WardenApplication).wardenLockSession
        val presenceManager = PresenceManager(this)
        val pinStore = WardenPinStorage.openStore(applicationContext)
        val pinConfigured = WardenPinStateDecision.load(pinStore.exists()) { pinStore.load() } !=
            WardenPinStateDecision.LoadResult.NotYetConfigured
        // Nur gelesen, nicht hier umschaltbar — s. FailsafeActivity-Kommentar.
        val accent = WardenThemePrefs.load(applicationContext)

        fun grant() {
            session.markAuthenticated()
            setResult(RESULT_OK)
            finish()
        }

        setContent {
            WardenTheme(accent = accent) {
                WardenLockScreen(
                    bootstrapping = !pinConfigured,
                    onBootstrap = ::grant,
                    onRequestBiometric = { onUnavailable ->
                        presenceManager.request(
                            title = "Warden entsperren",
                            subtitle = "Präsenznachweis für den App-Zugriff",
                        ) { result ->
                            when (result) {
                                is PresenceManager.Result.Success ->
                                    if (result.proof.consume()) grant() else onUnavailable()
                                PresenceManager.Result.Unavailable -> onUnavailable()
                                PresenceManager.Result.Cancelled -> {}
                            }
                        }
                    },
                    onRequestPin = {
                        pendingPinPresenceResult = { granted -> if (granted) grant() }
                        pinPresenceLauncher.launch(
                            Intent(this, WardenPinActivity::class.java).apply {
                                putExtra(WardenPinActivity.EXTRA_PRESENCE_REQUEST, true)
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WardenLockScreen(
    bootstrapping: Boolean,
    onBootstrap: () -> Unit,
    onRequestBiometric: (onUnavailable: () -> Unit) -> Unit,
    onRequestPin: () -> Unit,
) {
    var biometricUnavailable by remember { mutableStateOf(false) }

    // Einmal pro Bildschirm-Eintritt automatisch versucht — kein Extra-Tap für den häufigsten
    // Fall (Klassendoc). `bootstrapping` als Key genügt: dieser Screen wird pro
    // WardenLockActivity-Instanz nur einmal komponiert.
    LaunchedEffect(bootstrapping) {
        if (bootstrapping) {
            onBootstrap()
        } else {
            onRequestBiometric { biometricUnavailable = true }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "Warden gesperrt", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Präsenznachweis erforderlich, bevor der App-Inhalt angezeigt wird.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            if (biometricUnavailable) {
                Text(
                    text = "⚠ Keine Biometrie eingerichtet — mit Warden-PIN fortfahren.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            TextButton(onClick = { onRequestBiometric { biometricUnavailable = true } }) {
                Text("Mit Biometrie entsperren")
            }
            TextButton(onClick = onRequestPin) {
                Text("Mit Warden-PIN entsperren")
            }
        }
    }
}
