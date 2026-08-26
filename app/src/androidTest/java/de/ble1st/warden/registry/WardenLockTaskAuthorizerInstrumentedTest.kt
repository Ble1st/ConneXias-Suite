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
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Tests" —
 * `apply()`/`revert()`/`isActive()` gegen den echten DPM-Zustand, jetzt mit Sentinels
 * Paketnamen (s. [WardenLockTaskAuthorizer]-Klassendoc "Seit ... whitelistet dies wieder das
 * *fremde* Sentinel-Paket"). Anders als jeder andere DPM-Aufruf im Projekt gibt es hier bewusst
 * einen echten Instrumented-Test statt nur manueller Verifikation (`MANUAL_SMOKE_TEST.md`) —
 * [WardenLockTaskAuthorizer]s eigenes Klassendoc begründet explizit, warum `apply()`/`revert()`
 * risikolos wiederholbar live testbar sind: sie autorisieren nur, versetzen das Gerät selbst nie
 * in den Lock-Task-Modus (das bleibt `Activity.startLockTask()` in Sentinels eigenem Prozess
 * vorbehalten, strukturell weiterhin durch `DestructiveCommandGuard` blockiert).
 *
 * `assumeTrue` auf Device-Owner-Status — dieses Repo hat sonst keine DPM-live-Instrumented-Tests
 * (kein automatisierter DO/DPM-Testharness, s. CLAUDE.md), `connectedAndroidTest` liefe auf einem
 * nicht provisionierten Emulator sonst mit einer `SecurityException` statt eines ehrlichen
 * "übersprungen" ab.
 */
@RunWith(AndroidJUnit4::class)
class WardenLockTaskAuthorizerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authorizer = WardenLockTaskAuthorizer(context)

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
        authorizer.revert()
    }

    @Test
    fun applyWhitelistsSentinelPackageForLockTask() {
        authorizer.apply()

        assertTrue(
            "Sentinels Paket muss nach apply() real in der DPM-Lock-Task-Whitelist stehen",
            authorizer.isActive(),
        )
    }

    @Test
    fun revertRemovesSentinelPackageFromLockTaskWhitelist() {
        authorizer.apply()

        authorizer.revert()

        assertFalse(
            "nach revert() darf Sentinels Paket nicht mehr in der DPM-Lock-Task-Whitelist stehen",
            authorizer.isActive(),
        )
    }

    @Test
    fun isActiveIsFalseWithoutEverApplying() {
        // Defensive Vorbedingung — falls ein vorheriger Testlauf (oder ein manueller Drill) die
        // Whitelist stehen gelassen hat, revertieren statt einen falsch-grünen Test zu riskieren.
        authorizer.revert()

        assertFalse(authorizer.isActive())
    }
}
