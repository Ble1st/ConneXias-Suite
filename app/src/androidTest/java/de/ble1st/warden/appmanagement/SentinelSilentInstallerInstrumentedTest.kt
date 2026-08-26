package de.ble1st.warden.appmanagement

import android.app.admin.DevicePolicyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Tests" — die
 * produktionsrelevante Variante: läuft unter der echten `de.ble1st.warden`-UID (Device Owner),
 * weil `PackageInstaller.commit()` denselben Calling-UID-Privilegien-Check macht wie
 * `DevicePolicyManager` — installiert real die in Wardens eigenem APK gebündelte
 * `assets/sentinel.apk` (kein separater `:sentinel:installDebug`-Schritt nötig, anders als beim
 * ConneXias-Framework-Quellprojekt: hier ist das Bündeln bereits Teil jedes normalen Builds, s.
 * [SentinelSilentInstaller]-Klassendoc).
 *
 * Prüft sowohl das **synchrone** Ergebnis (`SessionCommitted`) als auch — mit kurzem Polling,
 * `PackageInstaller.commit()` läuft asynchron — das **tatsächliche** Installationsergebnis über
 * [PackageManager], nicht nur den Zwischenschritt: ein "Session committed, aber nie wirklich
 * installiert" wäre ein falsch-grüner Test.
 *
 * `assumeTrue` auf Device-Owner-Status — dieselbe Begründung wie
 * [de.ble1st.warden.registry.WardenLockTaskAuthorizerInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class SentinelSilentInstallerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun assumeDeviceOwner() {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        assumeTrue(
            "Braucht Device-Owner-Status — s. Klassendoc",
            dpm != null && dpm.isDeviceOwnerApp(context.packageName),
        )
    }

    @Test
    fun realSilentInstallOfBundledSentinelApkSucceedsWithoutUserConfirmation() {
        val installer = SentinelSilentInstaller(context)

        val outcome = installer.install()

        assertTrue(
            "Installation muss real eine PackageInstaller-Session committen, Ergebnis war: $outcome",
            outcome is SentinelInstallOutcome.SessionCommitted,
        )
        assertTrue(
            "Sentinel muss nach der committeten Session tatsächlich installiert sein " +
                "(per PackageManager verifiziert, kein bloßer Zwischenschritt)",
            waitUntilInstalled(timeoutMillis = 15_000),
        )
    }

    private fun waitUntilInstalled(timeoutMillis: Long): Boolean {
        val reader = SentinelInstallStatusReader(context)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (reader.currentStatus() is SentinelInstallStatus.Installed) return true
            Thread.sleep(200)
        }
        return reader.currentStatus() is SentinelInstallStatus.Installed
    }
}
