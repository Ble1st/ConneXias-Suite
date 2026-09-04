package de.ble1st.warden.domain.pin

/**
 * "Lockdown-Auslöse-Profil" (2026-08-27) — nur unter [LockdownTriggerProfile.STRICT] konsultiert
 * (s. `de.ble1st.warden.pin.WardenLockTaskDrillFreshnessGate`): eine einmalig bestätigte
 * Notruf-Drill-Bestätigung (`de.ble1st.warden.pin.WardenLockTaskDrillStorage`) soll unter dem
 * strengsten Profil nicht beliebig lange gelten. STANDARD/FAST behalten das bisherige
 * "einmal bestätigt, für immer gültig"-Verhalten unverändert (kein Aufruf dieser Funktion dort).
 */
object WardenLockTaskDrillFreshnessDecision {

    /** 30 Tage — konservativ gewählt (Notruf-Drill ist ein physischer Test, kein täglicher
     * Vorgang), Konstante statt fest verdrahtet, dieselbe "Schwellen sind Deployment-
     * Entscheidung, keine Algorithmus-Eigenschaft"-Haltung wie
     * [de.ble1st.warden.domain.bus.RateLimiter]. */
    const val DEFAULT_MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * `false`, solange nie bestätigt ([confirmedAtMillis] `== null`). Ein `confirmedAtMillis` in
     * der Zukunft (Uhrzeit-Sprung rückwärts nach der Bestätigung) gilt bewusst weiterhin als
     * frisch — kein Grund, eine tatsächlich soeben erfolgte Bestätigung wegen einer
     * Uhr-Anomalie abzulehnen.
     */
    fun isFresh(
        confirmedAtMillis: Long?,
        nowMillis: Long,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): Boolean {
        if (confirmedAtMillis == null) return false
        return nowMillis - confirmedAtMillis <= maxAgeMillis
    }
}
