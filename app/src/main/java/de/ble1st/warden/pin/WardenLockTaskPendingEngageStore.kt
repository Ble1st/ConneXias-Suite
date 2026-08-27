package de.ble1st.warden.pin

import android.content.Context
import androidx.core.content.edit

/** [requiresConfirmation]: `true` heißt, der Abholpunkt (`de.ble1st.warden.ui
 * .WardenStatusActivity.consumePendingLockTaskEngage`) muss vor dem tatsächlichen Scharfschalten
 * noch einen Ja/Nein-Dialog zeigen (`LockdownTriggerProfile.STANDARD`), statt sofort zu feuern. */
data class PendingLockTaskEngage(val reason: String, val requiresConfirmation: Boolean)

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) — der eigentliche "Auslöser"-Übergabepunkt
 * zwischen Bedrohungserkennung (läuft im Hintergrund, s.
 * `de.ble1st.warden.appmanagement.SuspiciousAppScanController`, kein `Activity`-Kontext
 * verfügbar) und `startLockTask()` (verlangt laut Android-Dokumentation zwingend einen laufenden
 * `Activity`-Aufruf). [requestEngage] wird vom Scan-Pfad aufgerufen, sobald
 * `WardenLockTaskAutoEngageDecision` zustimmt; [consumeIfPending] wird von
 * `de.ble1st.warden.ui.WardenStatusActivity`s `onResume()` aufgerufen — die nächste Gelegenheit,
 * in der Warden selbst wieder im Vordergrund läuft, holt die Anforderung ab und stößt den
 * tatsächlichen `de.ble1st.warden.sentinelbridge.SentinelLockdownEngager.engage()`-Aufruf an.
 *
 * **Bewusst kein Vordergrund-Erzwingen (kein `USE_FULL_SCREEN_INTENT`/kein automatisches
 * Öffnen von Warden):** die zugehörige `ThreatSeverity.CRITICAL`-Sicherheitsbenachrichtigung
 * (`SuspiciousAppNotifier`, `IMPORTANCE_HIGH`-Kanal) macht bereits laut auf den Fund
 * aufmerksam; das eigentliche `startLockTask()` feuert erst, wenn die Betreiberin Warden als
 * Reaktion selbst öffnet — ein Mensch ist damit anwesend, wenn das Gerät real in den
 * Lock-Task-Modus wechselt, nicht ein unbeaufsichtigter Hintergrund-Trigger. Dieselbe
 * "im Zweifel ein Mensch schaut zu"-Vorsicht wie beim Notruf-Drill-Gate selbst.
 *
 * **"Lockdown-Auslöse-Profil" (2026-08-27):** dieselbe Ablage wird jetzt auch von
 * `de.ble1st.warden.sentinelbridge.SentinelQuickTile` genutzt (`onClick()` hat keinen
 * zuverlässigen `Activity`-Kontext, exakt dasselbe Problem wie der Bedrohungs-Scan-Pfad) — daher
 * das neue [PendingLockTaskEngage.requiresConfirmation]-Bit statt eines zweiten, separaten
 * Relay-Mechanismus. `requiresConfirmation` defaultet auf `false`: der bestehende Aufrufer
 * ([de.ble1st.warden.appmanagement.SuspiciousAppScanController]) verhält sich unverändert (feuert
 * weiterhin ohne Rückfrage).
 *
 * Schreibt/liest nur ein einzelnes Bit + einen Anzeigetext + ein zweites Bit — kein Envelope/keine
 * Verschlüsselung nötig, dieselbe Klartext-`SharedPreferences`-Begründung wie
 * [WardenLockTaskAutoEngageStore]: ein verlorener Zustand bedeutet höchstens "eine bereits
 * geloggte/benachrichtigte Bedrohung löst kein automatisches Engage mehr aus", kein
 * Sicherheitsverlust (die Benachrichtigung selbst bleibt unabhängig davon bestehen).
 */
object WardenLockTaskPendingEngageStore {
    private const val PREFS_NAME = "warden_lock_task_pending_engage"
    private const val KEY_PENDING = "pending"
    private const val KEY_REASON = "reason"
    private const val KEY_REQUIRES_CONFIRMATION = "requires_confirmation"

    fun requestEngage(context: Context, reason: String, requiresConfirmation: Boolean = false) {
        prefs(context).edit {
            putBoolean(KEY_PENDING, true)
            putString(KEY_REASON, reason)
            putBoolean(KEY_REQUIRES_CONFIRMATION, requiresConfirmation)
        }
    }

    /** `true` mit Grund, wenn eine Anforderung aussteht — atomar mit dem Zurücksetzen verknüpft
     * (derselbe "lesen == verbrauchen"-Vertrag wie `de.ble1st.warden.presence.PresenceProof
     * .consume`), damit ein zweiter, gleichzeitiger Aufrufer dieselbe Anforderung nicht doppelt
     * verarbeitet. */
    fun consumeIfPending(context: Context): PendingLockTaskEngage? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val reason = prefs.getString(KEY_REASON, null) ?: "Kritischer Verdachtsfund"
        val requiresConfirmation = prefs.getBoolean(KEY_REQUIRES_CONFIRMATION, false)
        prefs.edit {
            putBoolean(KEY_PENDING, false)
            remove(KEY_REASON)
            remove(KEY_REQUIRES_CONFIRMATION)
        }
        return PendingLockTaskEngage(reason, requiresConfirmation)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
