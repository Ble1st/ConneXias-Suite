package de.ble1st.warden.domain.appmanagement

/**
 * "Detaillierte Permission-Audit-Reports" (2026-08-25, Feature-Ideenliste Punkt 22: "Warnung vor
 * Apps mit zu vielen Rechten"). Reine Schwellenwert-Entscheidung, dieselbe Decision/Executor-
 * Trennung wie überall sonst im Projekt — [de.ble1st.warden.appmanagement.PermissionAuditScanner]
 * zählt, hier wird nur noch verglichen.
 *
 * `THRESHOLD = 5`: kein aus einem Android-Dokument abgeleiteter Wert (Android definiert selbst
 * keinen "zu viele Rechte"-Schwellenwert), sondern eine bewusst gewählte, dokumentierte
 * Heuristik — eine Handvoll gefährlicher Rechte (Kamera, Standort, Kontakte, Mikrofon, SMS, …)
 * ist bei funktional breiten Apps (Messenger, Kamera-Apps) normal, eine deutlich größere Menge
 * bei einer einzelnen App ist der Signal-Fall, den dieses Feature laut Ideenliste eigentlich
 * anzeigen soll ("AVG, Norton, Kaspersky" als Vorbild — auch dort ein UI-Warnschwellenwert, keine
 * von Android vorgegebene Zahl).
 */
object PermissionAuditDecision {
    const val THRESHOLD = 5

    fun tooManyDangerousPermissions(dangerousPermissionCount: Int): Boolean = dangerousPermissionCount >= THRESHOLD
}
