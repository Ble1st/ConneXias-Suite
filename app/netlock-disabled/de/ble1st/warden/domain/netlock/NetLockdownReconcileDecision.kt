// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 und
// warden-netzsperre-feature-2026-08-27-Memo) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr,
// Ursache unbekannt. Diese Datei liegt deshalb bewusst außerhalb jedes Gradle-Source-Sets
// (app/netlock-disabled/ statt app/src/main/java/) — wird NICHT mitkompiliert, ist nirgendwo
// verkabelt. Zum Reaktivieren: Verzeichnis zurück nach app/src/main/java/... verschieben, alle
// Wiederverkabelungsstellen aus dem Deaktivierungs-Commit rückgängig machen (siehe dessen
// Commit-Message für die vollständige Liste), Kernfehler zuerst klären.

package de.ble1st.warden.domain.netlock

/**
 * "Netz-Sperre" (2026-08-27): reine Soll-vs-Ist-Entscheidungslogik für
 * [de.ble1st.warden.netlock.NetLockdownController.reconcile] — extrahiert aus dem dortigen
 * `when`-Block, damit sie ohne Android-Framework/DPM testbar ist (s. `CLAUDE.md`s
 * Decision/Executor-Trennung). [desired] `null` bedeutet "noch nie ein Soll-Zustand persistiert"
 * (frischer Boot vor der ersten Nutzer-Interaktion mit Netz-Sperre) — dann wird nichts unternommen,
 * derselbe "kein Soll-Zustand = nichts tun"-Grundsatz wie andere Reconciler in diesem Projekt.
 */
object NetLockdownReconcileDecision {

    sealed class Action {
        data object Arm : Action()
        data object Disarm : Action()
        data object NoOp : Action()
    }

    fun action(desired: Boolean?, actual: Boolean): Action = when {
        desired == null -> Action.NoOp
        desired && !actual -> Action.Arm
        !desired && actual -> Action.Disarm
        else -> Action.NoOp
    }
}
