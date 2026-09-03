package de.ble1st.warden.domain.clipboard

/**
 * Ein einzelnes, bereits ausgewertetes Cross-App-Zugriffsereignis (`docs/design-clipboard-guard.md`
 * Abschnitt 3.2.6/3.2.7). Anders als beim ursprünglich angenommenen Toast-Signal (Abschnitt 3.2.1)
 * trägt dieses Ereignis den tatsächlichen, gerade eingefügten Text — bewusste Konsequenz der
 * Nutzerentscheidung "voller Funktionsumfang mit expliziter UI-Aufklärung" (Abschnitt 5, Frage 4,
 * Option 3), nicht ein Versehen. [text] ist bereits auf [de.ble1st.warden.clipboard
 * .ClipboardAccessController.MAX_TEXT_LENGTH] gekürzt, bevor er hierher gelangt.
 */
data class ClipboardAccessEvent(
    val timestampMillis: Long,
    val packageName: String,
    /** Bestlabel zum Zeitpunkt der Erfassung — kann bei späterer Deinstallation der Quell-App
     * nicht mehr nachträglich aufgelöst werden, deshalb hier eingefroren statt live nachgeschlagen. */
    val appLabel: String,
    val text: String,
)
