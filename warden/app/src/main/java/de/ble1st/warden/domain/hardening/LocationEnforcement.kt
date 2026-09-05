package de.ble1st.warden.domain.hardening

/**
 * Ob Warden die Standortermittlung erzwingt (2026-09-05, Tier-2 der DPC-Recherche).
 *
 * **Warum das eine Sicherheitsfunktion ist und keine Bequemlichkeit:**
 * [de.ble1st.warden.antitheft.AntiTheftLastLocationReader] schreibt beim Diebstahlalarm den zuletzt
 * bekannten Standort ins Audit-Log — das ist die einzige forensische Spur, die Warden von einem
 * entwendeten Gerät überhaupt bekommen kann (es gibt bewusst keinen Fernzugriff). Ist die Ortung
 * aus, ist diese Spur leer. Und Ortung abzuschalten ist nach dem SIM-Ziehen der zweite
 * Standardgriff nach einem Diebstahl. Als Device Owner kann Warden beides verhindern.
 *
 * **Warum der mittlere Wert existiert:** [ERZWINGEN] schaltet die Ortung ein, lässt den Nutzer
 * sie aber wieder ausschalten — sinnvoll für alle, die den Schalter im Alltag selbst bedienen
 * wollen und nur verhindern möchten, dass er *versehentlich* aus bleibt.
 * [ERZWINGEN_UND_SPERREN] nimmt ihn zusätzlich aus der Hand (`DISALLOW_CONFIG_LOCATION`) — wirksam
 * gegen einen Dieb, aber eben auch gegen den Besitzer selbst, der die Ortung dann nicht mehr aus
 * Datenschutzgründen abschalten kann. Diese Abwägung gehört dem Nutzer, nicht Warden; deshalb ein
 * Auswahlmenü statt eines einzelnen Schalters.
 */
enum class LocationEnforcement(val label: String) {
    /** Warden fasst die Ortungseinstellung nicht an. */
    AUS("Aus — Ortung nicht beeinflussen"),

    /** Ortung einschalten, Nutzer darf sie weiterhin ändern. */
    ERZWINGEN("Ortung einschalten (weiterhin änderbar)"),

    /** Ortung einschalten und die Einstellung sperren. */
    ERZWINGEN_UND_SPERREN("Ortung einschalten und sperren"),
    ;

    val enablesLocation: Boolean get() = this != AUS
    val locksSetting: Boolean get() = this == ERZWINGEN_UND_SPERREN

    companion object {
        val DEFAULT: LocationEnforcement = AUS
    }
}
