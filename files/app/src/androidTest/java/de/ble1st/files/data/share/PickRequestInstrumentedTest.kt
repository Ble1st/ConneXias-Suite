package de.ble1st.files.data.share

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der `ACTION_GET_CONTENT`-Vertrag: was eine fremde App anfragt, muss sie auch bekommen
 * (analyse.md Abschnitt 5 / 6.2 — Files als Datei-Picker samt Mime-Typ-Filter).
 *
 * Instrumentation, weil [Intent] Framework-Code ist — unter einem JVM-Unit-Test wirft jeder
 * Aufruf `RuntimeException("Stub!")`. [PickRequest] war deshalb bisher komplett ungetestet,
 * obwohl es die Schnittstelle ist, auf die sich jede andere App verlässt.
 */
@RunWith(AndroidJUnit4::class)
class PickRequestInstrumentedTest {

    @Before
    @After
    fun reset() {
        // PickRequest ist ein prozessweites Singleton (s. dortiges Klassendoc) — ohne Reset
        // würde ein Test den nächsten sehen.
        PickRequest.setFromIntent(null)
    }

    @Test
    fun nonPickIntentLeavesNoSpec() {
        PickRequest.setFromIntent(Intent(Intent.ACTION_MAIN))
        assertNull(PickRequest.spec.value)
    }

    @Test
    fun pickIntentWithoutTypeHasNoRestriction() {
        PickRequest.setFromIntent(Intent(Intent.ACTION_GET_CONTENT))
        val spec = requireNotNull(PickRequest.spec.value)
        assertTrue(spec.mimeTypes.isEmpty())
        assertFalse(spec.hasTypeRestriction)
    }

    @Test
    fun wildcardTypeIsNoRestriction() {
        PickRequest.setFromIntent(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        val spec = requireNotNull(PickRequest.spec.value)
        assertEquals(listOf("*/*"), spec.mimeTypes)
        assertFalse(spec.hasTypeRestriction)
    }

    @Test
    fun singleTypeIsARestriction() {
        PickRequest.setFromIntent(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
        val spec = requireNotNull(PickRequest.spec.value)
        assertEquals(listOf("image/*"), spec.mimeTypes)
        assertTrue(spec.hasTypeRestriction)
    }

    @Test
    fun extraMimeTypesWinsOverType() {
        // Android-Vertrag: wer EXTRA_MIME_TYPES setzt, setzt getType() üblicherweise auf "*/*"
        // als Sammelangabe für Empfänger, die das Extra nicht auswerten. Wer beides liest, muss
        // dem Extra folgen — sonst kommt die Einschränkung nie an.
        PickRequest.setFromIntent(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/pdf"))
            },
        )
        val spec = requireNotNull(PickRequest.spec.value)
        assertEquals(listOf("image/png", "application/pdf"), spec.mimeTypes)
        assertTrue(spec.hasTypeRestriction)
    }

    @Test
    fun blankTypesAreDropped() {
        PickRequest.setFromIntent(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "", "  "))
            },
        )
        assertEquals(listOf("image/png"), requireNotNull(PickRequest.spec.value).mimeTypes)
    }

    @Test
    fun allowMultipleIsCarried() {
        PickRequest.setFromIntent(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
        )
        assertTrue(requireNotNull(PickRequest.spec.value).allowMultiple)
    }

    @Test
    fun allowMultipleDefaultsToFalse() {
        PickRequest.setFromIntent(Intent(Intent.ACTION_GET_CONTENT))
        assertFalse(requireNotNull(PickRequest.spec.value).allowMultiple)
    }

    @Test
    fun aLaterNonPickIntentClearsTheSpec() {
        // MainActivity ruft das auch aus onNewIntent — eine wiederverwendete Activity-Instanz
        // darf nicht als Picker weiterlaufen, nachdem sie regulär gestartet wurde.
        PickRequest.setFromIntent(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
        PickRequest.setFromIntent(Intent(Intent.ACTION_MAIN))
        assertNull(PickRequest.spec.value)
    }
}
