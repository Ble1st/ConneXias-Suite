package de.ble1st.warden.netlock

import de.ble1st.warden.registry.DpmSafeguard
import android.content.Context

/**
 * "Netz-Sperre" (2026-08-27): Warden-seitige DPM-Anbindung für Always-On-VPN mit
 * `lockdownEnabled=true`, portiert vom ConneXias-Framework-Quellprojekt
 * (`core/data/vpn/NetLockdownAuthorizer.kt`) — dort für ein fremdes Barbican-APK, hier für Wardens
 * eigenes Paket/[WardenVpnService] (kein Cross-APK-IPC mehr nötig, s. `CLAUDE.md`-"Netz-Sperre"-
 * Abschnitt). Ist-Zustand live vom System (`getAlwaysOnVpnPackage`), keine eigene Speicherung
 * (Konzept-Prinzip "DPM-Restrictions: Wahrheit im System").
 *
 * **Zwei unabhängige Bypass-Mechanismen, empirisch im Quellprojekt gefunden (dortiges Klassendoc,
 * 2026-08-19, "ERR_NAME_NOT_RESOLVED" trotz Firewall-Policy "Erlaubt"):** `VpnService.Builder
 * .addDisallowedApplication` (Tunnel-seitig, [WardenVpnService]) und DPMs eigene
 * `lockdownAllowlist` (System-seitig, hier) sind zwei strukturell getrennte Durchsetzungsebenen —
 * eine ALLOWED-App, die nur aus der Builder-Liste ausgeschlossen ist, wird von DPMs
 * Lockdown-Firewall trotzdem komplett blockiert (eigene UID-Liste, unabhängig von Barbicans/
 * Wardens Routing-Entscheidung). [apply] nimmt deshalb die **4-Parameter**-Überladung von
 * `setAlwaysOnVpnPackage` (`lockdownAllowlist: Set<String>`, API 29+) — jeder Aufrufer muss die
 * aktuelle ALLOWED-Liste ([NetworkFirewallPolicyStore.allowedPackageNames]) mitgeben, sowohl beim
 * initialen Scharfschalten als auch bei jeder späteren Firewall-Policy-Änderung
 * ([NetLockdownController.resyncLockdownAllowlist]).
 *
 * **Kein `VpnService.prepare()`-Consent-Dialog nötig:** die AOSP-Javadoc von
 * `setAlwaysOnVpnPackage()` sagt explizit "This connection is automatically granted and persisted
 * after a reboot" für einen Device-Owner-Aufrufer.
 *
 * **Ein einziger Soll-Zustand-Schreiber (analyse.md, 2. Durchgang, Hoch — "Zwei Soll-Zustände für
 * Always-On-VPN"):** vorher schrieb ausschließlich [NetLockdownController.arm]/`disarm` in
 * [NetLockdownStore], während [de.ble1st.warden.registry.DeviceLockdownBundle] dieselbe Instanz
 * dieser Klasse direkt über ihr no-arg [Safeguard]-Interface anspricht
 * (`LOCKDOWN_MODE_ARM`/`MASTER_SWITCH_REVERT`, presence-gated) — ohne je den Store anzufassen. Ein
 * `NetworkScreen`-Scharfschalten (Store `true`) gefolgt von `MASTER_SWITCH_REVERT` (DPM aus, Store
 * bleibt fälschlich `true`) ließ die nächste Boot-Reconciliation das Bündel-VPN ohne jede erneute
 * Presence-Bestätigung wieder scharf schalten; umgekehrt entschärfte ein Boot nach
 * `LOCKDOWN_MODE_ARM` (Store `false`/`null`) das gerade presence-bestätigte Lockdown-VPN wieder.
 * [apply]/[revert] schreiben den Soll-Zustand jetzt selbst, unabhängig vom Aufrufer — ein einziger
 * Soll-Zustand für beide Wege statt zweier unabhängig driftender.
 */
class NetLockdownAuthorizer(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    private val store = NetLockdownStore(NetLockdownStore.buildEnvelopeFile(context))

    /** [de.ble1st.warden.domain.registry.Safeguard]-Interface-Konformität (No-Argument-Aufruf) —
     * scharf mit **leerer** Lockdown-Allowlist, nur für Master-Switch-/Failsafe-Snapshot-Zwecke
     * gedacht (s. `CLAUDE.md`). Echte Aufrufer nutzen [apply] mit der aktuellen
     * Firewall-ALLOWED-Liste — dieser Überladung fehlt sonst jede Bypass-Wirkung auf DPM-Ebene. */
    override fun apply() = apply(emptySet())

    /** Pins Warden as Always-On VPN with lockdown — user cannot disable it in system settings.
     * [lockdownAllowlist] sind Paketnamen, die trotz Lockdown direkten Netzwerkzugriff behalten
     * (muss mit der Firewall-ALLOWED-Liste synchron gehalten werden, s. Klassendoc). */
    fun apply(lockdownAllowlist: Set<String>) {
        devicePolicyManager().setAlwaysOnVpnPackage(admin, context.packageName, true, lockdownAllowlist)
        store.saveDesiredArmed(true)
    }

    /** Removes Always-On VPN entirely — works even when [WardenVpnService] is dead. */
    override fun revert() {
        devicePolicyManager().setAlwaysOnVpnPackage(admin, null, false)
        store.saveDesiredArmed(false)
    }

    /** analyse.md (2. Durchgang, Mittel — "isActive() prüft nicht, was apply() setzt"): vorher nur
     * `getAlwaysOnVpnPackage() == packageName` — das ist wahr, sobald Warden irgendein Always-On-
     * VPN ist, unabhängig vom `lockdownEnabled`-Flag, das [apply] eigentlich setzt. Ohne
     * `lockdownEnabled` kann Verkehr am toten/nicht erreichbaren VPN-Prozess vorbei nach draußen
     * (u. a. IPv6, s. Klassendoc "zwei unabhängige Bypass-Mechanismen") — UI und Boot-Reconcile
     * hätten die Sperre trotzdem als scharf gemeldet. `isAlwaysOnVpnLockdownEnabled` (API 28+, hier
     * unbedingt verfügbar bei minSdk 35) prüft genau das zusätzliche Bit, das [apply] setzt. */
    override fun isActive(): Boolean =
        devicePolicyManager().getAlwaysOnVpnPackage(admin) == context.packageName &&
            devicePolicyManager().isAlwaysOnVpnLockdownEnabled(admin)

    /** Live Ist-Zustand der DPM-Lockdown-Allowlist (nicht die Builder-Bypass-Liste, s.
     * Klassendoc) — `null`, solange kein Always-On-Lockdown aktiv ist. */
    fun currentLockdownAllowlist(): Set<String>? = devicePolicyManager().getAlwaysOnVpnLockdownWhitelist(admin)

    companion object {
        const val ID = "net_lockdown"
    }
}
