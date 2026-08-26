package de.ble1st.warden.appmanagement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25, angelehnt an Feature-Ideenliste Punkt 55
 * "Automated Incident Response — Auto-Quarantine verdächtiger Apps"). **Keine Datei-Isolation/
 * kein Verschieben in ein Sandbox-Verzeichnis** — Android isoliert jede App bereits per UID/
 * Dateisystem-Rechten voneinander; ein Drittprozess (auch als Device Owner) kann ohne Root nicht
 * lesend oder schreibend auf das private Datenverzeichnis eines fremden Pakets zugreifen, es gibt
 * also keine öffentliche API, mit der Warden Dateien eines verdächtigen Pakets tatsächlich
 * "verschieben" könnte — dieselbe Grenze wie beim Rest des App-Verwaltungs-Codes (s. z. B.
 * [AppDataWiper]-Klassendoc: nur `clearApplicationUserData()`, kein Datei-Zugriff). Das real
 * verfügbare, vergleichbar wirksame Device-Owner-Mittel ist stattdessen
 * `DevicePolicyManager.setPermissionGrantState(..., DENIED)`: entzieht einer bereits verdächtigen
 * App jedes zur Laufzeit gewährte gefährliche Recht (Kamera, Standort, Kontakte, …), zusätzlich
 * zum bereits bestehenden Einfrieren (`AppFreezeManager`) — eine eingefrorene App kann zwar nicht
 * mehr laufen, ein späteres manuelles Entfrieren (z. B. ein Fehlalarm, der sich als solcher
 * herausstellt) soll das Ziel aber nicht automatisch mit alten, unwiderrufenen gefährlichen
 * Rechten zurückbringen.
 *
 * **Nur bereits *deklarierte* gefährliche Rechte, [PermissionClassifier] entscheidet welche** —
 * `setPermissionGrantState` wirft laut Android-Dokumentation für eine Permission, die das Zielpaket
 * gar nicht in seinem Manifest deklariert; [revokeDangerousPermissions] filtert deshalb vorher auf
 * [PermissionClassifier.classify]` == DANGEROUS` über die tatsächlich im Manifest angeforderten
 * Rechte (`PackageManager.getPackageInfo(..., GET_PERMISSIONS).requestedPermissions`), kein
 * Blind-Versuch über eine feste Liste.
 */
class DangerousPermissionRevoker(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    /** Best-effort — ein einzelnes fehlschlagendes Recht (z. B. inzwischen deinstalliert) darf die
     * übrigen nicht verhindern, deshalb pro Permission einzeln `runCatching`, dieselbe Haltung wie
     * [AppFreezeManager.setFrozen]s Suspend-Fallback. Liefert die Namen der tatsächlich entzogenen
     * Rechte, für die Audit-Log-Zeile beim Aufrufer. */
    fun revokeDangerousPermissions(packageName: String): List<String> {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return emptyList()
        val declared = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val dangerous = declared.filter { PermissionClassifier.classify(context, it) == PermissionCategory.DANGEROUS }
        return dangerous.filter { permission ->
            runCatching {
                dpm.setPermissionGrantState(admin, packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            }.getOrDefault(false)
        }
    }
}
