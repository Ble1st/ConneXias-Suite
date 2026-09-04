package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.registry.Safeguard

/**
 * Gemeinsame Basis aller [Safeguard]-Implementierungen, die den [DevicePolicyManager] als
 * Device Owner ansprechen (Meilenstein C.2, Konzept Abschnitt 4). Bündelt die
 * Device-Owner-Komponente ([WardenDeviceAdminReceiver] — die einzige DO-fähige Komponente im
 * Projekt, Konzept Abschnitt 1) und den `DevicePolicyManager`-Zugriff, damit konkrete
 * Safeguards sich nur um ihren jeweiligen DPM-Aufruf kümmern müssen.
 *
 * **Wichtig für Instrumented Tests:** diese Klassen prüfen die tatsächliche Device-Owner-Rechte
 * des *aufrufenden Prozesses* (Binder-Calling-UID → Package), nicht irgendeine übergebene
 * `Context`-Referenz. Ein eigenständiges `:core:data`-Test-APK (anderes Package als
 * `de.ble1st.warden`) wäre **nicht** der auf dem Testgerät gesetzte Device Owner —
 * jeder DPM-Aufruf von dort würfe `SecurityException`. Instrumented Tests für diese Klassen
 * gehören deshalb bewusst in `:warden-app`s androidTest (self-instrumentierend unter der
 * echten `de.ble1st.warden`-UID), nicht in `:core:data`s eigenes androidTest.
 */
abstract class DpmSafeguard(protected val context: Context) : Safeguard {

    protected val admin: ComponentName = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    protected fun devicePolicyManager(): DevicePolicyManager =
        checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
            "DevicePolicyManager nicht verfügbar"
        }
}
