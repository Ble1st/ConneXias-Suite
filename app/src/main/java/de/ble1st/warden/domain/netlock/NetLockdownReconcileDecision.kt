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
