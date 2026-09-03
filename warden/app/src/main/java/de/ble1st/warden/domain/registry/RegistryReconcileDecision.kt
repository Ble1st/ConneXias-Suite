package de.ble1st.warden.domain.registry

/** Was [de.ble1st.warden.registry.RegistryReconciler] für eine erkannte Soll-/Ist-Divergenz einer
 * einzelnen id tun soll. */
enum class RegistryReconcileAction { APPLY, REVERT, LEAVE_UNTOUCHED }

/**
 * analyse.md (2. Durchgang, Mittel — "USB-Daten am gesperrten Gerät nach Boot wieder an"):
 * [de.ble1st.warden.usb.UsbDataSignalingSafeguard] wird nicht nur über die Registry
 * (statischer Soll-Zustand pro Profil) geschaltet, sondern auch dynamisch von
 * [de.ble1st.warden.usb.UsbAutoLockController] direkt über die DPM-API — abgekoppelt vom
 * Registry-Soll-Zustand, absichtlich (s. dessen Klassendoc: "Auto-lock itself stays as-is when
 * those are off"). Alltag setzt den Katalog-Soll-Zustand für diese id nicht auf `true`; ein
 * gesperrtes Gerät, das Auto-Lock kurz vor einem Neustart USB-Data-Signaling ausgeschaltet hatte,
 * bootet deshalb mit `desired=false`, `actual=true` ("aus"). Die alte, undifferenzierte
 * [de.ble1st.warden.registry.RegistryReconciler]-Schleife wertete das als Divergenz und rief
 * `revert()` — USB-Data-Signaling ging wieder an, **bevor** das Gerät entsperrt war, exakt in dem
 * Fenster, für das Auto-Lock gebaut wurde. [de.ble1st.warden.usb.UsbAutoLockStorage] selbst liegt
 * bewusst auf Credential-Encrypted-Storage (dessen Klassendoc) und kann während Direct Boot weder
 * gelesen noch als verlässliches Korrektiv genutzt werden.
 *
 * [actionFor] macht die Boot-Reconciliation für die in [neverWeaken] gelisteten ids strukturell
 * einseitig statt das Auto-Lock-Feature-Flag risikant vor dem Entsperren nachzuvollziehen:
 * Verstärken (Soll `true`, Ist `false` → [RegistryReconcileAction.APPLY]) bleibt uneingeschränkt
 * erlaubt — ein zusätzlich aktivierter Schalter ist vor dem Entsperren nie ein Sicherheitsrisiko.
 * Abschwächen (Soll `false`, Ist `true` → sonst [RegistryReconcileAction.REVERT]) wird für diese
 * ids stattdessen [RegistryReconcileAction.LEAVE_UNTOUCHED] — dieselbe "im Zweifel die
 * restriktivere Richtung"-Haltung wie überall sonst im Projekt (s. `CLAUDE.md`,
 * "Fail-safe over convenient"). Der reguläre Soll-Zustand wird nach dem Entsperren ohnehin über
 * [de.ble1st.warden.usb.UsbLockStateReceiver]/[de.ble1st.warden.usb.UsbAutoLockWorker] wieder mit
 * dem dann tatsächlich lesbaren Sperrzustand abgeglichen — diese Funktion muss die Divergenz also
 * nicht selbst auflösen, nur sicher überbrücken.
 */
object RegistryReconcileDecision {

    fun actionFor(id: String, desired: Boolean, actual: Boolean, neverWeaken: Set<String> = emptySet()): RegistryReconcileAction = when {
        desired == actual -> RegistryReconcileAction.LEAVE_UNTOUCHED
        desired && !actual -> RegistryReconcileAction.APPLY
        id in neverWeaken -> RegistryReconcileAction.LEAVE_UNTOUCHED
        else -> RegistryReconcileAction.REVERT
    }
}
