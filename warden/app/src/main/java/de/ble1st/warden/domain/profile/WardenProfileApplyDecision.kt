package de.ble1st.warden.domain.profile

/** Was [de.ble1st.warden.registry.WardenProfileApplier.apply] mit einem einzelnen registrierten
 * reversiblen Safeguard tun soll. */
enum class WardenProfileApplyAction { APPLY, REVERT, LEAVE_UNTOUCHED }

/**
 * analyse.md (2. Durchgang, 2026-09-02, Hoch — "Profil-Apply nimmt Sentinel-Deinstallationsschutz
 * zurück"): `sentinel_uninstall_protection` steht im reversiblen Katalog
 * ([de.ble1st.warden.registry.SafeguardCatalog.reversible]), aber in **keinem** [WardenProfile].
 * Die alte, direkt inline geschriebene Applier-Schleife revertierte deshalb jede registrierte ID,
 * die nicht im Profil steht — also auch diese, bei **jedem** Profil-Apply (manuell, QR-
 * Provisioning, Nacht/Tag-Automatik). [de.ble1st.warden.appmanagement.SentinelInstallResultReceiver]s
 * eigener Kommentar sagt ausdrücklich "nie automatisch ausschalten" — ein Angreifer musste dafür
 * nur einen beliebigen Profilwechsel abwarten (oder auslösen), danach war `pm uninstall
 * de.ble1st.warden.sentinel` wieder möglich, ganz ohne dass irgendein Test rot wurde (die
 * `WardenProfileSpecTest`-Prüfung gegen eine handgepflegte ID-Menge deckte genau diese Lücke nicht
 * ab).
 *
 * Dieser Safeguard darf von Profil-Apply weder ein- noch ausgeschaltet werden — sein Zustand wird
 * ausschließlich durch Sentinels Installations-/Deinstallations-Lebenszyklus bestimmt, nicht durch
 * ein Profil. [NEVER_TOUCHED] macht das strukturell statt nur per Kommentar durchsetzbar: jede ID
 * darin überspringt die normale Apply-wenn-in-Profil/Revert-wenn-nicht-Logik komplett.
 *
 * Framework-frei (keine Android-Imports) und damit als reiner JVM-Unit-Test prüfbar — anders als
 * [de.ble1st.warden.registry.WardenProfileApplier] selbst, der einen echten `Context`/
 * `PersistentSafeguardRegistry` braucht und deshalb nur instrumented getestet werden könnte
 * (aktuell in diesem Projekt für Klassen im `registry`-Paket nicht eingerichtet). Der eigentliche
 * Regressionsschutz für dieses Finding lebt deshalb hier statt in einem Test gegen
 * `SafeguardCatalog.reversible()` selbst.
 */
object WardenProfileApplyDecision {

    /** IDs, die ein Profil-Apply niemals anfasst — weder ein- noch ausschalten. */
    val NEVER_TOUCHED: Set<String> = setOf(SafeguardIds.SENTINEL_UNINSTALL_PROTECTION)

    /**
     * analyse.md (2. Durchgang, Hoch — "Profile knacken Presence-Lockdown über geteilten
     * DPM-Zustand"): diese vier reversiblen Katalog-IDs sind zugleich Mitglieder von
     * [de.ble1st.warden.registry.DeviceLockdownBundle] (presence-gated, `LOCKDOWN_MODE_ARM`) —
     * dieselben DPM-Bits, zweimal registriert, unter zwei unabhängigen Soll-Zuständen (Katalog
     * vs. Bündel). `debugging_features_disabled` steht in **keinem** [WardenProfile]: ein
     * Bündel-Arm setzt es scharf (presence bestätigt), ein danach angewendetes Profil — jedes,
     * ausnahmslos — revertierte es bisher wieder, ohne jede erneute Presence-Prüfung, weil die
     * alte Schleife jede nicht im Profil stehende registrierte ID pauschal zurücknahm. Alltag traf
     * zusätzlich `usb_data_signaling_disabled` (nicht in Alltags-Set, wohl aber im Bündel).
     * `factory_reset_disabled`/`safe_boot_disabled` sind unkritisch, weil jedes Profil sie ohnehin
     * selbst will (bleiben also durch [WardenProfileApplyAction.APPLY] scharf) — trotzdem hier mit
     * aufgeführt, für den (heute nicht erreichbaren, aber nicht strukturell ausgeschlossenen) Fall
     * einer künftigen Profil-Änderung, die eine der beiden herausnimmt.
     *
     * Ein Profil-Apply darf eine dieser vier IDs nur revertieren, wenn das Bündel gerade NICHT
     * presence-armed ist — dafür reicht `LEAVE_UNTOUCHED` (nicht [NEVER_TOUCHED]): will das Profil
     * die ID selbst (`APPLY`), bleibt sie ohnehin an; will es sie nicht UND das Bündel ist armed,
     * wird sie stehen gelassen statt zurückgenommen. Nur ein bewusster, presence-gated
     * `MASTER_SWITCH_REVERT` darf das Bündel selbst abräumen.
     */
    val LOCKDOWN_SHARED_IDS: Set<String> = setOf(
        SafeguardIds.USB_DATA_SIGNALING_DISABLED,
        SafeguardIds.SAFE_BOOT_DISABLED,
        SafeguardIds.FACTORY_RESET_DISABLED,
        SafeguardIds.DEBUGGING_FEATURES_DISABLED,
    )

    fun actionFor(id: String, wantOn: Set<String>, lockdownActive: Boolean = false): WardenProfileApplyAction = when {
        id in NEVER_TOUCHED -> WardenProfileApplyAction.LEAVE_UNTOUCHED
        id in wantOn -> WardenProfileApplyAction.APPLY
        lockdownActive && id in LOCKDOWN_SHARED_IDS -> WardenProfileApplyAction.LEAVE_UNTOUCHED
        else -> WardenProfileApplyAction.REVERT
    }
}

/** Geteilte ID-Konstanten für Safeguards, die von mehr als einer Schicht referenziert werden
 * müssen (hier: framework-freie Profil-Entscheidungslogik UND die framework-seitigen
 * `*Safeguard`-Klassen im `registry`-Paket) — eine einzige Quelle statt mehrerer unabhängig
 * gepflegter String-Literale, die auseinanderlaufen könnten. */
object SafeguardIds {
    const val SENTINEL_UNINSTALL_PROTECTION = "sentinel_uninstall_protection"
    const val USB_DATA_SIGNALING_DISABLED = "usb_data_signaling_disabled"
    const val SAFE_BOOT_DISABLED = "safe_boot_disabled"
    const val FACTORY_RESET_DISABLED = "factory_reset_disabled"
    const val DEBUGGING_FEATURES_DISABLED = "debugging_features_disabled"
}
