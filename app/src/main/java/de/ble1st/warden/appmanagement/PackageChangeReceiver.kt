package de.ble1st.warden.appmanagement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25, angelehnt an Feature-Ideenliste "Real-Time
 * Threat Protection": "Statt periodisches Polling könnte `BroadcastReceiver` für
 * `ACTION_PACKAGE_ADDED` verwendet werden"). Manifest-registriert (nicht dynamisch wie
 * [de.ble1st.warden.usb.UsbLockStateReceiver]) — `PACKAGE_ADDED`/`PACKAGE_REPLACED`/
 * `PACKAGE_REMOVED` gehören laut Android-Dokumentation zu den wenigen impliziten System-
 * Broadcasts, die seit Android 8 (API 26) weiterhin auch an manifest-deklarierte Empfänger
 * zugestellt werden — anders als die meisten anderen impliziten Broadcasts, für die
 * [de.ble1st.warden.usb.UsbLockStateReceiver]s dynamische Registrierung nötig ist.
 *
 * **Ergänzt, ersetzt nicht** [SuspiciousAppScanWorker]s 15-Minuten-Poll (dasselbe Backup-Prinzip
 * wie bei [de.ble1st.warden.usb.UsbLockStateReceiver]/[de.ble1st.warden.usb.UsbAutoLockWorker]):
 * eine frisch installierte/aktualisierte/entfernte App löst sofort einen Scan aus, statt bis zu 15
 * Minuten auf den nächsten periodischen Lauf zu warten — der Worker bleibt trotzdem geplant, falls
 * dieser Receiver aus irgendeinem Grund nicht feuert (z. B. Direct-Boot-Fenster, s.
 * `WardenApplication`-Klassendoc für dasselbe Muster).
 *
 * **Kein direkter [SuspiciousAppScanController]-Aufruf in [onReceive]:** ein
 * `BroadcastReceiver.onReceive()` läuft laut Android-Dokumentation nur für ein kurzes Zeitfenster
 * (~10s) im Hauptthread, ein voller Scan (iteriert potenziell jedes installierte Fremdpaket über
 * mehrere `PackageManager`-Aufrufe) könnte das überschreiten und einen ANR riskieren. Stattdessen
 * [SuspiciousAppScanWorker.scheduleImmediate] — derselbe Worker wie der periodische Poll, nur als
 * einmaliger, sofort laufender `OneTimeWorkRequest` statt eines periodischen.
 *
 * `PACKAGE_REMOVED` mit [Intent.EXTRA_REPLACING] `true` wird ignoriert (das ist der
 * Zwischenschritt eines Updates — `PACKAGE_REPLACED`/`PACKAGE_ADDED` feuert direkt danach ohnehin,
 * ein Scan mitten im Update-Übergang wäre nur redundante Arbeit auf einem kurzlebigen
 * Zwischenzustand).
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val relevant = when (action) {
            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> true
            Intent.ACTION_PACKAGE_REMOVED -> !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            else -> false
        }
        if (!relevant) return
        Log.i(TAG, "Paketänderung erkannt ($action) — löse Sofort-Scan aus")
        SuspiciousAppScanWorker.scheduleImmediate(context.applicationContext)
    }

    private companion object {
        const val TAG = "PackageChangeReceiver"
    }
}
