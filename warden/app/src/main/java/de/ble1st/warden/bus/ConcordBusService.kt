package de.ble1st.warden.bus

import android.app.Service
import android.content.Intent
import android.os.IBinder
import de.ble1st.warden.WardenApplication

/**
 * Concord-Bus-HOST für den `:barbican`-Prozess (2026-08-31, Design-Dok
 * `docs/design-barbican-prozess-childvpn.md`) — Binder-Wrapper um Wardens eine, app-weite
 * [ConcordBus]-Instanz ([WardenApplication.concordBus]). Läuft im Hauptprozess (kein
 * `android:process` im Manifest), `exported="false"` genügt: das blockiert nur fremde UIDs, nicht
 * denselben-App-anderen-Prozess ([de.ble1st.warden.vpn.WardenVpnService] in `:barbican`).
 *
 * Bewusst dünn — keine eigene Logik, nur Marshalling. Ein `SecurityException` aus
 * [ConcordBus.reportBarbicanEvent] (Rate-Limit gegriffen) wird NICHT hier abgefangen: Standard-
 * AIDL-Stubs marshallen `RuntimeException`s automatisch über die Binder-Grenze zurück
 * (`Parcel.writeException`/`readException`), der Aufrufer ([BarbicanConcordClient]) sieht die
 * Ablehnung also unverändert als Exception — dieselbe "eine Ablehnung muss ankommen"-Haltung wie
 * überall sonst in [ConcordBus].
 */
class ConcordBusService : Service() {

    private val binder = object : IConcordBus.Stub() {
        override fun getBusVersion(): Int = VERSION

        override fun reportBarbicanEvent(priority: Int, message: String): Boolean =
            concordBus().reportBarbicanEvent(priority, message)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun concordBus(): ConcordBus {
        val app = application
        check(app is WardenApplication) { "ConcordBusService außerhalb von WardenApplication gebunden" }
        return app.concordBus
    }

    private companion object {
        const val VERSION = 1
    }
}
