package de.ble1st.warden.registry

import android.content.Context
import de.ble1st.warden.domain.registry.CompositeSafeguard
import de.ble1st.warden.netlock.NetLockdownAuthorizer

/**
 * Meilenstein C.5 (Konzept Abschnitt 4/19): "Geräte-Lockdown-Bündel (USB-Signaling, SAFE_BOOT,
 * FACTORY_RESET, DEBUGGING_FEATURES, Sentinel scharf) als zusammengesetzter Eintrag." Baut den
 * konkreten [CompositeSafeguard] aus sechs Einzel-Safeguards: [UsbDataSignalingSafeguard],
 * [UserRestrictionSafeguard.safeBootDisabled], [UserRestrictionSafeguard.factoryResetDisabled],
 * [UserRestrictionSafeguard.debuggingFeaturesDisabled], [WardenLockTaskAuthorizer],
 * [NetLockdownAuthorizer] (seit "Netz-Sperre", 2026-08-27 — s. dortiger Members-Listen-Kommentar
 * für die Begründung, warum es zusätzlich einen unabhängigen Standalone-Schalter hat, anders als
 * die übrigen fünf). Factory-reset /
 * safe-boot also exist as standalone reversible catalog entries for Alltag; the bundle still
 * reapplies them plus USB-debug kill. `DISALLOW_OEM_UNLOCK` is hidden and immutable for Device
 * Owner — debugging-features-off is the public path that also hides the OEM-unlock toggle.
 *
 * [WardenLockTaskAuthorizer] deckt davon bewusst nur die **Autorisierungs**-Hälfte ab
 * (DPM-Whitelist: darf die separate Sentinel-App überhaupt in den Lock-Task-Modus, s. dessen
 * eigenem Klassendoc für den Wechsel von "eigenes Paket" auf "Sentinel-Paket") — dieselbe Grenze,
 * die schon in dessen eigenem Klassendoc gezogen wird: das eigentliche Scharfschalten
 * (`Activity.startLockTask()`) bleibt bewusst außerhalb *dieses Bündels* und läuft seit "Sentinel:
 * eigenständige Kiosk-PIN-App" ohnehin in Sentinels eigenem, fremden Prozess — ein `apply()`
 * dieses Bündel-Mitglieds versetzt das Gerät selbst nicht in den Lock-Task-Modus, das ist
 * `de.ble1st.warden.sentinelbridge.SentinelLockdownEngager`s Aufgabe. Seit
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) gibt es dafür reale, aber eigenständig
 * (presence-gated bzw. mehrfach gegatet) abgesicherte Aufrufer, s. `SentinelLockdownEngager`-
 * Klassendoc — "jedes automatischen Pfads" gilt also nicht mehr uneingeschränkt, nur noch bezogen
 * auf dieses eine Bündel selbst.
 *
 * **Seit "arbeite langsam am Lockdownmodus" (2026-08-22) über
 * [de.ble1st.warden.domain.presence.SensitiveAction.LOCKDOWN_MODE_ARM] erreichbar** — auf
 * ausdrücklichen Nutzerwunsch presence-gated wie `WIPE_DATA`/`REBOOT`, nicht als einfacher
 * Safeguard-Schalter (`SensitiveActionActivity`-Klassendoc für die Verkabelung, `MasterSwitch`/
 * `MASTER_SWITCH_REVERT` für den Rückweg). Das ändert nichts an der realen Gefahrenlage der vier
 * ursprünglichen Mitglieder (Werksreset-Blockade, gekappte `adb`-Verbindung — s. die jeweiligen
 * Klassendocs): weiterhin bewusst **nicht** in `RegistryReconciliationReceiver` registriert (kein
 * automatisches Wieder-Scharfschalten nach Boot ohne erneute bewusste Presence-Bestätigung), und
 * `apply()`/`revert()` dieses Bündels bleiben auf dem aktuellen physischen Testgerät weiterhin nie
 * live ausgeführt — strukturell ausgeschlossen durch `DestructiveCommandGuard` (F.4), solange das
 * Gerät ein Debug-Build ist (was es aktuell ausnahmslos ist). [WardenLockTaskAuthorizer] selbst
 * bleibt zwar einzeln unkritisch live testbar (s. dessen Klassendoc), das ändert nichts an der
 * Vorsicht für das Bündel als Ganzes. Ein echter Live-Test auf einem Non-Debug-Build bleibt eine
 * bewusste, separate künftige Entscheidung, kein Nebeneffekt dieser Verkabelung.
 */
object DeviceLockdownBundle {
    const val ID = "device_lockdown_bundle"

    fun build(context: Context): CompositeSafeguard = CompositeSafeguard(
        id = ID,
        members = listOf(
            UsbDataSignalingSafeguard(context),
            UserRestrictionSafeguard.safeBootDisabled(context),
            UserRestrictionSafeguard.factoryResetDisabled(context),
            UserRestrictionSafeguard.debuggingFeaturesDisabled(context),
            WardenLockTaskAuthorizer(context),
            // "Netz-Sperre" (2026-08-27): sechstes Mitglied — anders als die übrigen fünf hat
            // NetLockdownAuthorizer auch einen unabhängigen Standalone-Schalter (NetworkScreen ->
            // NetLockdownController.arm()/disarm(), s. SafeguardCatalog.reversible()-Kommentar für
            // die Begründung, warum das bewusst NICHT über den generischen Safeguard-Registry-Pfad
            // läuft). Als Bundle-Mitglied arm()t es hier trotzdem korrekt: CompositeSafeguard.apply()
            // ruft NetLockdownAuthorizer.apply() parameterlos auf (leere Lockdown-Allowlist) — für
            // den Notfall-Lockdown-Modus sogar gewünscht strenger als der Alltags-Schalter (totaler
            // Netz-Kill-Switch, keine Bypass-Apps), nicht bloß ein Kompromiss.
            NetLockdownAuthorizer(context),
        ),
    )
}
