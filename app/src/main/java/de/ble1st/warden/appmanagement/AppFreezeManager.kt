package de.ble1st.warden.appmanagement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Milestone "App-Verwaltung: Einfrieren/Deaktivieren" — Warden hat seit Milestone I.4 ohnehin
 * [android.Manifest.permission.QUERY_ALL_PACKAGES] (Herald-Firewall-Liste, [InstalledAppLister]);
 * dieselbe Sichtbarkeit erlaubt jetzt auch, beliebige Fremd-Apps über den Device-Owner-Pfad
 * "einzufrieren" ([DevicePolicyManager.setApplicationHidden] — die App verschwindet aus
 * Launcher/Übersicht/Benachrichtigungen, Daten bleiben erhalten, jederzeit über denselben Aufruf
 * mit `hidden=false` reversibel).
 *
 * Anders als [de.ble1st.warden.registry.UserRestrictionSafeguard] bewusst **kein**
 * `Safeguard`-Registry-Eintrag: das Ziel ist dynamisch (jede installierte Fremd-App, nicht eine
 * feste, vorab bekannte Kleinmenge) — dieselbe "Wahrheit bleibt im System, keine eigene
 * Persistenz"-Haltung wie bei den C.2-Schaltern ([isFrozen] wird immer live gegen DPM abgefragt,
 * nie gecacht).
 *
 * **Wichtig für Instrumented Tests (dieselbe Einschränkung wie [DpmSafeguard]):** diese Klasse
 * prüft die tatsächliche Device-Owner-Berechtigung des *aufrufenden Prozesses*
 * (Binder-Calling-UID), nicht irgendeine übergebene [Context]-Referenz — Tests gehören deshalb
 * bewusst in `:warden-app`s androidTest, nicht in `:core:data`s eigenes.
 *
 * **Tier 3 ("App-Kontrolle", 2026-08-22) — `setPackagesSuspended` als Fallback:** schließt die in
 * CLAUDE.md dokumentierte Lücke ("Known OS-level limitation": `setApplicationHidden` scheitert
 * still für Apps, die im Manifest einen `DeviceAdminReceiver` deklarieren, oder für debuggbare
 * Apps). [setFrozen] versucht zuerst `setApplicationHidden`; scheitert das beim Einfrieren
 * (`result=false`), wird `setPackagesSuspended` als zweiter, unabhängiger OS-Mechanismus
 * versucht — andere Fehlersemantik, könnte genau dort greifen, wo Hide es nicht tut. [isFrozen]
 * berücksichtigt entsprechend beide Zustände, sonst würde ein per Suspend erfolgreich blockiertes
 * Ziel der UI/dem Verdachtsscanner fälschlich als "nicht eingefroren" erscheinen. Beim Entfrieren
 * werden beide Mechanismen best-effort zurückgesetzt, unabhängig davon, welcher tatsächlich
 * gegriffen hatte — Idempotenz-Prinzip (s. [de.ble1st.warden.domain.registry.Safeguard]-Doc), auch
 * wenn diese Klasse selbst kein `Safeguard` ist.
 */
class AppFreezeManager(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    private fun devicePolicyManager(): DevicePolicyManager =
        checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
            "DevicePolicyManager nicht verfügbar"
        }

    /** Wirft (statt `false` zu liefern), wenn `isApplicationHidden`/`isPackageSuspended` selbst
     * fehlschlagen — real gefunden (2026-08-21, s. `AppManagementScreen`-Kommentar an der
     * Fehleranzeige): ohne aktiven Device Owner wirft [DevicePolicyManager] eine
     * [SecurityException]. Ein hier verschlucktes `getOrDefault(false)` würde jede Zeile
     * fälschlich als "nicht eingefroren" zeigen, statt die vorgesehene "Liste konnte nicht
     * geladen werden"-Fehleranzeige auszulösen — derselbe Fail-Safe-Grundsatz wie überall sonst
     * im Projekt (Invariante 6), hier über den `runCatching` der Aufrufer (`loadManagedAppsSafely`)
     * statt eines eigenen. */
    fun isFrozen(packageName: String): Boolean {
        val hidden = devicePolicyManager().isApplicationHidden(admin, packageName)
        return hidden || isSuspended(packageName)
    }

    fun setFrozen(packageName: String, frozen: Boolean): Boolean {
        if (!frozen) {
            // Both mechanisms must end unfrozen — OR would report success while still suspended.
            val unhidden = runCatching { devicePolicyManager().setApplicationHidden(admin, packageName, false) }.getOrDefault(false)
            val unsuspendResult = runCatching { devicePolicyManager().setPackagesSuspended(admin, arrayOf(packageName), false) }
            return unhidden && unsuspendResult.getOrNull()?.isEmpty() == true
        }
        // Ungated bis 2026-08-24 (Review-Fund): anders als der Unfreeze-Zweig unten und der
        // Suspend-Fallback direkt darunter fehlte hier der runCatching-Schutz — eine
        // SecurityException (z. B. verlorener Device-Owner-Status zwischen Scan und Tap auf
        // "Einfrieren") hätte unbehandelt bis zum aufrufenden SuspiciousAppActionReceiver
        // durchgeschlagen und den Prozess abstürzen lassen, statt die dafür vorgesehene
        // "Einfrieren fehlgeschlagen"-Meldung zu zeigen (s. SuspiciousAppScanController
        // .handleFreezeAction).
        val hidden = runCatching { devicePolicyManager().setApplicationHidden(admin, packageName, true) }.getOrDefault(false)
        if (hidden) return true
        // Hide ist gescheitert (bekannte Lücke, s. Klassendoc) — Suspend als zweiten,
        // unabhängigen Mechanismus versuchen. `setPackagesSuspended` liefert die Pakete zurück,
        // die *nicht* suspendiert werden konnten — ein leeres Array heißt vollständiger Erfolg.
        val failedToSuspend = runCatching {
            devicePolicyManager().setPackagesSuspended(admin, arrayOf(packageName), true)
        }.getOrDefault(arrayOf(packageName))
        return failedToSuspend.isEmpty()
    }

    /** Wirft ebenfalls statt zu verschlucken — s. [isFrozen]-Doc. */
    private fun isSuspended(packageName: String): Boolean =
        devicePolicyManager().isPackageSuspended(admin, packageName)
}
