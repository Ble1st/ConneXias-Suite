package de.ble1st.warden.logging

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
 * Meilenstein C.4 — Abnahme: [LogStorage] baut den Envelope-Stack tatsächlich über
 * [android.content.Context.createDeviceProtectedStorageContext] auf und der Round-Trip
 * funktioniert real — s. `LogStorage`-Klassendoc für die Direct-Boot-Begründung.
 */
@RunWith(AndroidJUnit4::class)
class LogStorageInstrumentedTest {

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        File(deviceProtectedContext.filesDir, "log.envelope").delete()
        File(deviceProtectedContext.filesDir, "log.dek").delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.log") }
    }

    @Test
    fun buildEnvelopeFileRoundTripsThroughDeviceProtectedStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val envelopeFile = LogStorage.buildEnvelopeFile(context)
        val store = HashChainLogStore(envelopeFile)

        store.append(priority = 4, tag = "Test", message = "Direct-Boot-Speicherpfad")

        assertEquals(1, HashChainLogStore(envelopeFile).entries().size)
    }

    @Test
    fun buildEnvelopeFileUsesDeviceProtectedStorageNotNormalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()

        HashChainLogStore(LogStorage.buildEnvelopeFile(context))
            .append(priority = 4, tag = "Test", message = "x")

        // Die Datei muss exakt im Device-Protected-Verzeichnis landen, nicht im normalen
        // App-Speicher — sonst wäre sie bei ACTION_LOCKED_BOOT_COMPLETED nicht erreichbar.
        assertTrue(File(deviceProtectedContext.filesDir, "log.envelope").exists())
    }
}
