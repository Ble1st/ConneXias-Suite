package de.ble1st.warden.domain.bus

/**
 * Reine, framework-freie Autorisierungsentscheidung für Concord (s. [de.ble1st.warden.bus.ConcordBus]).
 * Reduzierte Fassung des Quellprojekts (dort eine Zeile pro Cross-APK-Rolle Herald/Sentinel/
 * Barbican) — hier gibt es nur noch [Role.OWNER], den einzigen verbleibenden In-Process-Aufrufer.
 *
 * **Strukturell erzwungen statt nur tabelliert:** [NEVER_ON_BUS] wird *vor* jedem Tabellen-Lookup
 * geprüft — ein künftiger Tippfehler beim Erweitern von [ROLE_CAPABILITIES] kann [BusCommand.DESTRUCTIVE]
 * dadurch nicht versehentlich freigeben, dieselbe Defensiv-Idee wie im Quellprojekt.
 */
object CapabilityMatrix {

    private val NEVER_ON_BUS = setOf(BusCommand.DESTRUCTIVE)

    private val ROLE_CAPABILITIES: Map<Role, Set<BusCommand>> = mapOf(
        Role.OWNER to setOf(BusCommand.READ, BusCommand.NON_DESTRUCTIVE_SWITCH, BusCommand.LOG_ACCESS),
    )

    fun isAllowed(role: Role, command: BusCommand): Boolean {
        if (command in NEVER_ON_BUS) return false
        return ROLE_CAPABILITIES[role]?.contains(command) ?: false
    }
}
