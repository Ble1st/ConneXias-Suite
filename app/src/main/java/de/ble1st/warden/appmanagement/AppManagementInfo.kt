package de.ble1st.warden.appmanagement

/**
 * Eine Zeile in der App-Verwaltungs-Liste. Anders als im ConneXias-Framework-Quellprojekt (dort
 * `core.ipc.AppManagementInfo`, `Parcelable` für den AIDL-Cross-APK-Transport über Concord) eine
 * einfache Datenklasse ohne `Parcelable` — [de.ble1st.warden.bus.ConcordBus] ist In-Process, es
 * gibt keinen Binder-Transport mehr zu bedienen.
 *
 * [protected] spiegelt [de.ble1st.warden.domain.appmanagement.AppFreezeGuard.isProtected] — die
 * UI nutzt es nur, um den Schalter für ein Paket auszugrauen, das `setAppFrozen` ohnehin ablehnen
 * würde; die eigentliche Durchsetzung bleibt in [AppManagementController] (UI-Hinweise sind nie
 * die Autorisierungsgrenze, dasselbe Prinzip wie überall sonst in diesem Projekt).
 */
data class AppManagementInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val frozen: Boolean,
    val protected: Boolean,
)
