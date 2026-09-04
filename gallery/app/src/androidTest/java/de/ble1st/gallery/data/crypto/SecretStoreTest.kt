package de.ble1st.gallery.data.crypto

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * [SecretStore] löst seit 2026-09-04 `androidx.security:security-crypto` ab (s. dessen
 * Klassendoc). Der Schlüssel liegt im Android-Keystore — es gibt also keine JVM-Fassung davon,
 * die Prüfung muss auf einem Gerät oder Emulator laufen.
 *
 * Geprüft wird das, was die abgelöste Bibliothek zugesichert hat und worauf sich
 * `WebDavAccountStore` verlässt: der Wert kommt unverändert zurück, er steht nicht im Klartext in
 * der Datei, und ein unlesbarer Eintrag ergibt `null` statt eines Absturzes.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    private lateinit var context: Context
    private lateinit var store: SecretStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = SecretStore(context, PREFS_FILE, KEY_ALIAS)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    @Test
    fun gespeicherterWertKommtUnveraendertZurueck() {
        store.putString("password", "hunter2")

        assertEquals("hunter2", store.getString("password"))
    }

    @Test
    fun umlauteUndSonderzeichenUeberlebenDieRundreise() {
        // Die Zeichenkette geht als UTF-8 durch die Verschlüsselung — ein Passwort mit Umlauten
        // oder Emoji darf dabei nicht verstümmelt werden.
        val geheim = "Paßwört-🔑-掛"
        store.putString("password", geheim)

        assertEquals(geheim, store.getString("password"))
    }

    @Test
    fun nichtGesetzterSchluesselLiefertNull() {
        assertNull(store.getString("gibtEsNicht"))
    }

    @Test
    fun derWertStehtNichtImKlartextInDerDatei() {
        // Der eigentliche Zweck der Klasse. Der Schlüsselname darf im Klartext stehen (s.
        // Klassendoc), der Wert nicht.
        store.putString("password", "hunter2")

        val roh = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString("password", null)

        assertNotEquals("hunter2", roh)
        assertEquals(false, roh?.contains("hunter2"))
    }

    @Test
    fun zweimalDasselbeErgibtVerschiedeneGeheimtexte() {
        // Jeder Schreibvorgang holt sich einen neuen Initialisierungsvektor. Ohne das ließe sich
        // an der Datei ablesen, dass zwei Konten dasselbe Passwort tragen.
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        store.putString("a", "hunter2")
        store.putString("b", "hunter2")

        assertNotEquals(prefs.getString("a", null), prefs.getString("b", null))
    }

    @Test
    fun unlesbarerEintragLiefertNullStattAbsturz() {
        // Der Fall nach einem Verlust des Keystore-Schlüssels bei erhaltener Datei. Die abgelöste
        // Bibliothek warf hier bis in den Aufrufer.
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit { putString("password", "kein gültiger Geheimtext") }

        assertNull(store.getString("password"))
    }

    @Test
    fun mehrereWerteAufEinmalLandenAlleInDerDatei() {
        store.putStrings(mapOf("baseUrl" to "https://example.invalid/dav", "username" to "gerd"))

        assertEquals("https://example.invalid/dav", store.getString("baseUrl"))
        assertEquals("gerd", store.getString("username"))
    }

    @Test
    fun clearEntferntAlleWerte() {
        store.putStrings(mapOf("baseUrl" to "https://example.invalid/dav", "username" to "gerd"))
        store.clear()

        assertNull(store.getString("baseUrl"))
        assertNull(store.getString("username"))
    }

    @Test
    fun einNeuerStoreLiestWasDerVorherigeGeschriebenHat() {
        // Der Alltagsfall: geschrieben beim Einrichten, gelesen nach dem nächsten App-Start.
        store.putString("password", "hunter2")

        assertEquals("hunter2", SecretStore(context, PREFS_FILE, KEY_ALIAS).getString("password"))
    }

    private companion object {
        const val PREFS_FILE = "secret_store_test"
        const val KEY_ALIAS = "de.ble1st.gallery.secret_store_test"
    }
}
