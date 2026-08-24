package de.ble1st.warden.pin

import android.app.Activity
import de.ble1st.warden.domain.pin.WardenLockTaskGate

/**
 * Meilenstein H.8 (Konzept Abschnitt 7). Dünner Wrapper um `Activity.startLockTask()`/
 * `stopLockTask()` — funktioniert nur, wenn Warden zuvor
 * `WardenLockTaskAuthorizer.apply()` aufgerufen hat.
 *
 * **Bewusst nirgends automatisch aufgerufen** — reales `startLockTask()` auf dem einzigen
 * Testgerät zu riskieren, ohne zuvor den Notruf-Escape-Pfad real verifiziert zu haben, könnte
 * das Gerät im Kiosk-Modus aussperren (dieselbe Vorsichts-Kategorie wie
 * `DISALLOW_FACTORY_RESET`/`DISALLOW_SAFE_BOOT`). [WardenLockTaskGate] verweigert
 * [startIfPermitted] strukturell, solange kein echter, manuell durchgeführter Notruf-Drill
 * bestätigt wurde (`emergencyCallDrillPassed`-Parameter, vom Aufrufer explizit übergeben — kein
 * gespeichertes/implizites Flag, das versehentlich `true` bleiben könnte). **Offener
 * Folgeschritt, nicht Teil dieser Runde:** ein realer Notruf-Drill muss vor dem ersten
 * produktiven Einsatz noch durchgeführt und dokumentiert werden.
 */
class WardenLockTaskManager(private val activity: Activity) {

    fun startIfPermitted(emergencyCallDrillPassed: Boolean): Boolean {
        if (!WardenLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed)) return false
        activity.startLockTask()
        return true
    }

    fun stop() {
        activity.stopLockTask()
    }
}
