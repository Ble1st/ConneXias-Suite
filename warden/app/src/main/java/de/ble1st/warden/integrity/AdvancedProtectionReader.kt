package de.ble1st.warden.integrity

import android.content.Context
import android.os.Build
import android.util.Log
import de.ble1st.warden.domain.advancedprotection.AdvancedProtectionState

/**
 * Liest Androids "Erweiterten Schutz" (AAPM) aus (2026-09-05, Tier-1 der DPC-Recherche).
 *
 * **Android-15-Rückfallweg, ausdrücklich verlangt (Nutzerwunsch 2026-09-05: "aber mit Android 15
 * Fallback, da Zielgerät noch kein Android 16 Update hat"):** `AdvancedProtectionManager` gibt es
 * erst ab API 36. Unterhalb davon wird **kein** Fehler erzeugt und nichts geraten, sondern
 * [AdvancedProtectionState.NICHT_VERFUEGBAR] geliefert — ein eigener Zustand, den UI und Score
 * getrennt von "aus" behandeln (s. dortiges Klassendoc). Der Zugriff steht hinter einer
 * `Build.VERSION.SDK_INT`-Prüfung; `compileSdk`/`targetSdk` liegen bei 37, die Klasse ist also zur
 * Übersetzungszeit vorhanden, wird auf Android 15 aber nie geladen.
 *
 * **Reflection statt direktem Aufruf.** `android.security.advancedprotection
 * .AdvancedProtectionManager` ist zwar ab API 36 öffentlich, aber `isAdvancedProtectionEnabled`
 * hängt an der Berechtigung `QUERY_ADVANCED_PROTECTION_MODE`; ein direkter Aufruf würde den Build
 * an eine API binden, die auf der Zielplattform (Android 15) gar nicht existiert, und Lint dazu
 * zwingen, jede Aufrufstelle mit `@RequiresApi` zu markieren. Der Reflection-Weg hält die
 * Versionsabhängigkeit an genau *einer* Stelle — hier — und macht jeden Fehlschlag zu einem
 * sauberen [AdvancedProtectionState.UNBEKANNT] statt zu einem `NoClassDefFoundError` irgendwo
 * oben in der UI. Sobald das Mindest-SDK bei 36 liegt, kann das durch einen direkten Aufruf
 * ersetzt werden, ohne dass sich für die Aufrufer etwas ändert.
 */
class AdvancedProtectionReader(private val context: Context) {

    fun read(): AdvancedProtectionState {
        if (Build.VERSION.SDK_INT < ANDROID_16) return AdvancedProtectionState.NICHT_VERFUEGBAR
        return try {
            val managerClass = Class.forName("android.security.advancedprotection.AdvancedProtectionManager")
            val manager = context.getSystemService(managerClass)
                ?: return AdvancedProtectionState.UNBEKANNT
            val enabled = managerClass
                .getMethod("isAdvancedProtectionEnabled")
                .invoke(manager) as? Boolean
                ?: return AdvancedProtectionState.UNBEKANNT
            if (enabled) AdvancedProtectionState.AN else AdvancedProtectionState.AUS
        } catch (e: Exception) {
            Log.i(TAG, "Erweiterter Schutz nicht auslesbar", e)
            AdvancedProtectionState.UNBEKANNT
        }
    }

    private companion object {
        const val TAG = "AdvancedProtectionReader"

        /** API-Level, ab dem AAPM existiert. Als benannte Konstante statt `Build.VERSION_CODES`-
         * Referenz, damit die Klasse auch mit einem älteren `compileSdk` übersetzbar bliebe. */
        const val ANDROID_16 = 36
    }
}
