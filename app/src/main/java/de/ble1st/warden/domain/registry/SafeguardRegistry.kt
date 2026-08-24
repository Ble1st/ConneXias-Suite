package de.ble1st.warden.domain.registry

/**
 * In-Memory-Verwaltung registrierter [Safeguard]-Einträge (Meilenstein C.1, Konzept Abschnitt 4).
 * Hält für jeden registrierten Eintrag den **Soll**-Zustand (was zuletzt erfolgreich per [apply]
 * bzw. [revert] angefordert wurde) — der **Ist**-Zustand kommt immer live von
 * `Safeguard.isActive()`, nie aus dieser Verwaltung (Konzept Abschnitt 3: "DPM-Restrictions:
 * Wahrheit im System, keine eigene Speicherung").
 *
 * Bleibt bewusst framework-frei und kennt keine Persistenz selbst — [desiredStateSnapshot] und
 * [restoreDesiredState] sind die reinen Ein-/Ausgänge dafür, die eigentliche `EnvelopeFile`-
 * Anbindung (Meilenstein C.3) lebt als `PersistentSafeguardRegistry`/`SafeguardRegistryStore`
 * in `:core:data`, weil sie Android/Keystore braucht. Ebenso ohne Boot-Reconciliation (C.4).
 *
 * [apply]/[revert] aktualisieren den gemerkten Soll-Zustand nur, wenn der zugrundeliegende
 * `Safeguard`-Aufruf **nicht** wirft — schlägt er fehl, bleibt der zuvor bekannte Soll-Zustand
 * unverändert stehen und die Exception propagiert unverändert nach oben (Fail-Safe,
 * Invariante 6: ein fehlgeschlagener Befehl darf nie fälschlich als "erledigt" gelten).
 */
class SafeguardRegistry {

    private val safeguards = mutableMapOf<String, Safeguard>()
    private val desiredState = mutableMapOf<String, Boolean>()

    /**
     * Registriert einen Safeguard. Wirft bei doppelter `id` — ein stiller Überschreiber wäre ein
     * Bug, kein gültiger Zustand: zwei Instanzen unter derselben id wären nicht mehr eindeutig
     * einer künftigen Persistenz-/Log-Zeile zuzuordnen (Meilenstein C.3/B.6).
     */
    @Synchronized
    fun register(safeguard: Safeguard) {
        require(safeguard.id !in safeguards) { "Safeguard mit id \"${safeguard.id}\" bereits registriert" }
        safeguards[safeguard.id] = safeguard
    }

    /** Ruft `Safeguard.apply()` auf und merkt sich den Soll-Zustand `true` — s. Klassendoc. */
    @Synchronized
    fun apply(id: String) {
        safeguardFor(id).apply()
        desiredState[id] = true
    }

    /** Ruft `Safeguard.revert()` auf und merkt sich den Soll-Zustand `false` — s. Klassendoc. */
    @Synchronized
    fun revert(id: String) {
        safeguardFor(id).revert()
        desiredState[id] = false
    }

    /** Ist-Zustand, live von der Quelle der Wahrheit — s. Klassendoc. */
    @Synchronized
    fun isActive(id: String): Boolean = safeguardFor(id).isActive()

    /**
     * Zuletzt erfolgreich angeforderter Soll-Zustand, oder `null`, wenn seit der Registrierung
     * weder [apply] noch [revert] für diese `id` aufgerufen wurde.
     */
    @Synchronized
    fun desiredState(id: String): Boolean? {
        safeguardFor(id) // wirft NoSuchElementException, wenn id nicht registriert ist
        return desiredState[id]
    }

    /** Alle registrierten ids, z. B. für die künftige Boot-Reconciliation (C.4). */
    @Synchronized
    fun registeredIds(): Set<String> = safeguards.keys.toSet()

    /**
     * Momentaufnahme des aktuellen Soll-Zustands aller registrierten Einträge — die Ausgabe für
     * die Persistenz (Meilenstein C.3, `SafeguardRegistryStore` in `:core:data`). Enthält nur
     * ids, für die bereits [apply]/[revert] aufgerufen wurde (s. [desiredState]-Doc).
     */
    @Synchronized
    fun desiredStateSnapshot(): Map<String, Boolean> = desiredState.toMap()

    /**
     * Übernimmt einen zuvor gesicherten Soll-Zustand (Meilenstein C.3) — setzt **nicht**
     * [apply]/[revert] auf den zugrundeliegenden Safeguards durch (das ist die künftige
     * Boot-Reconciliation, Meilenstein C.4, die Soll- und Ist-Zustand vergleicht und divergente
     * Einträge gezielt re-appliziert), sondern merkt sich nur den geladenen Soll-Zustand, exakt
     * wie ihn ein vorheriger [apply]/[revert]-Aufruf auch hinterlassen hätte.
     *
     * Einträge für nicht (mehr) registrierte ids werden stillschweigend ignoriert — das ist der
     * erwartete Fall, wenn ein Safeguard zwischen App-Versionen entfernt wurde (s.
     * `Safeguard.id`-Doc: "id darf sich nicht ändern, sonst verwaist der gespeicherte
     * Soll-Zustand" — genau dieser Fall, nicht wegdesignbar, nur dokumentiert).
     *
     * Muss nach allen [register]-Aufrufen laufen, sonst werden persistierte Einträge für zu
     * diesem Zeitpunkt noch unregistrierte ids fälschlich verworfen.
     */
    @Synchronized
    fun restoreDesiredState(persisted: Map<String, Boolean>) {
        for ((id, desired) in persisted) {
            if (id in safeguards) {
                desiredState[id] = desired
            }
        }
    }

    private fun safeguardFor(id: String): Safeguard =
        safeguards[id] ?: throw NoSuchElementException("kein Safeguard mit id \"$id\" registriert")
}
