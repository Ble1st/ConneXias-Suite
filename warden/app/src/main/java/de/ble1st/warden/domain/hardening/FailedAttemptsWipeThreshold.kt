package de.ble1st.warden.domain.hardening

/**
 * OS-seitiges Löschen nach N Fehlversuchen am Sperrbildschirm
 * (`DevicePolicyManager.setMaximumFailedPasswordsForWipe`, 2026-09-05, Tier-2 der DPC-Recherche).
 *
 * **Das steht in ausdrücklichem Spannungsverhältnis zur Projektlinie und ist deshalb aus, bis es
 * jemand bewusst einschaltet.** Warden reagiert auf Bedrohungen grundsätzlich mit einem *Neustart
 * nach BFU* statt mit Löschen — `SensitiveAction.WIPE_DATA` ist bis heute absichtlich nur ein
 * protokollierender Stub ("der einzige der sechs, für den es keinen Weg zurück gibt"), und
 * [de.ble1st.warden.failedattempts.FailedAttemptsRebootController] zählt dieselben Fehlversuche und
 * *rebootet*. Dieses Auswahlmenü löscht wirklich, unwiderruflich, ohne Rückfrage.
 *
 * **Warum es trotzdem angeboten wird:** Wardens eigener Zähler hängt an Wardens eigenem
 * `onPasswordFailed`-Empfänger — er wirkt nur, solange Wardens Prozess und Empfänger leben. Die
 * OS-Variante setzt `system_server` durch, völlig unabhängig davon, ob Warden noch läuft,
 * abgestürzt ist oder deaktiviert wurde. Für wen das Gerät im Verlustfall lieber leer als lesbar
 * ist, ist das die einzige Stufe, die einen Angreifer überlebt, der Warden selbst ausschaltet.
 *
 * Die Schwellen sind bewusst hoch (ab 10): unter etwa zehn Versuchen sind versehentliche
 * Auslösungen durch Kinder, Hosentaschen-Eingaben oder schlicht einen vergessenen PIN realistisch —
 * bei einer nicht umkehrbaren Aktion ist das die falsche Seite zum Irren. Androids eigene
 * Untergrenze liegt niedriger; Warden bietet sie trotzdem nicht an.
 */
enum class FailedAttemptsWipeThreshold(val label: String, val attempts: Int) {
    /** Kein OS-seitiges Löschen. Default. */
    AUS("Aus — kein Löschen durch das System", 0),

    NACH_10("Nach 10 Fehlversuchen löschen", 10),
    NACH_20("Nach 20 Fehlversuchen löschen", 20),
    NACH_30("Nach 30 Fehlversuchen löschen", 30),
    ;

    val isEnabled: Boolean get() = this != AUS

    companion object {
        val DEFAULT: FailedAttemptsWipeThreshold = AUS

        /** Rückweg aus dem gespeicherten DPM-Wert — für den Fall, dass die Richtlinie von außen
         * (anderer Admin, Herstelleraufsatz) gesetzt wurde und Warden sie nur anzeigt. Ein Wert,
         * der zu keiner angebotenen Stufe passt, ergibt `null` statt einer gerundeten Näherung:
         * die UI zeigt dann "vom System gesetzt", statt eine Zahl zu behaupten, die nicht stimmt. */
        fun fromAttempts(attempts: Int): FailedAttemptsWipeThreshold? = when (attempts) {
            0 -> AUS
            else -> entries.firstOrNull { it.attempts == attempts }
        }
    }
}
