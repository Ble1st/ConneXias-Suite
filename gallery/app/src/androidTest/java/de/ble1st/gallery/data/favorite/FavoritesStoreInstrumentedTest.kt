package de.ble1st.gallery.data.favorite

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Favoriten liegen als lokale Menge von MediaStore-IDs in den `SharedPreferences` — also nur
 * unter Instrumentation prüfbar.
 *
 * Der Test pinnt neben dem Verhalten auch das **Speicherformat** (JSON-Array von Zahlen): Lesen
 * und Schreiben sind zwei getrennte private Funktionen, und ein Format-Wechsel auf nur einer
 * Seite würde alle Markierungen des Nutzers stillschweigend verlieren, ohne dass irgendetwas
 * abstürzt.
 */
@RunWith(AndroidJUnit4::class)
class FavoritesStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun persistedIds(): Set<Long> {
        val raw = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
            .getString("ids", null) ?: return emptySet()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getLong(it) }.toSet()
    }

    @Before
    fun clear() {
        // Über die öffentliche API statt durch Leeren der Prefs-Datei: FavoritesStore ist ein
        // prozessweites Singleton, das seinen Inhalt nur einmal je Prozess einliest — ein direkt
        // geleertes Prefs-File würde der laufende Store gar nicht mitbekommen.
        FavoritesStore.setAll(context, FavoritesStore.load(context), favorite = false)
    }

    @Test
    fun freshStoreIsEmpty() {
        assertTrue(FavoritesStore.load(context).isEmpty())
    }

    @Test
    fun toggleAddsAndRemovesTheSameId() {
        FavoritesStore.toggle(context, 42)
        assertTrue(42L in FavoritesStore.load(context))
        FavoritesStore.toggle(context, 42)
        assertFalse(42L in FavoritesStore.load(context))
    }

    @Test
    fun toggleTouchesOnlyTheGivenId() {
        FavoritesStore.setAll(context, setOf(1, 2, 3), favorite = true)
        FavoritesStore.toggle(context, 2)
        assertEquals(setOf(1L, 3L), FavoritesStore.load(context))
    }

    @Test
    fun setAllWithFalseRemovesOnlyTheGivenIds() {
        FavoritesStore.setAll(context, setOf(1, 2, 3, 4), favorite = true)
        FavoritesStore.setAll(context, setOf(2, 4), favorite = false)
        assertEquals(setOf(1L, 3L), FavoritesStore.load(context))
    }

    @Test
    fun setAllWithTrueIsIdempotentOnAlreadyMarkedIds() {
        // Der Grund, warum setAll den Zielzustand übergeben bekommt statt umzuschalten: eine
        // gemischte Auswahl soll ein vorhersehbares Ergebnis haben.
        FavoritesStore.setAll(context, setOf(1, 2), favorite = true)
        FavoritesStore.setAll(context, setOf(2, 3), favorite = true)
        assertEquals(setOf(1L, 2L, 3L), FavoritesStore.load(context))
    }

    @Test
    fun theStateFlowMirrorsTheStoredSet() {
        FavoritesStore.setAll(context, setOf(5, 6), favorite = true)
        assertEquals(FavoritesStore.load(context), FavoritesStore.favorites.value)
    }

    @Test
    fun everyChangeReachesThePreferencesFile() {
        FavoritesStore.toggle(context, 11)
        FavoritesStore.setAll(context, setOf(12, 13), favorite = true)
        assertEquals(setOf(11L, 12L, 13L), persistedIds())

        FavoritesStore.toggle(context, 11)
        assertEquals(setOf(12L, 13L), persistedIds())
    }
}
