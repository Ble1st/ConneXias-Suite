package de.ble1st.warden.domain.appmanagement

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22) — erkennt den Übergang
 * "war beim letzten Scan noch nicht aktiv, ist es jetzt" für Geräteadmin-/Bedienungshilfen-
 * Aktivierung. Dringlicher als die reine Deklarations-Erkennung
 * ([SuspiciousAppScanDecision.evaluate]s [SuspiciousSignal.EXTRA_DEVICE_ADMIN]/
 * [SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED]), die nur die Fähigkeit im Manifest sieht,
 * nicht ob sie gerade tatsächlich vom Nutzer (oder, im Missbrauchsfall, ohne dessen bewusste
 * Zustimmung) eingeschaltet wurde.
 *
 * Reine Mengendifferenz, aber mit einem wichtigen Sonderfall: [previouslyActive] `== null`
 * markiert "noch keine Historie vorhanden" (allererster Scan-Lauf, oder Historie-Cache verloren).
 * In diesem Fall wird **nichts** als "gerade aktiviert" gemeldet — sonst wäre bei jeder
 * Neuinstallation/jedem ersten Lauf jeder bereits länger aktive Admin/Bedienungshilfen-Dienst
 * (inklusive Wardens eigenem) fälschlich ein "gerade aktiviert"-Fund. Der Aufrufer
 * (`de.ble1st.warden.appmanagement.SuspiciousAppScanController`) persistiert [currentlyActive]
 * nach jedem Aufruf als neue Baseline für den nächsten Scan.
 */
object ActivationTransitionDecision {

    fun evaluate(previouslyActive: Set<String>?, currentlyActive: Set<String>): Set<String> =
        if (previouslyActive == null) emptySet() else currentlyActive - previouslyActive
}
