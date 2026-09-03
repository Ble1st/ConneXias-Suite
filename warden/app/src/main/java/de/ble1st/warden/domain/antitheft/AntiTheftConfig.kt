package de.ble1st.warden.domain.antitheft

/**
 * Soll-Zustand für "Diebstahlschutz-Alarm" (2026-09-03, Ideenliste Punkte 3+4) — zwei unabhängig
 * schaltbare Auslöser statt einer gemeinsamen Reaktions-Enum wie bei
 * [de.ble1st.warden.domain.wifitrust.WifiTrustReaction]/`CellSecurityReaction`: hier gibt es keine
 * Abstufung "nur melden"/"stärker reagieren" — der einzige Zweck dieses Features ist der laute
 * Alarm selbst, eine "nur melden"-Variante wäre kein Diebstahlschutz mehr.
 */
data class AntiTheftConfig(
    val motionAlarmEnabled: Boolean = false,
    val chargerAlarmEnabled: Boolean = false,
) {
    val isAnyEnabled: Boolean get() = motionAlarmEnabled || chargerAlarmEnabled

    companion object {
        val DISABLED = AntiTheftConfig()
    }
}
