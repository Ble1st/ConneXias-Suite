package de.ble1st.warden.hardening

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.os.UserManager
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.hardening.FailedAttemptsWipeThreshold
import de.ble1st.warden.domain.hardening.LocationEnforcement
import de.ble1st.warden.domain.hardening.TimeIntegrityMode
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.UserRestrictionSafeguard

/**
 * Wendet die drei Tier-2-Auswahlmenüs auf echte DPM-Aufrufe an (2026-09-05).
 *
 * **Decision/Executor-Trennung wie überall:** *was* die einzelnen Stufen bedeuten, steht in den
 * drei Enums ([LocationEnforcement], [TimeIntegrityMode], [FailedAttemptsWipeThreshold]) — hier
 * steht nur, welcher DPM-Aufruf daraus folgt.
 *
 * **Idempotent und jederzeit wiederholbar**, dieselbe Zusage wie bei
 * [de.ble1st.warden.domain.registry.Safeguard]: [applyAll] darf beliebig oft laufen. Es wird nach
 * jeder Änderung in den Einstellungen aufgerufen und zusätzlich beim Boot-Reconcile mitgeführt —
 * anders als die Katalog-Safeguards liegen diese drei Werte nicht in der Safeguard-Registry (sie
 * sind mehrstufig, nicht an/aus, und passen deshalb nicht in das `Safeguard`-Interface), brauchen
 * also einen eigenen Wiederherstellungspunkt nach einem Neustart.
 *
 * **Jeder Aufruf ist einzeln abgesichert.** Fällt einer aus (Herstelleraufsatz verweigert eine
 * Einstellung, fehlender Device Owner), sollen die anderen beiden trotzdem greifen — ein
 * gemeinsamer `try` um alles drei würde aus einem Teilausfall einen Totalausfall machen.
 *
 * **Ein DPM-Bit wird mit dem Safeguard-Katalog geteilt, und das ist hier bewusst behandelt:**
 * `DISALLOW_CONFIG_DATE_TIME` gehört bereits dem Katalog-Safeguard
 * [UserRestrictionSafeguard.CONFIG_DATE_TIME_DISABLED_ID]. Ein zweiter, unabhängiger Soll-Zustand
 * für dasselbe Bit ist in diesem Projekt schon zweimal als echter Fehler aufgefallen (Always-On-VPN
 * und die vier Lockdown-Bündel-IDs, s. `CLAUDE.md`) — deshalb setzt [applyTimeIntegrity] die Sperre
 * **nicht selbst**, sondern über die Registry, und **nimmt sie nie zurück**. Dieselbe Asymmetrie wie
 * bei `RegistryReconciler`s `neverWeaken` und `WardenProfileApplyDecision.LOCKDOWN_SHARED_IDS`: für
 * ein geteiltes Bit darf ein zweiter Weg nur verschärfen, nie lockern. Ausgeschaltet wird die
 * Sperre da, wo ihr Soll-Zustand herkommt — am Schalter im Safeguards-Bildschirm.
 *
 * `DISALLOW_CONFIG_LOCATION` teilt sich dagegen mit niemandem: dort ist dieser Controller der
 * einzige Schreiber, und deshalb darf er dort auch beide Richtungen bedienen.
 */
