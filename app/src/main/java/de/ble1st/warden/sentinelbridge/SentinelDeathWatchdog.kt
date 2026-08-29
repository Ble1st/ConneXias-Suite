package de.ble1st.warden.sentinelbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import de.ble1st.warden.domain.sentinelbridge.SentinelWatchdogDecision
import de.ble1st.warden.logging.HashChainLogStore

private const val SENTINEL_PACKAGE_NAME = "de.ble1st.warden.sentinel"
private const val SENTINEL_HEARTBEAT_SERVICE_CLASS_NAME = "de.ble1st.warden.sentinel.SentinelHeartbeatService"

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Watchdog-Sicherheitsnetz
 * kommt in v1 mit" — Port aus dem ConneXias-Framework-Quellprojekt
 * (`warden-app/.../sentinel/SentinelDeathWatchdog.kt`), Package-Namen angepasst. Bindet an
 * Sentinels [SentinelHeartbeatService][de.ble1st.warden.sentinel.SentinelHeartbeatService] (fremde
 * APK, expliziter `Intent` — dieselbe Vertrauensrichtung wie [SentinelLockdownEngager], hier aber
 * ausnahmsweise Warden selbst als Binder-**Client**, nicht als Host) und registriert einen
 * `IBinder.DeathRecipient` auf dem zurückgelieferten Binder — kernelvermittelt, Doze-unabhängig,
 * feuert sofort, wenn Sentinels Prozess stirbt (im Gegensatz zu einem periodischen Poll).
 *
 * `SentinelHeartbeatService` ist `de.ble1st.warden.sentinel.permission.ENGAGE`-geschützt
 * (`sentinel/src/main/AndroidManifest.xml`) — ohne Wardens `<uses-permission>` auf dieselbe
 * Permission (`app/src/main/AndroidManifest.xml`) scheitert `bindService()` an einer
 * `SecurityException`, unten abgefangen (derselbe Fund wie im Quellprojekt, Security-Review S3:
 * ein still sterbender Bus-Client ist selbst ein Fund wert, deshalb laut geloggt statt nur
 * verschluckt).
 */
class SentinelDeathWatchdog(
    private val context: Context,
    private val logStore: HashChainLogStore,
    private val onEscalate: () -> Unit,
) {
    private var connection: ServiceConnection? = null
    private var boundBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val deathTimestampsMillis = mutableListOf<Long>()

    /** Rein diagnostisch — ob aktuell ein Binder gebunden ist (für Tests/Statusanzeigen). */
    fun isBound(): Boolean = boundBinder != null

    /** **Live-Drill-Fund (2026-08-26):** ruft `stop()` zuerst auf statt nur `if (connection !=
     * null) return` — [SentinelWatchdogController] ist ein app-weites `by lazy`-Singleton, dessen
     * `watchdog`-Feld nie neu erzeugt wird; `connection` wird ausschließlich von [stop] (regulärer
     * PIN-Exit-Pfad in `SentinelSignalReceiver`, oder [SentinelWatchdogController.escalate]
     * selbst) zurückgesetzt. Endet ein Lockdown-Zyklus auf jedem anderen Weg — Sentinels Prozess
     * stirbt/wird gekillt, **exakt das Bedrohungsmodell, gegen das dieser Watchdog schützen
     * soll** — blieb `connection` non-null und jeder folgende `arm()`-Aufruf verließ diese Methode
     * sofort ohne neu zu binden: der Watchdog war für den Rest der Warden-Prozess-Lebensdauer
     * lautlos wirkungslos. Empirisch auf dem physischen Testgerät bestätigt (drei echte `am
     * crash`-Abstürze nach einem vorherigen `am force-stop`-Zyklus lösten keine Eskalation aus —
     * identischer `BinderProxy`-Hash über alle drei Tode hinweg bewies eine nie erneuerte
     * Zombie-Bindung). `stop()` selbst ist bereits idempotent (No-Op bei `binder == null`), macht
     * `start()` also sicher wiederholt aufrufbar — dieselbe "jederzeit risikolos wiederholbar"-
     * Idempotenz-Konvention wie bei jedem [de.ble1st.warden.domain.registry.Safeguard]. */
    fun start() {
        stop()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) return
                boundBinder = service
                val recipient = IBinder.DeathRecipient { onSentinelDeath() }
                deathRecipient = recipient
                try {
                    service.linkToDeath(recipient, 0)
                } catch (e: Exception) {
                    logStore.append(Log.ERROR, TAG, "linkToDeath fehlgeschlagen: $e")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundBinder = null
            }
        }
        val intent = Intent().apply {
            component = ComponentName(SENTINEL_PACKAGE_NAME, SENTINEL_HEARTBEAT_SERVICE_CLASS_NAME)
        }
        val bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            logStore.append(Log.ERROR, TAG, "bindService(SentinelHeartbeatService) fehlgeschlagen (SecurityException): $e")
            false
        }
        if (bound) {
            connection = conn
        } else {
            logStore.append(Log.ERROR, TAG, "bindService(SentinelHeartbeatService) lieferte false — kein Watchdog aktiv")
        }
    }

    fun stop() {
        val binder = boundBinder
        val recipient = deathRecipient
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        boundBinder = null
        deathRecipient = null
    }

    private fun onSentinelDeath() {
        val now = System.currentTimeMillis()
        deathTimestampsMillis.add(now)
        logStore.append(Log.WARN, TAG, "Sentinel-Prozess gestorben (linkToDeath), deaths=${deathTimestampsMillis.size}")
        if (SentinelWatchdogDecision.shouldEscalate(deathTimestampsMillis, now)) {
            logStore.append(Log.ERROR, TAG, "Eskalation: 3 Deaths in 60s — Lock-Task-Whitelist wird zurückgezogen")
            onEscalate()
        }
    }

    private companion object {
        const val TAG = "SentinelWatchdog"
    }
}
