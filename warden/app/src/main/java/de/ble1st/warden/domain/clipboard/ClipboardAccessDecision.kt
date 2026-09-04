package de.ble1st.warden.domain.clipboard

/**
 * Reine Entscheidungslogik für [de.ble1st.warden.clipboard.ClipboardAccessibilityService]
 * (`docs/design-clipboard-guard.md` Abschnitt 3.2.6/3.2.7, "Signal 2"). Framework-Seite liefert nur
 * bereits aus dem `AccessibilityEvent` extrahierte Rohwerte, keine Android-Typen hier.
 *
 * **Vier unabhängige Filter, alle vor jeder Erfassung geprüft** — dieselbe "strukturell erzwungen"-
 * Haltung wie `SuspiciousAppScanDecision`/`CapabilityMatrix.NEVER_ON_BUS`:
 * - [MONITORING_DISABLED]: Nutzer-Opt-in ([de.ble1st.warden.clipboard.ClipboardGuardStorage
 *   .isCrossAppMonitoringEnabled]) — zusätzlich zur Systemebene (Bedienungshilfe muss ohnehin
 *   manuell aktiviert sein), Verteidigung in der Tiefe, falls die App-Präferenz nach einem
 *   Deaktivieren hinter der Systemeinstellung zurückbleibt.
 * - [OWN_PACKAGE]: Wardens eigenes Package — würde sonst seine eigene UI (PIN-Eingabe, Einstellungs-
 *   Textfelder) mitlesen. Deckt sich mit `SuspiciousAppScanDecision.ownPackageName`-Ausschluss,
 *   hier aber als eigener Filter nötig, weil dieser Pfad nicht über den Verdachtsscanner läuft.
 * - [PASSWORD_FIELD]: `AccessibilityNodeInfo.isPassword` — Androids eigener Schutzmechanismus für
 *   Passwortfelder (Text erscheint bei aktiver Bedienungshilfe standardmäßig nur maskiert, s.
 *   Framework-Seiten-Klassendoc), hier zusätzlich als expliziter Ausschluss, nicht nur verlassen
 *   auf die zufällige Maskierung.
 * - [NOT_PASTE_LIKE]: **Kernabgrenzung gegenüber reinem Tastatur-Mitschnitt.** Normales Tippen löst
 *   `TYPE_VIEW_TEXT_CHANGED` mit `addedCount=1` pro Zeichen aus; ein Einfügevorgang fügt mehrere
 *   Zeichen in einem einzigen Ereignis hinzu. [MIN_BURST_CHARS] grenzt die Erfassung auf
 *   paste-artige Bursts ein — eine Heuristik, keine Garantie (schnelles Tippen, Autovervollständigung
 *   oder IME-Vorschläge können ebenfalls mehrere Zeichen auf einmal einfügen), aber eine echte
 *   technische Einschränkung des Umfangs, kein reines Versprechen im Code (s. Konzept 3.2.6).
 */
object ClipboardAccessDecision {

    const val MIN_BURST_CHARS = 3

    sealed class Action {
        data class Capture(val text: String) : Action()
        data class Ignore(val reason: IgnoreReason) : Action()
    }

    enum class IgnoreReason { MONITORING_DISABLED, OWN_PACKAGE, PASSWORD_FIELD, EMPTY_TEXT, NOT_PASTE_LIKE }

    fun evaluate(
        monitoringEnabled: Boolean,
        packageName: String?,
        ownPackageName: String,
        isPassword: Boolean,
        addedCount: Int,
        text: String,
    ): Action = when {
        !monitoringEnabled -> Action.Ignore(IgnoreReason.MONITORING_DISABLED)
        packageName == null || packageName == ownPackageName -> Action.Ignore(IgnoreReason.OWN_PACKAGE)
        isPassword -> Action.Ignore(IgnoreReason.PASSWORD_FIELD)
        text.isBlank() -> Action.Ignore(IgnoreReason.EMPTY_TEXT)
        addedCount < MIN_BURST_CHARS -> Action.Ignore(IgnoreReason.NOT_PASTE_LIKE)
        else -> Action.Capture(text)
    }
}
