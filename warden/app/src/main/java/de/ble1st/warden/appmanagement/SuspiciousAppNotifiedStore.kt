package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 6) — merkt sich
 * pro Paket, mit welchem [de.ble1st.warden.domain.appmanagement.SuspiciousSignal]-Bitmaske zuletzt
 * benachrichtigt wurde. [SuspiciousAppScanController.notifyNewFindings] überspringt einen Fund,
 * dessen Signale sich seit der letzten Benachrichtigung nicht geändert haben, statt bei jedem
 * periodischen 15-Minuten-Lauf erneut zu posten — [SuspiciousAppNotifier.notify]s
 * `setOnlyAlertOnce` verhinderte bisher schon ein erneutes Aufploppen/Piepen, dieser Store macht
 * den Re-Post selbst überflüssig. Ändern sich die Signale (z. B. kommt
 * `SIGNING_CERT_CHANGED` zu einem bereits gemeldeten `EXTRA_DEVICE_ADMIN`-Fund dazu), wird erneut
 * benachrichtigt — die neue Information ist relevant.
 *
 * Eigene, separate `EnvelopeFile`-unabhängige Datei statt einer Erweiterung von
 * [SuspiciousAppScanStore]s Format — ändert dessen bestehendes Envelope-Format nicht, ein bereits
 * vorhandener `enabled`/`trusted`-Blob bleibt unverändert lesbar (dieselbe "ein Format pro Zweck,
 * eine Zeile pro Paket"-Bauweise wie [SigningCertHistoryStore]). Klartext-`SharedPreferences`
 * genügt: ein verlorener Wert bedeutet höchstens eine einmalige Doppel-Benachrichtigung, kein
 * Sicherheitsverlust.
 */
class SuspiciousAppNotifiedStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** `null` = für dieses Paket wurde noch nie benachrichtigt. */
    fun lastNotifiedBitmask(packageName: String): Int? =
        if (prefs.contains(packageName)) prefs.getInt(packageName, 0) else null

    fun recordNotified(packageName: String, signalsBitmask: Int) {
        prefs.edit { putInt(packageName, signalsBitmask) }
    }

    /** Nach Einfrieren/Deinstallieren/"Vertrauen" — ein späteres erneutes Auftauchen desselben
     * Pakets (z. B. nach Neuinstallation) soll wieder frisch benachrichtigt werden, nicht durch
     * einen alten Eintrag unterdrückt bleiben. */
    fun clear(packageName: String) {
        prefs.edit { remove(packageName) }
    }

    private companion object {
        const val PREFS_NAME = "warden_suspicious_app_notified"
    }
}
