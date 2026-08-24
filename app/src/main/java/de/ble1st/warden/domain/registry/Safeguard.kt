package de.ble1st.warden.domain.registry

/**
 * Eine einzelne, idempotente OS-Maßnahme (Konzept Abschnitt 4: "Jede OS-Maßnahme als idempotente
 * Einheit apply()/revert()/isActive() (Command-Pattern, einzeln testbar)"). Konkrete
 * Implementierungen (`DISALLOW_*`-Restrictions, Kamera-Sperre, Screen-Capture, ... — Meilenstein
 * C.2) brauchen den `DevicePolicyManager` und leben deshalb in `:core:data`; dieses Interface
 * selbst bleibt bewusst framework-frei (`:core:domain`), damit [SafeguardRegistry] als reine
 * JVM-Logik ohne Robolectric/Instrumentierung testbar ist.
 *
 * **Idempotenz ist Vertragsbestandteil, keine Empfehlung:** [apply] muss beliebig oft
 * hintereinander aufrufbar sein, auch wenn die Maßnahme bereits aktiv ist, ohne Fehler oder
 * doppelte Nebenwirkungen. Gleiches gilt für [revert], wenn die Maßnahme bereits inaktiv ist.
 * Das ist Voraussetzung für die künftige Boot-Reconciliation (Meilenstein C.4), die [apply] auf
 * jeden Eintrag anwendet, dessen Soll-Zustand "aktiv" ist — unabhängig davon, ob der Ist-Zustand
 * schon passt.
 *
 * **Zustandsprüfung (Konzept Abschnitt 3):** [isActive] fragt die tatsächliche Quelle der
 * Wahrheit ab (i. d. R. den `DevicePolicyManager`), nie einen zwischengespeicherten Wert — die
 * [SafeguardRegistry] speichert nur den *Soll*-Zustand, nie den Ist-Zustand.
 */
interface Safeguard {
    /**
     * Eindeutiger, stabiler Bezeichner. Wird ab Meilenstein C.3 persistiert und lässt sich ab B.6
     * loggen — darf sich zwischen App-Versionen nicht ändern, sonst verwaist der gespeicherte
     * Soll-Zustand für bestehende Einträge.
     */
    val id: String

    /** Wendet die Maßnahme an. Muss idempotent sein (s. Klassendoc). */
    fun apply()

    /** Macht die Maßnahme rückgängig. Muss idempotent sein (s. Klassendoc). */
    fun revert()

    /** Tatsächlicher Ist-Zustand, live abgefragt (s. Klassendoc) — nie gecacht. */
    fun isActive(): Boolean
}
