package de.ble1st.warden.registry

import de.ble1st.warden.domain.registry.RegistryReconcileAction
import de.ble1st.warden.domain.registry.RegistryReconcileDecision

/**
 * Meilenstein C.4 (Konzept Abschnitt 4/19): "Reconciliation bei `ACTION_LOCKED_BOOT_COMPLETED`:
 * Ist vs. Soll, re-applizieren, loggen." Vergleicht für jeden registrierten Safeguard den
 * zuletzt persistierten Soll-Zustand ([PersistentSafeguardRegistry.desiredState]) mit dem live
 * abgefragten Ist-Zustand (`isActive()`) und appliziert/revertiert divergente Einträge erneut.
 *
 * Bewusst kein `BroadcastReceiver` selbst — reine, direkt testbare Logik ohne Android-
 * Abhängigkeit über [PersistentSafeguardRegistry] hinaus. Die Boot-Anbindung
 * (`ACTION_LOCKED_BOOT_COMPLETED`) ist ein dünner Receiver in `:warden-app`
 * (`RegistryReconciliationReceiver`), der [reconcile] aufruft — analog zu
 * `WardenDeviceAdminReceiver`: dünne Android-Glue, Logik separat testbar.
 *
 * **Per-Eintrag statt Alles-oder-nichts:** ein fehlschlagender Safeguard darf die Korrektur der
 * übrigen, unabhängigen Sicherheits-Maßnahmen nicht verhindern — jeder Eintrag wird einzeln
 * versucht; ein Fehler bei einem Eintrag bricht die Schleife nicht ab, sondern wird als
 * [RegistryCorrection.Failed] gemeldet.
 *
 * **Von Konstruktion her crashsicher/wiederaufsetzbar** (Meilenstein-Abnahme "Crash-während-
 * Apply simulieren"): jede einzelne Korrektur persistiert sofort über
 * [PersistentSafeguardRegistry] (C.3, `EnvelopeFile` atomar seit B.6). Ein Prozessabbruch mitten
 * in der Schleife hinterlässt nie einen undefinierten Zwischenzustand, nur noch nicht
 * abgearbeitete Einträge — ein erneuter [reconcile]-Lauf (nächster Boot oder Retry) findet
 * exakt diese übriggebliebenen Divergenzen wieder (bereits korrigierte Einträge sind dann schon
 * im Soll-Zustand und werden übersprungen) und korrigiert sie. Kein zusätzliches
 * Fortschritts-Tracking über den Registry-eigenen Soll-/Ist-Vergleich hinaus nötig.
 *
 * [neverWeaken] (analyse.md, 2. Durchgang, Mittel — "USB-Daten am gesperrten Gerät nach Boot
 * wieder an"): ids, für die diese Reconciliation eine Divergenz nur in die verstärkende Richtung
 * auflöst, s. [RegistryReconcileDecision]-Klassendoc für die volle Begründung.
 */
class RegistryReconciler(
    private val registry: PersistentSafeguardRegistry,
    private val neverWeaken: Set<String> = emptySet(),
    private val onCorrection: (RegistryCorrection) -> Unit = {},
) {

    /**
     * Geht alle registrierten ids durch. ids ohne bekannten Soll-Zustand (noch nie [apply]/
     * [revert] aufgerufen) werden übersprungen — es gibt nichts durchzusetzen. Liefert nur
     * tatsächliche Korrekturen/Fehlschläge zurück, keine Einträge, die bereits im Soll-Zustand
     * waren.
     */
    fun reconcile(): List<RegistryCorrection> {
        val corrections = mutableListOf<RegistryCorrection>()
        for (id in registry.registeredIds()) {
            val desired = registry.desiredState(id) ?: continue
            val correction = reconcileOne(id, desired)
            if (correction != null) {
                corrections += correction
                onCorrection(correction)
            }
        }
        return corrections
    }

    private fun reconcileOne(id: String, desired: Boolean): RegistryCorrection? {
        val actual = try {
            registry.isActive(id)
        } catch (e: Exception) {
            return RegistryCorrection.Failed(id, desired, e)
        }
        if (actual == desired) return null

        val action = RegistryReconcileDecision.actionFor(id, desired, actual, neverWeaken)
        if (action == RegistryReconcileAction.LEAVE_UNTOUCHED) return null

        return try {
            if (action == RegistryReconcileAction.APPLY) registry.apply(id) else registry.revert(id)
            RegistryCorrection.Applied(id, desired)
        } catch (e: Exception) {
            RegistryCorrection.Failed(id, desired, e)
        }
    }
}

/** Ergebnis eines einzelnen Reconciliation-Schritts für eine `id` — nur erzeugt, wenn Soll- und
 * Ist-Zustand tatsächlich divergierten. */
sealed class RegistryCorrection {
    abstract val id: String
    abstract val desired: Boolean

    /** Divergenz erfolgreich behoben. */
    data class Applied(override val id: String, override val desired: Boolean) : RegistryCorrection()

    /** Divergenz erkannt, aber Korrektur (oder schon die Ist-Zustand-Abfrage) fehlgeschlagen —
     * betrifft nur diese eine `id`, andere Einträge werden trotzdem weiter bearbeitet. */
    data class Failed(override val id: String, override val desired: Boolean, val cause: Throwable) :
        RegistryCorrection()
}