class HardeningPreferencesController(
    private val context: Context,
    /** Nur für den geteilten Datum/Uhrzeit-Schalter, s. Klassendoc. Wird geladen, bevor
     * [applyTimeIntegrity] etwas anfasst — ohne `load()` kennte die Registry den persistierten
     * Soll-Zustand nicht und `apply()` würde ihn überschreiben statt fortzuschreiben. */
    private val registry: PersistentSafeguardRegistry = buildReversibleRegistry(context),
) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    private fun dpm(): DevicePolicyManager =
        checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
            "DevicePolicyManager nicht verfügbar"
        }

    fun applyAll() {
        applyLocation(HardeningPreferencesStorage.loadLocation(context))
        applyTimeIntegrity(HardeningPreferencesStorage.loadTimeIntegrity(context))
        applyWipeThreshold(HardeningPreferencesStorage.loadWipeThreshold(context))
    }

    fun applyLocation(mode: LocationEnforcement) {
        runCatching {
            if (mode.enablesLocation) dpm().setLocationEnabled(admin, true)
        }.onFailure { Log.w(TAG, "Ortung erzwingen fehlgeschlagen", it) }
        // Die Sperre wird immer explizit gesetzt *oder* gelöscht — sonst bliebe sie nach einem
        // Wechsel auf eine schwächere Stufe stehen, und der Nutzer käme nicht mehr an die
        // Einstellung heran, obwohl er das gerade abgewählt hat.
        runCatching {
            if (mode.locksSetting) {
                dpm().addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
            } else {
                dpm().clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
            }
        }.onFailure { Log.w(TAG, "Ortungs-Sperre setzen/lösen fehlgeschlagen", it) }
    }

    fun applyTimeIntegrity(mode: TimeIntegrityMode) {
        runCatching {
            if (mode.enforcesAutoTime) {
                dpm().setAutoTimeEnabled(admin, true)
                dpm().setAutoTimeZoneEnabled(admin, true)
            }
        }.onFailure { Log.w(TAG, "Netzzeit erzwingen fehlgeschlagen", it) }
        // Nur verschärfen, nie lockern, und über die Registry statt direkt am DPM — s. Klassendoc.
        if (!mode.locksSetting) return
        runCatching {
            registry.load()
            registry.apply(UserRestrictionSafeguard.CONFIG_DATE_TIME_DISABLED_ID)
        }.onFailure { Log.w(TAG, "Zeit-Sperre setzen fehlgeschlagen", it) }
    }

    /**
     * **Der einzige wirklich unumkehrbare Schalter in diesem Controller.** `0` schaltet die
     * Richtlinie ab (Androids eigene Semantik für "kein Limit") — der Rückweg ist also derselbe
     * Aufruf, nicht etwa ein Sonderfall.
     */
    fun applyWipeThreshold(threshold: FailedAttemptsWipeThreshold) {
        runCatching {
            dpm().setMaximumFailedPasswordsForWipe(admin, threshold.attempts)
        }.onFailure { Log.w(TAG, "Fehlversuchs-Löschgrenze setzen fehlgeschlagen", it) }
    }

    /** Liest den tatsächlich gesetzten OS-Wert zurück — für die "Soll vs. Ist"-Anzeige (s.
     * [de.ble1st.warden.ui.SafeguardsScreen]s Divergenz-Hinweis). `null`, wenn nicht lesbar. */
    fun readActiveWipeThreshold(): Int? =
        runCatching { dpm().getMaximumFailedPasswordsForWipe(admin) }.getOrNull()

    /** Ob die Ortung aktuell wirklich an ist — [LocationEnforcement] beschreibt nur den Wunsch.
     * Gelesen über den [LocationManager], nicht über den `DevicePolicyManager`: dort gibt es nur
     * den Setzweg (`setLocationEnabled`), die Abfrage gehört dem Standort-Dienst. */
    fun isLocationEnabled(): Boolean? = runCatching {
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled
    }.getOrNull()

    private companion object {
        const val TAG = "HardeningPreferences"

        /** Dieselbe Zusammenstellung wie in
         * [de.ble1st.warden.boot.RegistryReconciliationReceiver] — bewusst nur der reversible
         * Katalog, das Lockdown-Bündel gehört ausschließlich hinter den Presence-Pfad. */
        fun buildReversibleRegistry(context: Context): PersistentSafeguardRegistry {
            val registry = PersistentSafeguardRegistry(
                SafeguardRegistry(),
                SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
            )
            SafeguardCatalog.registerReversible(registry, context)
            return registry
        }
    }
}
