package de.ble1st.files.data.localshare

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface LocalShareStatus {
    data object Stopped : LocalShareStatus
    data class Running(val directory: File, val url: String) : LocalShareStatus
    data class Failed(val message: String) : LocalShareStatus
}

/**
 * In-Prozess-Statuskanal zwischen [LocalShareService] und [de.ble1st.files.ui.localshare
 * .LocalShareScreen] — derselbe StateFlow-Kanal wie [de.ble1st.files.data.fileops
 * .FileOperationQueue] für Kopier-/Verschiebe-Jobs, hier nur mit dem einfacheren Zustand
 * "läuft nicht / läuft mit dieser URL / zuletzt fehlgeschlagen" statt Fortschritts-Prozenten.
 */
object LocalShareState {
    private val _status = MutableStateFlow<LocalShareStatus>(LocalShareStatus.Stopped)
    val status: StateFlow<LocalShareStatus> = _status

    fun publish(status: LocalShareStatus) {
        _status.value = status
    }
}

/**
 * Liefert die eigene IPv4-Adresse im aktuell aktiven WLAN/Hotspot — bewusst über
 * `NetworkInterface` statt `WifiManager.getConnectionInfo()`, weil Letzteres nur die
 * WLAN-*Client*-Adresse liefert, nicht die eigene Adresse im Access-Point-Modus (Hotspot).
 * `NetworkInterface` sieht in beiden Fällen dieselbe aktive Schnittstelle (`wlan0`/`ap0`/…), ohne
 * dass zwischen den beiden Betriebsarten unterschieden werden müsste.
 */
object LocalIpAddress {
    fun get(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
