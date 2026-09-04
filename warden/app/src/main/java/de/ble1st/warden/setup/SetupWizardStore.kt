package de.ble1st.warden.setup

import android.content.Context
import androidx.core.content.edit

/**
 * Merkt sich, ob der Ersteinrichtungs-Assistent (`de.ble1st.warden.ui.SetupWizardScreen`) bereits
 * abgeschlossen wurde. Ein einziges Bit — der Fortschritt der einzelnen Schritte wird bewusst
 * **nicht** hier gespeichert, sondern bei jedem Öffnen aus dem echten Zustand gelesen (PIN-Store,
 * Profil-Store, installierte Pakete, Drill-Bit): ein zweiter, mitgeführter Fortschrittsstand wäre
 * genau die Art von Kopie, die nach einem Zurücksetzen der PIN oder einer Sentinel-Deinstallation
 * still falsch weiterläuft und dem Nutzer eine Absicherung meldet, die es nicht mehr gibt.
 *
 * "Abgeschlossen" heißt hier ausschließlich "nicht mehr automatisch beim Start zeigen" — es ist
 * keine Aussage darüber, ob alle Schritte tatsächlich erledigt sind. Der Assistent lässt sich
 * bewusst auch mit offenen Punkten beenden (nicht jedes Gerät braucht Kiosk/Sentinel), und der
 * Bildschirm bleibt über das Menü jederzeit erreichbar.
 */
object SetupWizardStore {
    private const val PREFS_NAME = "warden_setup_wizard"
    private const val KEY_COMPLETED = "completed"

    fun isCompleted(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        prefs(context).edit { putBoolean(KEY_COMPLETED, true) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
