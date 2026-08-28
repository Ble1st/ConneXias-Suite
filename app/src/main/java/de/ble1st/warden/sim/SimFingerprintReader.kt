package de.ble1st.warden.sim

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.sim.SimChangeDecision
import java.security.MessageDigest

/**
 * Ermittelt einen stabilen Fingerabdruck der aktuell eingelegten SIM(s) für
 * [SimChangeController]/[de.ble1st.warden.domain.sim.SimChangeDecision] (2026-08-28).
 *
 * **Warum nicht die ICCID:** seit Android 10 liefert `SubscriptionInfo.getIccId()` für
 * nicht-privilegierte Apps nur noch einen gekürzten/leeren Wert — die klassische
 * "Seriennummer der SIM" ist für eine normale Device-Owner-App schlicht nicht mehr lesbar.
 * Stattdessen ein zusammengesetzter Abdruck aus den Feldern, die mit `READ_PHONE_STATE`
 * tatsächlich verfügbar bleiben: Subscription-ID (vergibt das System pro SIM neu, eine fremde
 * SIM bekommt eine andere), Carrier-ID sowie MCC/MNC (Land und Netzbetreiber). Ein Tausch gegen
 * eine SIM desselben Betreibers ändert immer noch die Subscription-ID — der Abdruck reagiert also
 * auch dort, wo MCC/MNC gleich blieben.
 *
 * **Gehasht statt roh gespeichert:** verglichen wird ohnehin nur auf Gleichheit, also gibt es
 * keinen Grund, Netzbetreiber-Kennungen im Klartext in einer Preferences-Datei liegen zu lassen.
 *
 * **`READ_PHONE_STATE` gewährt sich Warden als Device Owner selbst** (`setPermissionGrantState`,
 * dasselbe Muster wie `POST_NOTIFICATIONS` in [de.ble1st.warden.WardenApplication]) — ohne
 * Device-Owner-Status oder vor der Provisionierung schlägt das fehl; dann liefert [fingerprint]
 * bewusst `null` ("nicht lesbar") statt eines leeren Abdrucks, damit daraus nie eine Reaktion
 * abgeleitet wird (s. `SimChangeDecision`-Klassendoc).
 */
class SimFingerprintReader(private val context: Context) {

    /**
     * @return Hex-SHA-256 über die aktiven Subscriptions, [SimChangeDecision.NO_SIM_FINGERPRINT]
     * bei erfolgreich gelesener, aber leerer Liste, oder `null`, wenn nichts Verlässliches
     * ermittelt werden konnte.
     */
    fun fingerprint(): String? {
        ensurePermissionGranted()
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE nicht erteilt — SIM-Zustand nicht ermittelbar")
            return null
        }
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val descriptors = try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().map { info ->
                "${info.subscriptionId}:${info.carrierId}:${info.mccString.orEmpty()}:${info.mncString.orEmpty()}"
            }
        } catch (e: SecurityException) {
            // Explizites catch statt runCatching — Android Lints MissingPermission-Prüfung
            // verlangt genau diese Form, s. CLAUDE.md.
            Log.w(TAG, "SIM-Zustand nicht lesbar", e)
            return null
        }
        if (descriptors.isEmpty()) return SimChangeDecision.NO_SIM_FINGERPRINT
        return sha256Hex(descriptors.sorted().joinToString("|"))
    }

    private fun ensurePermissionGranted() {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            dpm.setPermissionGrantState(
                ComponentName(context, WardenDeviceAdminReceiver::class.java),
                context.packageName,
                Manifest.permission.READ_PHONE_STATE,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        } catch (e: Exception) {
            Log.w(TAG, "READ_PHONE_STATE-Selbstfreigabe fehlgeschlagen", e)
        }
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "SimFingerprint"
    }
}
