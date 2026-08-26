package de.ble1st.warden.sentinelbridge

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Tests" — der
 * sicherheitskritische Kern dieses Designs (`signature`-Permission statt Userspace-Caller-
 * Verifier, s. [SentinelLockdownEngager]-Klassendoc "Warum kein AIDL-Bus") muss empirisch
 * bestätigt werden, nicht nur angenommen. Sendet den Entwarn-Broadcast real über die Shell
 * (`am broadcast`, UID `shell` — nicht mit Wardens Zertifikat signiert) und verifiziert, dass die
 * Zustellung tatsächlich am `de.ble1st.warden.permission.SENTINEL_SIGNAL`-Permission-Check
 * scheitert. Automatisch, nicht per `assumeTrue`-Drill-Gate: mutiert keinen echten Geräte-/DPM-
 * Zustand (reine Permission-Zurückweisung, kein `apply()`/`revert()` auf irgendeinem Safeguard),
 * anders als die scharf-schaltenden Drills in `sentinel/`/`registry/`.
 */
@RunWith(AndroidJUnit4::class)
class SentinelSignalReceiverInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun shellSenderWithoutMatchingSignatureIsRejected() {
        val context = instrumentation.targetContext
        // Strukturelle Vorbedingung: die Shell-UID darf das signature-Permission gar nicht
        // besitzen (kann sie auch nie — nicht mit Wardens Zertifikat signiert). Bestätigt, dass
        // der Test tatsächlich das erwartete Negativ-Szenario prüft, nicht zufällig auf einem
        // Testgerät läuft, auf dem "shell" aus anderen Gründen bereits alles darf.
        assertEquals(
            "Shell-UID darf das signature-Permission strukturell nie besitzen",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(SENTINEL_SIGNAL_PERMISSION, SHELL_PACKAGE_NAME),
        )

        val output = runShell(
            "am broadcast -n $WARDEN_PACKAGE_NAME/$RECEIVER_CLASS_NAME -a $ACTION_PIN_VERIFIED",
        )

        assertTrue(
            "Ein Broadcast von der Shell (kein Warden-Zertifikat) an SentinelSignalReceiver muss " +
                "am signature-Permission-Check scheitern — tatsächliche Ausgabe: $output",
            output.contains("Permission Denial") || output.contains("SecurityException"),
        )
        assertTrue(
            "Die Ablehnung muss auf das erwartete Permission benennen — tatsächliche Ausgabe: $output",
            output.contains(SENTINEL_SIGNAL_PERMISSION),
        )
    }

    private fun runShell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
    }

    private companion object {
        const val WARDEN_PACKAGE_NAME = "de.ble1st.warden"
        const val RECEIVER_CLASS_NAME = "de.ble1st.warden.sentinelbridge.SentinelSignalReceiver"
        const val ACTION_PIN_VERIFIED = "de.ble1st.warden.sentinel.action.PIN_VERIFIED"
        const val SENTINEL_SIGNAL_PERMISSION = "de.ble1st.warden.permission.SENTINEL_SIGNAL"
        const val SHELL_PACKAGE_NAME = "com.android.shell"
    }
}
