package de.ble1st.warden.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.pin.WardenLockScreenTextStorage
import de.ble1st.warden.registry.LockScreenInfoManager
import de.ble1st.warden.registry.OrganizationNameManager
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryCorrection
import de.ble1st.warden.registry.RegistryReconciler
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.SupportMessageManager
import de.ble1st.warden.registry.WardenOrganizationNameStorage
import de.ble1st.warden.registry.WardenSupportMessageStorage
import de.ble1st.warden.wardenAuditLog

/**
 * Meilenstein C.4 (Konzept Abschnitt 4/11/19): Registry-Reconciliation bei
 * `ACTION_LOCKED_BOOT_COMPLETED`. Dünne Android-Glue analog zu `WardenDeviceAdminReceiver` — die
 * eigentliche Logik lebt testbar in `RegistryReconciler`. Registriert die drei bekannten
 * C.2-Schalter (Kamera, Screen-Capture, `DISALLOW_INSTALL_UNKNOWN_SOURCES`) sowie seit Tier
 * 1/2/3/5 (2026-08-22) acht weitere, reversible Schalter (s. `buildRegistry`) — bewusst weiterhin
 * **nicht** das C.5-Lockdown-Bündel (`DeviceLockdownBundle`, dortiges Klassendoc: reales
 * Rückbau-Risiko auf dem aktuellen Testgerät).
 *
 * `directBootAware="true"` (Manifest) + [Context.createDeviceProtectedStorageContext] über
 * [RegistryStorage]/[LogStorage]: dieser Receiver feuert **vor** dem Entsperren (Direct
 * Boot/FBE) — Wardens normaler, credential-verschlüsselter App-Speicher ist zu diesem Zeitpunkt
 * noch nicht zugänglich (s. dortige Klassendocs für die volle Begründung).
 *
 * Jede Korrektur wird einzeln in [HashChainLogStore] protokolliert (Konzept: "jede Korrektur
 * geloggt") — Erfolge als `INFO`, Fehlschläge (isoliert pro Eintrag, s. `RegistryReconciler`-Doc)
 * als `ERROR`.
 */
class RegistryReconciliationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val registry = buildRegistry(context)
        val logStore = wardenAuditLog(context)

        registry.load()
        RegistryReconciler(registry) { correction -> log(logStore, correction) }.reconcile()
        reconcileLockScreenInfo(context, logStore)
        reconcileOrganizationName(context, logStore)
        reconcileSupportMessage(context, logStore)
        // "Netz-Sperre" (2026-08-27): reconcileNetLockdown()-Aufruf entfernt — Feature pausiert
        // (ungeklärter Kernfehler im Live-Test, s. WardenApplication-Klassendoc), Code geparkt
        // unter app/netlock-disabled/. Bei Reaktivierung: Aufruf hier + die zugehörige private
        // Methode (nutzte NetLockdownController(context).reconcile()) wiederherstellen.
    }

    /** [LockScreenInfoManager] ist kein [de.ble1st.warden.domain.registry.Safeguard] (Freitext
     * statt Boolean-"an/aus", s. dortiges Klassendoc) und läuft deshalb nicht über
     * [RegistryReconciler] — derselbe Soll-vs-Ist-Gedanke trotzdem manuell nachgebildet: Soll-Wert
     * aus [WardenLockScreenTextStorage] (Device-Protected-Storage, hier vor dem Entsperren bereits
     * lesbar) gegen den live über die DPM gelesenen Ist-Wert vergleichen, nur bei Abweichung
     * erneut setzen. */
    private fun reconcileLockScreenInfo(context: Context, logStore: HashChainLogStore) {
        val desired = WardenLockScreenTextStorage.load(context)
        try {
            val manager = LockScreenInfoManager(context)
            if (manager.current() != desired) {
                manager.apply(desired)
                logStore.append(Log.INFO, TAG, "reconciled lock_screen_info")
            }
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "failed to reconcile lock_screen_info: $e")
        }
    }

    /** Dieselbe manuell nachgebildete Soll-vs-Ist-Reconciliation wie [reconcileLockScreenInfo],
     * für den unabhängigen [OrganizationNameManager]-Wert (s. dortiges Klassendoc). */
    private fun reconcileOrganizationName(context: Context, logStore: HashChainLogStore) {
        val desired = WardenOrganizationNameStorage.load(context)
        try {
            val manager = OrganizationNameManager(context)
            if (manager.current() != desired) {
                manager.apply(desired)
                logStore.append(Log.INFO, TAG, "reconciled organization_name")
            }
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "failed to reconcile organization_name: $e")
        }
    }

    /** Dieselbe manuell nachgebildete Soll-vs-Ist-Reconciliation wie [reconcileLockScreenInfo]/
     * [reconcileOrganizationName], für den unabhängigen [SupportMessageManager]-Wert (Tier 6). */
    private fun reconcileSupportMessage(context: Context, logStore: HashChainLogStore) {
        val desired = WardenSupportMessageStorage.load(context)
        try {
            val manager = SupportMessageManager(context)
            if (manager.current() != desired) {
                manager.apply(desired)
                logStore.append(Log.INFO, TAG, "reconciled support_message")
            }
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "failed to reconcile support_message: $e")
        }
    }

    private fun buildRegistry(context: Context): PersistentSafeguardRegistry {
        val registry = PersistentSafeguardRegistry(
            SafeguardRegistry(),
            SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
        )
        // Reversible catalog only — lockdown is not re-armed before unlock without presence.
        SafeguardCatalog.registerReversible(registry, context)
        return registry
    }

    private fun log(logStore: HashChainLogStore, correction: RegistryCorrection) {
        when (correction) {
            is RegistryCorrection.Applied -> logStore.append(
                priority = Log.INFO,
                tag = TAG,
                message = "reconciled ${correction.id} -> desired=${correction.desired}",
            )
            is RegistryCorrection.Failed -> logStore.append(
                priority = Log.ERROR,
                tag = TAG,
                message = "failed to reconcile ${correction.id} -> desired=${correction.desired}: " +
                    "${correction.cause}",
            )
        }
    }

    private companion object {
        const val TAG = "RegistryReconciliation"
    }
}
