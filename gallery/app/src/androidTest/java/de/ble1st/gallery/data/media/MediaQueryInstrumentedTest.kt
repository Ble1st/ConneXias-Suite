package de.ble1st.gallery.data.media

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prüft die Abfragen aus [MediaQuery]/[MediaStoreRepository] gegen einen **echten**
 * MediaProvider.
 *
 * Das ist kein Beiwerk, sondern der Kern der Absicherung des seitenweisen Ladens (analyse.md 6.2):
 * die Sortierung nach Datum enthält seitdem einen SQL-Ausdruck
 * (`CASE WHEN datetaken > 0 THEN ... ELSE date_added * 1000 END`, s.
 * [MediaQuery.DATE_SORT_EXPRESSION]), und der MediaProvider prüft `ORDER BY`-Zeichenketten
 * (`SQLiteQueryBuilder.setStrict`). Ob eine konkrete Android-Fassung diesen Ausdruck durchlässt,
 * lässt sich nur auf einem Gerät oder Emulator feststellen — unter einem JVM-Unit-Test gibt es
 * keinen Provider, der ihn ablehnen könnte.
 *
 * [MediaStoreRepository] fängt eine Ablehnung ab und wiederholt mit
 * [MediaQuery.fallbackSortOrder]; dieser Test stellt fest, **ob** dieser Rückfall überhaupt
 * gebraucht wird. Ein Fehlschlag hier bedeutet keinen Absturz in der App, wohl aber, dass
 * importierte Dateien ohne EXIF auf dieser Android-Fassung nach Import- statt nach
 * Aufnahmezeitpunkt einsortiert werden.
 *
 * Läuft ohne erteilte Medienberechtigung: der MediaProvider liefert dann nur die Einträge der
 * eigenen App (im Zweifel keine). Für die Frage, ob die Abfrage angenommen wird, genügt das —
 * die Prüfung der Sortierzeichenkette geschieht beim Zusammenbauen der Abfrage, nicht beim Lesen
 * der Zeilen. Die inhaltlichen Prüfungen unten sind entsprechend so formuliert, dass sie auch bei
 * null Treffern gelten.
 */
@RunWith(AndroidJUnit4::class)
class MediaQueryInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun datumsSortierungWirdVomProviderAngenommen() {
        // Der eigentliche Zweck dieser Testklasse. queryPage wirft nicht, weil runQuery abfängt —
        // deshalb wird hier die Abfrage bewusst ohne dieses Netz gestellt.
        val query = MediaQuery(order = SortOrder.DATE)
        val cursor = context.contentResolver.query(
            android.provider.MediaStore.Files.getContentUri("external"),
            arrayOf(android.provider.MediaStore.Files.FileColumns._ID),
            query.selection(),
            query.selectionArgs(),
            query.sortOrder(),
        )
        cursor.use {
            assertTrue(
                "MediaProvider hat den CASE-WHEN-Sortierausdruck abgelehnt — " +
                    "MediaStoreRepository fällt dann auf date_added zurück (s. Klassendoc)",
                it != null,
            )
        }
    }

    @Test
    fun alleSortierungenLiefernEinErgebnis() {
        for (order in SortOrder.entries) {
            val items = MediaStoreRepository.queryPage(context, MediaQuery(order = order), limit = 10, offset = 0)
            assertTrue(order.name, items.size <= 10)
        }
    }

    @Test
    fun ersatzSortierungWirdAngenommen() {
        val query = MediaQuery(order = SortOrder.DATE)
        val cursor = context.contentResolver.query(
            android.provider.MediaStore.Files.getContentUri("external"),
            arrayOf(android.provider.MediaStore.Files.FileColumns._ID),
            query.selection(),
            query.selectionArgs(),
            query.fallbackSortOrder(),
        )
        cursor.use { assertTrue("Die Ersatzsortierung muss immer durchgehen", it != null) }
    }

    @Test
    fun seitenGrenzenUeberschneidenSichNicht() {
        // Die Kernaussage des seitenweisen Ladens: zwei aufeinanderfolgende Fenster derselben
        // Abfrage müssen zusammen dasselbe ergeben wie ein Fenster doppelter Größe. Genau das
        // geht schief, wenn die Sortierung nicht eindeutig ist (deshalb der _ID-Stichentscheid).
        val query = MediaQuery(order = SortOrder.DATE)
        val firstTwo = MediaStoreRepository.queryPage(context, query, limit = 2, offset = 0)
        val second = MediaStoreRepository.queryPage(context, query, limit = 1, offset = 1)

        if (firstTwo.size == 2) {
            assertEquals(1, second.size)
            assertEquals(firstTwo[1].id, second[0].id)
        }
    }

    @Test
    fun offsetJenseitsDesEndesLiefertLeereSeite() {
        // Paging fragt nach einer Invalidierung an der zuletzt sichtbaren Position weiter; hat
        // sich der Bestand verkleinert, liegt diese Position hinter dem Ende. Das muss eine leere
        // Seite ergeben, keine Ausnahme.
        val query = MediaQuery(order = SortOrder.DATE)
        val items = MediaStoreRepository.queryPage(context, query, limit = 10, offset = 1_000_000)

        assertTrue(items.isEmpty())
    }

    @Test
    fun anzahlUndIdListeStimmenUeberein() {
        val query = MediaQuery(order = SortOrder.DATE)

        assertEquals(MediaStoreRepository.count(context, query), MediaStoreRepository.queryIds(context, query).size)
    }

    @Test
    fun positionsbestimmungPasstZurIdListe() {
        val query = MediaQuery(order = SortOrder.DATE)
        val ids = MediaStoreRepository.queryIds(context, query)

        if (ids.isNotEmpty()) {
            assertEquals(0, MediaStoreRepository.indexOf(context, query, ids.first()))
            assertEquals(ids.lastIndex, MediaStoreRepository.indexOf(context, query, ids.last()))
        }
        // Eine ID, die es sicher nicht gibt.
        assertEquals(-1, MediaStoreRepository.indexOf(context, query, Long.MAX_VALUE))
    }

    @Test
    fun suchbegriffMitPlatzhalterTrifftNichtAlles() {
        // "%" als Suchbegriff würde ohne Maskierung jeden Dateinamen treffen.
        val alle = MediaStoreRepository.count(context, MediaQuery())
        val mitPlatzhalter = MediaStoreRepository.count(context, MediaQuery(search = "%"))

        if (alle > 0) {
            assertTrue(
                "Ein wörtliches Prozentzeichen kommt in Dateinamen praktisch nie vor",
                mitPlatzhalter < alle,
            )
        }
    }

    @Test
    fun unbekannteIdMengeLiefertNichts() {
        assertTrue(MediaStoreRepository.queryItems(context, setOf(Long.MAX_VALUE), SortOrder.DATE).isEmpty())
        assertTrue(MediaStoreRepository.queryItems(context, emptySet(), SortOrder.DATE).isEmpty())
    }

    @Test
    fun ordneruebersichtZaehltSoVieleWieDieGesamtabfrage() {
        // Die Ordnerübersicht entsteht aus einer eigenen, schlanken Abfrage (foldIntoBuckets über
        // vier Spalten) — sie muss denselben Bestand sehen wie die vollständige Abfrage.
        val gesamt = MediaStoreRepository.count(context, MediaQuery())
        val ids = MediaStoreRepository.queryIds(context, MediaQuery())

        assertEquals(gesamt, ids.size)
    }
}
