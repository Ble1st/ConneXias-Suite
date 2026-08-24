package de.ble1st.warden.registry

import android.content.Context
import de.ble1st.warden.domain.registry.CompositeSafeguard

/**
 * Meilenstein C.5 (Konzept Abschnitt 4/19): "Geräte-Lockdown-Bündel (USB-Signaling, SAFE_BOOT,
 * FACTORY_RESET, DEBUGGING_FEATURES, Sentinel scharf) als zusammengesetzter Eintrag." Baut den
 * konkreten [CompositeSafeguard] aus fünf Einzel-Safeguards: [UsbDataSignalingSafeguard],
 * [UserRestrictionSafeguard.safeBootDisabled], [UserRestrictionSafeguard.factoryResetDisabled],
 * [UserRestrictionSafeguard.debuggingFeaturesDisabled], [WardenLockTaskAuthorizer]. Factory-reset /
 * safe-boot also exist as standalone reversible catalog entries for Alltag; the bundle still
 * reapplies them plus USB-debug kill. `DISALLOW_OEM_UNLOCK` is hidden and immutable for Device
 * Owner — debugging-features-off is the public path that also hides the OEM-unlock toggle.
 *
 * [WardenLockTaskAuthorizer] deckt davon bewusst nur die **Autorisierungs**-Hälfte ab
 * (DPM-Whitelist: darf das eigene Paket überhaupt in den Lock-Task-Modus) — dieselbe Grenze, die
 * schon in dessen eigenem Klassendoc gezogen wird: das eigentliche Scharfschalten
 * (`Activity.startLockTask()`) bleibt bewusst außerhalb dieses Bündels und jedes automatischen
 * Pfads, s. `WardenLockTaskGate`/`WardenLockTaskManager`-Klassendocs — ein `apply()` dieses
 * Bündel-Mitglieds versetzt das Gerät selbst nicht in den Kiosk-Modus.
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
        ),
    )
}
