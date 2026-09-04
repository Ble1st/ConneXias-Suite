package de.ble1st.warden.sentinel

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Existiert ausschließlich, damit Warden (`de.ble1st.warden.sentinelbridge
 * .SentinelDeathWatchdog`) sich per `bindService()` + `IBinder.linkToDeath()` an Sentinels
 * Prozess binden und dessen Tod kernelvermittelt erkennen kann (Warden-seitiges
 * Sicherheitsnetz: drei Tode in 60s → DPM-Whitelist zurückziehen + Masterschalter, s. dortiges
 * Klassendoc). Kein AIDL-Contract nötig — Warden ruft nie eine Methode auf diesem Binder auf,
 * nur `linkToDeath()`, ein leerer `Binder()` genügt. `exported="true"` +
 * `de.ble1st.warden.sentinel.permission.ENGAGE`-geschützt (AndroidManifest.xml) — dieselbe
 * Vertrauensrichtung Warden→Sentinel wie [SentinelActivity], kein zweites Permission nötig.
 */
class SentinelHeartbeatService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent?): IBinder = binder
}
