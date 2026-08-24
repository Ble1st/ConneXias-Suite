package de.ble1st.warden.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import de.ble1st.warden.domain.registry.Safeguard
import de.ble1st.warden.domain.registry.SafeguardRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Meilenstein C.3 — Abnahme: `PersistentSafeguardRegistry` verdrahtet `SafeguardRegistry` (C.1)
 * mit `SafeguardRegistryStore` (C.3). Fake-Safeguard statt echtem DPM-Schalter — dieser Test
 * prüft nur die Persistenz-Verdrahtung, nicht die konkreten C.2-Schalter selbst (die schon in
 * `:warden-app` gegen die echte DPM-Grenze getestet sind, s. `CameraSafeguardInstrumentedTest`
 * & Co.). Braucht Keystore, aber keine Device-Owner-Rechte, läuft deshalb hier.
 */
@RunWith(AndroidJUnit4::class)
class PersistentSafeguardRegistryInstrumentedTest {

    private class FakeSafeguard(override val id: String) : Safeguard {
        private var active = false
        override fun apply() { active = true }
        override fun revert() { active = false }
        override fun isActive(): Boolean = active
    }

    private lateinit var testDir: File
    private lateinit var kek: KeystoreKek
    private val purpose = "test-persistent-registry-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "persistent-registry-test-${UUID.randomUUID()}").apply { mkdirs() }
        kek = KeystoreKek.forPurpose(context, purpose)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.$purpose") }
    }

    private fun newEnvelopeFile() = EnvelopeFile(
        dataFile = File(testDir, "registry.envelope"),
        wrappedDekFile = File(testDir, "registry.dek"),
        wrapper = kek,
        context = "warden:registry:v1".toByteArray(),
    )

    private fun newPersistentRegistry(envelopeFile: EnvelopeFile) =
        PersistentSafeguardRegistry(SafeguardRegistry(), SafeguardRegistryStore(envelopeFile))

    @Test
    fun applyPersistsAutomatically() {
        val envelopeFile = newEnvelopeFile()
        val registry = newPersistentRegistry(envelopeFile)
        registry.register(FakeSafeguard(id = "camera_disabled"))
        registry.load()

        registry.apply("camera_disabled")

        assertEquals(mapOf("camera_disabled" to true), SafeguardRegistryStore(envelopeFile).load())
    }

    @Test
    fun revertPersistsAutomatically() {
        val envelopeFile = newEnvelopeFile()
        val registry = newPersistentRegistry(envelopeFile)
        registry.register(FakeSafeguard(id = "camera_disabled"))
        registry.load()
        registry.apply("camera_disabled")

        registry.revert("camera_disabled")

        assertEquals(mapOf("camera_disabled" to false), SafeguardRegistryStore(envelopeFile).load())
    }

    @Test
    fun desiredStateSurvivesASimulatedRestartThroughRealFile() {
        val envelopeFile = newEnvelopeFile()
        val firstRegistry = newPersistentRegistry(envelopeFile)
        firstRegistry.register(FakeSafeguard(id = "camera_disabled"))
        firstRegistry.load()
        firstRegistry.apply("camera_disabled")

        // Simuliert einen Neustart: frische Registry + frischer Safeguard (Ist-Zustand wieder
        // false, "als hätte der Prozess neu gestartet"), aber derselbe persistierte Envelope.
        val secondRegistry = newPersistentRegistry(envelopeFile)
        val restoredSafeguard = FakeSafeguard(id = "camera_disabled")
        secondRegistry.register(restoredSafeguard)

        secondRegistry.load()

        assertFalse(
            "restoreDesiredState() darf den Safeguard selbst nicht aufrufen — das bleibt der " +
                "künftigen Boot-Reconciliation (C.4) vorbehalten",
            restoredSafeguard.isActive(),
        )
        assertEquals(
            "der Soll-Zustand von vor dem simulierten Neustart muss geladen werden",
            true,
            secondRegistry.desiredState("camera_disabled"),
        )
    }

    @Test
    fun partialRegistrySaveMergesInsteadOfReplacing() {
        val envelopeFile = newEnvelopeFile()
        val first = newPersistentRegistry(envelopeFile)
        first.register(FakeSafeguard(id = "camera_disabled"))
        first.register(FakeSafeguard(id = "screen_capture_disabled"))
        first.load()
        first.apply("camera_disabled")
        first.apply("screen_capture_disabled")

        val subset = newPersistentRegistry(envelopeFile)
        subset.register(FakeSafeguard(id = "camera_disabled"))
        subset.load()
        subset.revert("camera_disabled")

        assertEquals(
            mapOf(
                "camera_disabled" to false,
                "screen_capture_disabled" to true,
            ),
            SafeguardRegistryStore(envelopeFile).load(),
        )
    }

    @Test
    fun loadBeforeAnyApplyLeavesDesiredStateNull() {
        val registry = newPersistentRegistry(newEnvelopeFile())
        registry.register(FakeSafeguard(id = "camera_disabled"))

        registry.load()

        assertNull(registry.desiredState("camera_disabled"))
    }
}
