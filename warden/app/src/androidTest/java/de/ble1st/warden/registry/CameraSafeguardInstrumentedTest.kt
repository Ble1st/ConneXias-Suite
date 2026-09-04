package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live-Fund 2026-08-30 (Smoketest, [[warden-smoketest-2026-08-30]] im Projekt-Memory): dieser Test
 * grenzt ein, *wo* eine Kamera-Sperre landen kann, indem er ausschließlich den DPM-Zustandsautomaten
 * (`setCameraDisabled`/`getCameraDisabled`) prüft, ohne die Kamera-Hardware anzufassen — dafür
 * bräuchte Warden die `CAMERA`-Laufzeitberechtigung, die die App bewusst nicht deklariert (kein
 * Feature, das Kamerazugriff braucht). Schlägt dieser Test fehl, liegt der Fehler in Wardens
 * eigenem Code.
 *
 * Die eigentliche Durchsetzung gegenüber Kamera-Zugriffen wurde separat, außerhalb dieses Tests,
 * mit einem unabhängig signierten Wegwerf-Test-APK verifiziert (eigene `CAMERA`-Berechtigung, kein
 * privilegierter Systemstatus): bei aktiver Sperre scheiterte `CameraManager.openCamera()` dort
 * synchron mit `CameraAccessException(CAMERA_DISABLED)`, bei inaktiver Sperre öffnete dieselbe App
 * die Kamera normal. Eine erste Beobachtung desselben Tages (Stock-Kamera-App bleibt trotz aktiver
 * Sperre funktionsfähig, `dumpsys media.camera` zeigt eine aktive HAL-Session) war kein
 * Durchsetzungsfehler, sondern vermutlich eine OEM-Ausnahme speziell für diese privilegierte
 * Systemapp — s. [CameraSafeguard]s Klassendoc für die volle Herleitung. `dumpsys device_policy`
 * zeigt weiterhin nie einen kamerabezogenen Eintrag (weder alter Feld-Dump noch neues
 * `PolicyKey`-Format) — ein bestätigter Diagnose-Blindspot, kein Hinweis auf einen echten
 * Durchsetzungsfehler.
 */
@RunWith(AndroidJUnit4::class)
class CameraSafeguardInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val safeguard = CameraSafeguard(context)

    @Before
    fun assumeDeviceOwner() {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        assumeTrue(
            "Braucht Device-Owner-Status — s. Klassendoc",
            dpm != null && dpm.isDeviceOwnerApp(context.packageName),
        )
    }

    @After
    fun tearDown() {
        safeguard.revert()
    }

    @Test
    fun applySetsCameraDisabledFlagInDpm() {
        safeguard.apply()

        assertTrue(
            "getCameraDisabled() muss nach apply() true liefern — s. Klassendoc: die tatsächliche " +
                "Durchsetzung ist separat per Wegwerf-Test-APK bestätigt, dieser Test prüft nur " +
                "Wardens eigenen DPM-Aufruf",
            safeguard.isActive(),
        )
    }

    @Test
    fun revertClearsCameraDisabledFlagInDpm() {
        safeguard.apply()

        safeguard.revert()

        assertFalse(
            "getCameraDisabled() muss nach revert() wieder false liefern",
            safeguard.isActive(),
        )
    }

    @Test
    fun isActiveIsFalseWithoutEverApplying() {
        // Defensive Vorbedingung, gleiches Muster wie WardenLockTaskAuthorizerInstrumentedTest —
        // falls ein vorheriger Lauf/manueller Test die Flagge stehen gelassen hat, erst
        // revertieren statt einen falsch-grünen Test zu riskieren.
        safeguard.revert()

        assertFalse(safeguard.isActive())
    }
}
