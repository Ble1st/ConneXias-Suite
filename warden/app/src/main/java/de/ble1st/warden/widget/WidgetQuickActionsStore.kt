package de.ble1st.warden.widget

import android.content.Context
import androidx.core.content.edit

/**
 * "Quick-Action-Widget für Lockdown/Sentinel-Kiosk" (2026-09-05, Nutzerwunsch: "ein
 * Quick-Action-Menü für Lockdown und Sentinel-Lockdown-Task, aber mit Schalter in den
 * Einstellungen, um diese scharf zu schalten") — der Master-Schalter, ohne den
 * [WardenStatusWidget] die beiden Aktions-Schaltflächen gar nicht erst zeichnet.
 *
 * **Default `false`, dieselbe "opt-in für ein zusätzliches Angriffsfenster"-Haltung wie
 * [de.ble1st.warden.antitheft.AntiTheftConfig]/BLE-Tracker-Wächter.** Ohne diesen Schalter zeigt
 * das Widget nur die bereits vorhandenen drei Status-Zeilen (s. [WardenStatusWidget]-Klassendoc,
 * "ursprünglich bewusst komplett nicht gebaut" — genau dieselbe Abwägung gilt hier: ein Homescreen-
 * Widget ist noch exponierter erreichbar als eine Quick-Settings-Kachel, jede/r mit physischem
 * Zugriff auf den Homescreen sieht die Schaltflächen sofort). Wer das Widget bewusst zusätzlich
 * absichert (z. B. auf einem gesperrten Launcher, oder weil das Gerät ohnehin nur an einem Ort
 * steht), kann das hier gezielt einschalten — beide Aktionen bleiben trotzdem strukturell hinter
 * [de.ble1st.warden.presence.WardenLockSession] (s.
 * [de.ble1st.warden.widget.WardenQuickActionCallbacks]-Klassendoc): dieser Schalter entscheidet
 * nur, ob die Schaltflächen überhaupt existieren, nicht, ob sie den PIN-Zugangsschutz umgehen.
 *
 * Plain-Klartext-`SharedPreferences`, kein Envelope — dieselbe Begründung wie
 * [de.ble1st.warden.pin.LockdownTriggerProfileStore]: reine UI-Einstellung, kein
 * Sicherheitsverlust bei Zurücksetzen auf den Default (der Default ist bereits die strengere
 * Seite).
 */
object WidgetQuickActionsStore {
    private const val PREFS_NAME = "warden_widget_quick_actions"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
