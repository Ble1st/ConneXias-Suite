package de.ble1st.warden.domain.bus

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Meilenstein E.8 (Konzept Abschnitt 2/19: "Rate-Limiting pro Aufrufer"). Gleitendes Zeitfenster
 * pro Schlüssel (i. d. R. `"$role:$packageName"`, s.
 * `de.ble1st.warden.bus.ConcordBus`) — kein globales Limit über alle
 * Aufrufer hinweg, ein lauter Aufrufer darf einen anderen nicht aussperren.
 *
 * `now` ist injizierbar (Default `System::currentTimeMillis`) — reiner JVM-Unit-Test ohne
 * Sleep/Timing-Flakiness möglich (Konzept-19-Testmuster wie überall sonst in `:core:domain`).
 *
 * Schwellenwerte sind bewusst nicht fest in dieser Klasse verdrahtet, sondern Konstruktor-
 * Parameter — analog zur 4a-Notiz "Schwellen konservativ, anhand der Logs kalibrieren": der
 * konkrete Wert für den Concord-Bus ist eine Deployment-Entscheidung, keine Eigenschaft des
 * Algorithmus.
 */
class RateLimiter(
    private val maxCallsPerWindow: Int,
    private val windowMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxCallsPerWindow > 0) { "maxCallsPerWindow muss positiv sein" }
        require(windowMillis > 0) { "windowMillis muss positiv sein" }
    }

    private val callTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /**
     * `true`, wenn `key` innerhalb des aktuellen Zeitfensters noch einen weiteren Aufruf frei
     * hat — verbucht den Aufruf in diesem Fall sofort (kein separater "reserve"-Schritt nötig).
     * Synchronisiert je Aufruf (nicht je Schlüssel) — bei der erwarteten Bus-Aufrufrate
     * (Menschen-/App-getriggerte Kommandos, keine Hot-Loop) unkritisch; ein Lock pro Schlüssel
     * wäre Komplexität ohne gemessenen Bedarf.
     */
    @Synchronized
    fun allow(key: String): Boolean {
        val current = now()
        val timestamps = callTimestamps.getOrPut(key) { ArrayDeque() }
        while (timestamps.isNotEmpty() && current - timestamps.peekFirst() > windowMillis) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxCallsPerWindow) return false
        timestamps.addLast(current)
        return true
    }
}
