package de.ble1st.warden.domain.bus

/**
 * Reine, framework-freie Autorisierungsentscheidung für Concord (s. [de.ble1st.warden.bus.ConcordBus]).
 * Reduzierte Fassung des Quellprojekts (dort eine Zeile pro Cross-APK-Rolle Herald/Sentinel/
 * Barbican) — [Role.OWNER] bleibt Wardens eigene UI im Hauptprozess; [Role.BARBICAN] kam
 * 2026-08-31 dazu (Prozess-Split, `docs/design-barbican-prozess-childvpn.md`), bekommt aber
 * bewusst nur [BusCommand.EVENT_REPORT] — kleinstmögliches Privileg für den einzigen echten
 * Cross-Process-Aufrufer, kein READ/NON_DESTRUCTIVE_SWITCH-Zugriff über den Bus.
 *
 * **Strukturell erzwungen statt nur tabelliert:** [NEVER_ON_BUS] wird *vor* jedem Tabellen-Lookup
 * geprüft — ein künftiger Tippfehler beim Erweitern von [ROLE_CAPABILITIES] kann [BusCommand.DESTRUCTIVE]
 * dadurch nicht versehentlich freigeben, dieselbe Defensiv-Idee wie im Quellprojekt. Gilt
 * unverändert für jede Rolle, auch [Role.BARBICAN].
 */
object CapabilityMatrix {

    private val NEVER_ON_BUS = setOf(BusCommand.DESTRUCTIVE)

    private val ROLE_CAPABILITIES: Map<Role, Set<BusCommand>> = mapOf(
        Role.OWNER to setOf(BusCommand.READ, BusCommand.NON_DESTRUCTIVE_SWITCH, BusCommand.LOG_ACCESS),
        Role.BARBICAN to setOf(BusCommand.EVENT_REPORT),
    )

    fun isAllowed(role: Role, command: BusCommand): Boolean {
        if (command in NEVER_ON_BUS) return false
        return ROLE_CAPABILITIES[role]?.contains(command) ?: false
    }
}
