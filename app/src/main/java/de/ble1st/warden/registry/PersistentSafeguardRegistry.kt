package de.ble1st.warden.registry

import de.ble1st.warden.domain.registry.Safeguard
import de.ble1st.warden.domain.registry.SafeguardRegistry

/**
 * Meilenstein C.3: verdrahtet [SafeguardRegistry] (`:core:domain`, rein in-memory) mit
 * [SafeguardRegistryStore] (`:core:data`, Envelope-Persistenz) — [apply]/[revert] persistieren
 * automatisch nach jeder erfolgreichen Änderung, analog zu `LocalRingTree`/`HashChainLogStore`
 * (B.5/B.6: Persistenz passiert bei jeder Mutation, nicht als separater, vergessbarer Schritt).
 *
 * Persistiert `store.save(...)` erst **nach** `registry.apply()`/`registry.revert()` erfolgreich
 * war — schlägt der DPM-Aufruf fehl, propagiert die Exception unverändert (Fail-Safe,
 * Invariante 6, s. `SafeguardRegistry`-Doc) und `save()` läuft gar nicht erst. Schlägt
 * umgekehrt `save()` fehl, **nachdem** der DPM-Aufruf bereits erfolgreich war, propagiert auch
 * diese Exception unverändert; der zugrundeliegende Systemzustand hat sich dennoch geändert.
 * Das ist bewusst kein Rollback-Fall: ein möglicher Soll-Zustand-Drift durch diesen seltenen
 * Fehlerfall zeigt bei der künftigen Reconciliation (C.4) höchstens in die *sichere* Richtung
 * — ein zu alter persistierter „aktiv"-Zustand führt im schlimmsten Fall zu einem erneuten
 * `apply()`, nie zu einem übersehenen, tatsächlich noch nötigen `revert()`.
 */
class PersistentSafeguardRegistry(
    private val registry: SafeguardRegistry,
    private val store: SafeguardRegistryStore,
) {

    fun register(safeguard: Safeguard) = registry.register(safeguard)

    /**
     * Lädt den zuletzt persistierten Soll-Zustand und übernimmt ihn in die zugrunde liegende
     * Registry (`SafeguardRegistry.restoreDesiredState`) — **muss nach allen [register]-
     * Aufrufen laufen**, sonst werden persistierte Einträge für zu diesem Zeitpunkt noch
     * unregistrierte ids fälschlich verworfen (s. `restoreDesiredState`-Doc). Ruft **nicht**
     * `apply()`/`revert()` auf den Safeguards auf — das bleibt der künftigen Boot-Reconciliation
     * (Meilenstein C.4) vorbehalten, die Soll- und Ist-Zustand vergleicht.
     */
    fun load() {
        registry.restoreDesiredState(store.load())
    }

    fun apply(id: String) {
        registry.apply(id)
        store.save(registry.desiredStateSnapshot())
    }

    fun revert(id: String) {
        registry.revert(id)
        store.save(registry.desiredStateSnapshot())
    }

    fun isActive(id: String): Boolean = registry.isActive(id)

    fun desiredState(id: String): Boolean? = registry.desiredState(id)

    fun registeredIds(): Set<String> = registry.registeredIds()
}
