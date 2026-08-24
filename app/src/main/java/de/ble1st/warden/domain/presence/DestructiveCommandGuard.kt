package de.ble1st.warden.domain.presence

/**
 * Meilenstein F.4 (Konzept Abschnitt 19): "Dry-Run-Modus nur im testOnly-Build;
 * `BuildConfig.DEBUG` schaltet destruktive Kommandos hart ab." Zwei unabhängige, absichtlich
 * unbedingte Prüfungen — reine Werte-Logik, damit der Aufrufer (`:warden-app`) sie mit den
 * echten `BuildConfig`/`ApplicationInfo`-Werten füttert, ohne diese Klasse selbst an Android zu
 * binden.
 *
 * **Unbedingt, nicht konfigurierbar:** ein Debug-Build kann [isExecutionAllowed] nicht durch
 * irgendeinen Parameter doch noch auf `true` bringen — dieselbe "strukturell erzwungen statt nur
 * konfiguriert"-Haltung wie bei `CapabilityMatrix.NEVER_ON_BUS` (E.6, Konzept 2b/(5): "getrennte
 * Vertrauensdomänen, kein Vermischen").
 */
object DestructiveCommandGuard {
    /** `false`, sobald `isDebugBuild` (i. d. R. `BuildConfig.DEBUG`) `true` ist — unabhängig
     * davon, was sonst noch zutrifft. */
    fun isExecutionAllowed(isDebugBuild: Boolean): Boolean = !isDebugBuild

    /** Dry-Run (Aktion wird nur simuliert/geloggt, nie ausgeführt) ist ausschließlich in einem
     * `testOnly`-signierten Gesamt-Build erlaubt (Konzept Abschnitt 8/2b/(5)). */
    fun isDryRunAllowed(isTestOnlyBuild: Boolean): Boolean = isTestOnlyBuild
}
