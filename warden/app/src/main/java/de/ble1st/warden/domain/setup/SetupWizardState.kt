package de.ble1st.warden.domain.setup

/**
 * Die Schritte des Ersteinrichtungs-Assistenten, in der Reihenfolge, in der sie sinnvoll
 * abgearbeitet werden.
 *
 * [required] trennt "ohne das ist Warden wirkungslos" von "das braucht nicht jedes Gerät". Die
 * Unterscheidung ist keine Kosmetik: Ein Assistent, der alle fünf Schritte gleich dringlich
 * darstellt, drängt zu einer Kiosk-Einrichtung samt Notruf-Drill auf einem Gerät, das nie in den
 * Kiosk-Modus soll — und ein Drill, den man nur abhakt, um eine rote Liste grün zu bekommen, ist
 * genau die Bestätigung, die [de.ble1st.warden.pin.WardenLockTaskDrillStorage] nie bekommen soll.
 */
enum class SetupStep(val required: Boolean) {
    /** Ohne Device-Owner-Rechte kann Warden keinen einzigen Safeguard setzen. Nicht nachholbar
     * ohne Zurücksetzen des Geräts — deshalb reine Anzeige, kein Knopf. */
    DEVICE_OWNER(required = true),

    /** Ohne PIN ist WardenLock ein No-Op und jede presence-gated Aktion ungeschützt. */
    PIN(required = true),

    /** Ein Profil anzuwenden ist der schnellste Weg von "nichts gehärtet" zu "sinnvoll gehärtet"
     * — aber einzeln gesetzte Safeguards sind ein genauso gültiger Zustand, deshalb nicht
     * zwingend. */
    PROFILE(required = false),

    /** Nur nötig, wenn der Kiosk-Modus genutzt werden soll. */
    SENTINEL(required = false),

    /** Setzt Sentinel voraus und ist ausdrücklich eine Bestätigung über einen *tatsächlich
     * durchgeführten* Notruf-Test, kein Häkchen. */
    EMERGENCY_DRILL(required = false),
}

/**
 * Der zum Anzeigezeitpunkt aus dem echten Systemzustand gelesene Stand der fünf Schritte —
 * framework-frei, damit die Ableitungen unten ohne Android testbar bleiben (dieselbe
 * `domain/`-Trennung wie im Rest der App). Der Assistent führt bewusst keinen eigenen
 * Fortschrittsstand mit, s. [de.ble1st.warden.setup.SetupWizardStore].
 */
data class SetupWizardState(
    val isDeviceOwner: Boolean,
    val pinConfigured: Boolean,
    val profileApplied: Boolean,
    val sentinelInstalled: Boolean,
    val emergencyDrillConfirmed: Boolean,
) {
    fun isDone(step: SetupStep): Boolean = when (step) {
        SetupStep.DEVICE_OWNER -> isDeviceOwner
        SetupStep.PIN -> pinConfigured
        SetupStep.PROFILE -> profileApplied
        SetupStep.SENTINEL -> sentinelInstalled
        SetupStep.EMERGENCY_DRILL -> emergencyDrillConfirmed
    }

    /**
     * Der Notruf-Drill lässt sich nicht bestätigen, solange Sentinel gar nicht installiert ist —
     * ohne Sentinel gibt es keinen Kiosk, aus dem der Notruf heraus geprüft werden könnte. Der
     * Schritt wird dann als gesperrt dargestellt statt als "noch offen", damit niemand vergeblich
     * danach sucht.
     */
    fun isBlocked(step: SetupStep): Boolean =
        step == SetupStep.EMERGENCY_DRILL && !sentinelInstalled

    /** Erledigte Schritte, für die Fortschrittsanzeige. */
    val doneCount: Int get() = SetupStep.entries.count { isDone(it) }

    /** Alle Pflichtschritte erledigt — nur davon hängt ab, ob der Assistent das Gerät als
     * grundsätzlich abgesichert melden darf. */
    val requiredComplete: Boolean get() = SetupStep.entries.filter { it.required }.all { isDone(it) }

    /** Der erste noch offene Schritt, auf den der Assistent den Blick lenkt — `null`, wenn alles
     * erledigt ist. Gesperrte Schritte werden übersprungen: sie sind gerade nicht bearbeitbar. */
    val nextOpenStep: SetupStep?
        get() = SetupStep.entries.firstOrNull { !isDone(it) && !isBlocked(it) }
}
