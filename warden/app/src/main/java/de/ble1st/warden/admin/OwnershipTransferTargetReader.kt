package de.ble1st.warden.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Ein möglicher Empfänger der Device-Owner-Rolle. */
data class OwnershipTransferTarget(
    val label: String,
    val packageName: String,
    val receiver: ComponentName,
)

/**
 * Listet die Apps auf, an die sich die Device-Owner-Rolle überhaupt übertragen ließe
 * (Tier 3 der DPC-Recherche, 2026-09-05 — "changeowner in Erweitert").
 *
 * **Bedingung ist ein deklarierter [DeviceAdminReceiver], nicht ein aktivierter.**
 * `DevicePolicyManager.transferOwnership` verlangt genau das: ein Ziel, das einen Admin-Empfänger
 * im Manifest führt — aktiviert wird er durch die Übertragung selbst. Das ist derselbe
 * Manifest-Blick, den [DeviceAdminCapabilityScanner] für den Bedrohungs-Scanner benutzt, hier nur
 * mit dem vollständigen `ComponentName` statt bloß dem Paketnamen (den braucht `transferOwnership`).
 *
 * **Bewusst ohne `MATCH_UNINSTALLED_PACKAGES`/`MATCH_DISABLED_COMPONENTS`**, anders als beim
 * Scanner: dort ist eine möglichst weite Sicht richtig (proaktiv warnen, auch vor Deaktiviertem),
 * hier wäre sie falsch — ein deinstalliertes oder deaktiviertes Ziel kann die Rolle nicht
 * übernehmen, und ein Gerät ohne Device Owner ist das Ergebnis, das sich am wenigsten rückgängig
 * machen lässt. Diese Liste zeigt deshalb nur, was wirklich in Frage kommt.
 *
 * Warden selbst wird herausgefiltert: eine Übertragung an das eigene Paket ist keine.
 */
class OwnershipTransferTargetReader(private val context: Context) {

    fun availableTargets(): List<OwnershipTransferTarget> {
        val intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
        val flags = PackageManager.ResolveInfoFlags.of(0L)
        return context.packageManager
            .queryBroadcastReceivers(intent, flags)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .map { activityInfo ->
                OwnershipTransferTarget(
                    label = readLabel(activityInfo.packageName),
                    packageName = activityInfo.packageName,
                    receiver = ComponentName(activityInfo.packageName, activityInfo.name),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun readLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
