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
 *
 * **Verifikationsweg per Logcat-Empfängerzahl, nicht per `am`-Textausgabe (2026-08-30, echter
 * Live-Fund):** die ursprüngliche Fassung prüfte `am broadcast`s eigene Konsolenausgabe auf
 * "Permission Denial"/"SecurityException". Auf einem realen Samsung-Testgerät (Android-Version mit
 * neuerer `am`-Implementierung) schlug das fehl — `am broadcast` gibt für einen an einer
 * `signature`-Permission abgewiesenen *expliziten* Broadcast nur noch "Broadcasting: Intent {…}" /
 * "Broadcast completed: result=0" aus, ganz ohne Hinweistext, unabhängig davon ob die Zustellung
 * wirklich stattfand. Der eigentliche Beweis lag im selben Moment bereits im System-Logcat:
 * `ActivityManager: Enqueued broadcast Intent {…}: 0` — die Zahl am Zeilenende ist die Anzahl der
 * tatsächlich zur Zustellung vorgemerkten Empfänger. `0` bedeutet: der Permission-Check hat die
 * Zustellung an [SentinelSignalReceiver] schon *vor* jedem Zustellversuch verhindert — das ist der
 * eigentliche Sicherheitsbeweis, nicht eine bestimmte Textzeile von `am`. Dieser Test liest jetzt
 * genau diese Zahl aus dem Logcat statt `am`s Ausgabetext zu interpretieren.
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

        runShell("logcat -c")
        val broadcastOutput = runShell(
            "am broadcast -n $WARDEN_PACKAGE_NAME/$RECEIVER_CLASS_NAME -a $ACTION_PIN_VERIFIED",
        )

        val enqueuedCount = awaitEnqueuedReceiverCount()
        assertTrue(
            "Kein 'Enqueued broadcast'-Logcat-Eintrag für $ACTION_PIN_VERIFIED gefunden — " +
                "am-Ausgabe war: $broadcastOutput",
            enqueuedCount != null,
        )
        assertEquals(
            "Ein Broadcast von der Shell (kein Warden-Zertifikat) an SentinelSignalReceiver muss " +
                "an null Empfängern ankommen (Zustellung durch den signature-Permission-Check " +
                "verhindert, bevor SentinelSignalReceiver überhaupt aufgerufen wird) — am-Ausgabe " +
                "war: $broadcastOutput",
            0,
            enqueuedCount,
        )
    }

    /**
     * Pollt kurz auf die `ActivityManager`-Logzeile, die die Anzahl der für diesen Broadcast
     * tatsächlich zur Zustellung vorgemerkten Empfänger nennt (`Enqueued broadcast Intent {…}: N`).
     * `logcat -d` liefert nur einen Schnappschuss, und die Zeile kann dem `am`-Aufruf um wenige
     * Millisekunden nachlaufen — daher mehrere kurze Versuche statt einer einzelnen Momentaufnahme.
     */
    private fun awaitEnqueuedReceiverCount(): Int? {
        val pattern = Regex("""Enqueued broadcast Intent \{[^}]*act=${Regex.escape(ACTION_PIN_VERIFIED)}[^}]*\}:\s*(\d+)""")
        repeat(ENQUEUED_LOG_POLL_ATTEMPTS) { attempt ->
            val log = runShell("logcat -d -s ActivityManager:I")
            pattern.find(log)?.let { return it.groupValues[1].toIntOrNull() }
            if (attempt < ENQUEUED_LOG_POLL_ATTEMPTS - 1) Thread.sleep(ENQUEUED_LOG_POLL_DELAY_MS)
        }
        return null
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
        const val ENQUEUED_LOG_POLL_ATTEMPTS = 10
        const val ENQUEUED_LOG_POLL_DELAY_MS = 200L
    }
}
