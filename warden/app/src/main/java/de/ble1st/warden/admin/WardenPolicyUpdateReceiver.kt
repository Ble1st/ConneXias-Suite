package de.ble1st.warden.admin

import android.app.admin.PolicyUpdateReceiver
import android.app.admin.PolicyUpdateResult
import android.app.admin.TargetUser
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import de.ble1st.warden.domain.policycoexistence.PolicyConflictRecord
import de.ble1st.warden.domain.policycoexistence.PolicyUpdateOutcome
import de.ble1st.warden.policycoexistence.PolicyConflictStore
import de.ble1st.warden.wardenAuditLog

/**
 * Empfängt Androids Rückmeldung zu jeder Richtlinie, die Warden setzt oder zurücknimmt
 * (Tier 3 der DPC-Recherche, 2026-09-05 — der einzige *öffentliche* Zugang zur seit Android 14
 * eingeführten "policy coexistence").
 *
 * **Was das schließt.** Ein `Safeguard.isActive()` fragt immer den echten DPM-Zustand ab, aber
 * genau der gehört im Konfliktfall dem anderen Admin. Warden konnte "der Schalter greift nicht"
 * bisher nicht von "ein zweiter Admin hat gewonnen" unterscheiden — auf dem physischen Testgerät
 * kein Randfall, dort ist `com.samsung.android.kgclient` ein zweiter aktiver Admin. Diese
 * Rückmeldung ist die einzige Stelle, an der das System den Unterschied überhaupt mitteilt.
 *
 * **Nicht `getDevicePolicyState()`**, obwohl die DPC-Recherche das ursprünglich als Weg nannte:
 * `DevicePolicyState`/`PolicyKey` sind `@SystemApi` und stehen im öffentlichen SDK gar nicht zur
 * Verfügung (nachgeprüft gegen `android-37.0/android.jar` — die Klassen fehlen dort schlicht).
 * `PolicyUpdateReceiver`/`PolicyUpdateResult`/`DevicePolicyIdentifiers` sind die öffentliche
 * Hälfte desselben Mechanismus und liefern genau die Information, um die es ging.
 *
 * **`exported="true"` — bewusst, mit bekannter Konsequenz.** Das System verschickt den Broadcast
 * paketgerichtet (`Intent.setPackage`), nicht komponenten-explizit; ein nicht exportierter
 * Empfänger bekäme ihn nie. `PolicyUpdateReceiver.onReceive` ist `final`, der Absender lässt sich
 * hier also auch nicht nachträglich prüfen. Das heißt: eine andere App auf dem Gerät kann eine
 * Rückmeldung *vortäuschen*. Tragbar, weil dieser Empfänger ausschließlich einen lesbaren
 * Diagnoseeintrag schreibt — er löst keine Aktion aus, ändert keine Richtlinie und erreicht keinen
 * presence-gated Pfad. Eine erfundene Zeile kann verwirren, nichts weiter; das Gegenteil (Wardens
 * echte Konflikte gar nicht zu sehen) wiegt schwerer. Anders als bei
 * [de.ble1st.warden.sentinelbridge.SentinelSignalReceiver] gibt es hier keine eigene
 * `signature`-Permission, mit der sich das absichern ließe: der Absender ist `system_server`, kein
 * Geschwister-APK mit Wardens Zertifikat.
 *
 * `directBootAware="true"` aus demselben Grund wie [WardenDeviceAdminReceiver]: die Reconciliation
 * beim Booten setzt Richtlinien, bevor das Gerät je entsperrt wurde — die Rückmeldungen dazu
 * kommen im BFU-Fenster an. [PolicyConflictStore] liegt deshalb im Device-Protected-Bereich.
 */
class WardenPolicyUpdateReceiver : PolicyUpdateReceiver() {

    override fun onPolicySetResult(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult,
    ) {
        record(context, policyIdentifier, policyUpdateResult, source = "gesetzt")
    }

    /** Feuert, wenn sich eine bereits gesetzte Richtlinie *nachträglich* ändert — genau der Fall,
     * in dem ein zweiter Admin Wardens Zustand später überschreibt, ohne dass Warden selbst gerade
     * etwas getan hätte. */
    override fun onPolicyChanged(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult,
    ) {
        record(context, policyIdentifier, policyUpdateResult, source = "nachträglich geändert")
    }

    private fun record(
        context: Context,
        policyIdentifier: String,
        result: PolicyUpdateResult,
        source: String,
    ) {
        // Defense-in-Depth: obwohl `PolicyUpdateReceiver.onReceive` final ist und der Absender
        // sich nicht im klassischen Sinn prüfen lässt, kann `Binder.getCallingUid()` in den
        // Callback-Methoden abgefragt werden. Der legitime Absender ist `system_server`
        // (UID Process.SYSTEM_UID). Eine fremde App, die den Broadcast fälscht, hat eine andere
        // UID — der Eintrag wird dann als "ungeprüfter Absender" markiert, statt still als
        // vertrauenswürdig behandelt zu werden.
        val callerUid = Binder.getCallingUid()
        val trustedSender = callerUid == Process.SYSTEM_UID
        val outcome = toOutcome(result.resultCode)
        runCatching {
            PolicyConflictStore.record(
                context,
                PolicyConflictRecord(
                    policyIdentifier = if (trustedSender) policyIdentifier else "[ungeprüft] $policyIdentifier",
                    outcome = outcome,
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "Richtlinien-Rückmeldung konnte nicht gespeichert werden", it) }

        // Nur Problemfälle ins Audit-Log: eine erfolgreich gesetzte Richtlinie ist der Normalfall
        // und würde das Log in derselben Weise fluten, aus der heraus erfolgreiche
        // `BusCommand.READ`-Aufrufe 2026-08-28 wieder herausgenommen wurden.
        if (outcome.isProblem || !trustedSender) {
            runCatching {
                wardenAuditLog(context).append(
                    priority = if (trustedSender) Log.WARN else Log.ERROR,
                    tag = TAG,
                    message = "Richtlinie $policyIdentifier ($source): ${outcome.label}" +
                        if (!trustedSender) " [ungeprüfter Absender, UID=$callerUid]" else "",
                )
            }.onFailure { Log.w(TAG, "Audit-Eintrag zur Richtlinien-Rückmeldung fehlgeschlagen", it) }
        }
    }

    /** Die einzige Stelle, an der Androids Zahlenwerte auf das framework-freie
     * [PolicyUpdateOutcome] treffen — s. dessen Klassendoc. Ein unbekannter Code (neuere
     * Android-Version mit zusätzlichem Ergebnis) fällt auf [PolicyUpdateOutcome.UNBEKANNT] und
     * gilt damit als Problem, nicht als Erfolg: dieselbe "Unsicherheit nie als Entwarnung"-Regel
     * wie überall. */
    private fun toOutcome(resultCode: Int): PolicyUpdateOutcome = when (resultCode) {
        PolicyUpdateResult.RESULT_POLICY_SET -> PolicyUpdateOutcome.GESETZT
        PolicyUpdateResult.RESULT_POLICY_CLEARED -> PolicyUpdateOutcome.ZURUECKGENOMMEN
        PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY -> PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN
        PolicyUpdateResult.RESULT_FAILURE_HARDWARE_LIMITATION -> PolicyUpdateOutcome.HARDWARE_GRENZE
        PolicyUpdateResult.RESULT_FAILURE_STORAGE_LIMIT_REACHED -> PolicyUpdateOutcome.SPEICHERGRENZE
        else -> PolicyUpdateOutcome.UNBEKANNT
    }

    private companion object {
        const val TAG = "PolicyUpdate"
    }
}
