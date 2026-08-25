package de.ble1st.warden.appmanagement

/**
 * Eine Zeile im Permission-Audit-Bericht (Feature-Ideenliste Punkt 22). [dangerousPermissions]/
 * [specialPermissions] sind die tatsächlichen, im Manifest deklarierten Rechtenamen (nicht nur
 * die Anzahl) — die UI zeigt sie einzeln an, [SuspiciousAppScanController]/
 * [DangerousPermissionRevoker] nutzen dieselbe Liste für den Auto-Quarantäne-Pfad.
 * [tooManyDangerousPermissions] ist bereits hier, nicht erst in der UI berechnet — ein Kriterium,
 * ein Ort, s. [PermissionAuditScanner]-Klassendoc für die Schwellenwert-Begründung.
 */
data class PermissionAuditInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val dangerousPermissions: List<String>,
    val specialPermissions: List<String>,
    val tooManyDangerousPermissions: Boolean,
)
