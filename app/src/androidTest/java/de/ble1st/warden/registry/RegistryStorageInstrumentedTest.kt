package de.ble1st.warden.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Meilenstein C.4 — Abnahme: [RegistryStorage] baut den Envelope-Stack tatsächlich über
 * [android.content.Context.createDeviceProtectedStorageContext] auf und der Round-Trip
 * funktioniert real (nicht nur "kompiliert") — s. `RegistryStorage`-Klassendoc für die
 * Direct-Boot-Begründung.
 */
@RunWith(AndroidJUnit4::class)
class RegistryStorageInstrumentedTest {

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        File(deviceProtectedContext.filesDir, "registry.envelope").delete()
        File(deviceProtectedContext.filesDir, "registry.dek").delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.registry") }
    }

    @Test
    fun buildEnvelopeFileRoundTripsThroughDeviceProtectedStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val envelopeFile = RegistryStorage.buildEnvelopeFile(context)
        val state = mapOf("camera_disabled" to true)

        SafeguardRegistryStore(envelopeFile).save(state)

        assertEquals(state, SafeguardRegistryStore(envelopeFile).load())
    }

    @Test
    fun buildEnvelopeFileUsesDeviceProtectedStorageNotNormalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()

        SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)).save(mapOf("x" to true))

        // Die Datei muss exakt im Device-Protected-Verzeichnis landen, nicht im normalen
        // App-Speicher — sonst wäre sie bei ACTION_LOCKED_BOOT_COMPLETED nicht erreichbar.
        assertTrue(File(deviceProtectedContext.filesDir, "registry.envelope").exists())
    }
}
