package de.ble1st.warden.domain.registry

/**
 * Bündelt mehrere [Safeguard]-Einträge zu einem einzigen Registry-Eintrag (Meilenstein C.5,
 * Konzept Abschnitt 4: "Geräte-Lockdown-Bündel ... als zusammengesetzter Eintrag" — Beispiel
 * dort: USB-Data-Signaling + `DISALLOW_SAFE_BOOT`/`DISALLOW_FACTORY_RESET`/
 * `DISALLOW_DEBUGGING_FEATURES`). Bewusst framework-frei — reine Komposition über das
 * [Safeguard]-Interface, konkrete Mitglieder (die DPM brauchen) kommen aus `:core:data`.
 *
 * [isActive] ist nur dann `true`, wenn wirklich **alle** [members] aktiv sind — konservativ: ein
 * nur teilweise durchgesetztes Bündel gilt nicht als "aktiv" (Invariante 6: im Zweifel den
 * strengeren, nicht den lockereren Zustand annehmen).
 *
 * **Best-Effort pro Mitglied, aber laut nach außen:** [apply]/[revert] versuchen **jedes**
 * Mitglied unabhängig — ein fehlschlagendes Mitglied hält die übrigen nicht auf, damit ein
 * einzelner defekter Teil nicht die ganze restliche Härtung verhindert. Schlägt mindestens ein
 * Mitglied fehl, werfen [apply]/[revert] am Ende trotzdem eine [CompositeSafeguardException] mit
 * allen Fehlschlägen — sonst würde eine aufrufende `SafeguardRegistry` einen nur teilweise
 * erfolgreichen Zustand fälschlich als vollen Erfolg verbuchen (der Soll-Zustand würde als
 * "erledigt" persistiert, obwohl er es nicht ist).
 */
class CompositeSafeguard(
    override val id: String,
    private val members: List<Safeguard>,
) : Safeguard {

    init {
        require(members.isNotEmpty()) { "CompositeSafeguard \"$id\" braucht mindestens ein Mitglied" }
    }

    override fun apply() = runOnAllMembers("apply") { it.apply() }

    override fun revert() = runOnAllMembers("revert") { it.revert() }

    override fun isActive(): Boolean = members.all { it.isActive() }

    private fun runOnAllMembers(operation: String, action: (Safeguard) -> Unit) {
        val failures = mutableListOf<MemberFailure>()
        for (member in members) {
            try {
                action(member)
            } catch (e: Exception) {
                failures += MemberFailure(member.id, e)
            }
        }
        if (failures.isNotEmpty()) {
            throw CompositeSafeguardException(id, operation, failures)
        }
    }
}

/** Ein einzelner fehlgeschlagener Mitglied-Aufruf innerhalb eines [CompositeSafeguard]. */
data class MemberFailure(val memberId: String, val cause: Throwable)

/**
 * Geworfen von [CompositeSafeguard.apply]/[CompositeSafeguard.revert], wenn mindestens ein
 * Mitglied fehlschlug — s. [CompositeSafeguard]-Klassendoc für die Begründung, warum das nicht
 * verschluckt wird.
 */
class CompositeSafeguardException(
    val compositeId: String,
    val operation: String,
    val failures: List<MemberFailure>,
) : Exception(
    "CompositeSafeguard \"$compositeId\": $operation fehlgeschlagen für " +
        failures.joinToString { "${it.memberId} (${it.cause.message})" },
)
