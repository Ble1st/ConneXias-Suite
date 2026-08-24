package de.ble1st.warden.presence

import android.app.Activity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WardenLock (Finalisierungsphase, 2026-08-24, auf Nutzerwunsch): App-weiter Presence-Zustand für
 * den Zeitraum, in dem Warden im Vordergrund ist. Schließt die bislang bestehende Lücke, dass
 * [de.ble1st.warden.ui.WardenStatusActivity] keinerlei Zugriffs-Gate hatte — `FLAG_SECURE`
 * verhindert nur Screenshots, nicht den Zugriff selbst; wer ein entsperrtes Gerät kurz in der Hand
 * hatte, konnte bislang ungeprüft jeden Safeguard umschalten.
 *
 * **Bewusst rein im Prozessspeicher, keine Persistenz:** ein Prozess-Tod verlangt ohnehin einen
 * frischen Nachweis, sobald der Prozess neu startet — dieselbe "je frischer, desto besser"-Haltung
 * wie [de.ble1st.warden.crypto.PresenceGate] (dort: Biometrie-Timeout 0). Ein persistiertes
 * "war schon mal entsperrt"-Flag wäre hier ein Rückschritt, kein Feature.
 *
 * **Invalidierung ausschließlich über `ProcessLifecycleOwner`** (s. `WardenApplication.onCreate`):
 * `ON_STOP` heißt "keine Warden-Activity mehr sichtbar" — Navigation zwischen Wardens eigenen
 * Activities (z. B. Dashboard → Safeguards-Screen → zurück) invalidiert **nicht**, nur ein
 * tatsächliches Verlassen der App (Home, App-Wechsel, Bildschirmsperre) tut das. Das unterscheidet
 * WardenLock von einer reinen Activity-`onStop()`-Prüfung, die bei jeder internen Navigation
 * fälschlich auslösen würde.
 *
 * **Reichweite über den reinen Dashboard-Zugriff hinaus:** ein erfolgreicher
 * [WardenLockActivity]-Durchlauf deckt auf ausdrücklichen Nutzerwunsch auch die vier real
 * verkabelten [de.ble1st.warden.domain.presence.SensitiveAction]s ab (dort
 * `allowsSessionPresence`) — kein zweiter, separater Prompt für Reboot & Co. innerhalb derselben
 * App-Sitzung. `WIPE_DATA` bleibt strukturell ausgenommen, s. dortiges Klassendoc.
 * [de.ble1st.warden.failsafe.FailsafeActivity] bleibt komplett außerhalb dieser Prüfung — sie
 * ersetzt WardenLock durch ihren eigenen Ed25519-Challenge/Response-Nachweis; ein von WardenLock
 * abhängiger Recovery-Pfad wäre ein echtes Aussperr-Risiko, sobald der PIN-Blob korrupt und keine
 * Biometrie eingerichtet ist.
 */
class WardenLockSession {
    private val authenticated = AtomicBoolean(false)

    fun isAuthenticated(): Boolean = authenticated.get()

    /** Von [WardenLockActivity] nach einem erfolgreichen Presence-Nachweis aufgerufen. */
    fun markAuthenticated() {
        authenticated.set(true)
    }

    /** Von `WardenApplication`s `ProcessLifecycleOwner`-Beobachtung aufgerufen, sobald keine
     * Warden-Activity mehr sichtbar ist (`ON_STOP`). */
    fun invalidate() {
        authenticated.set(false)
    }
}

/**
 * Von jeder Activity aufgerufen, die WardenLock durchsetzen soll (`onResume()`), außer
 * [WardenLockActivity] selbst (die stellt den Nachweis erst her) und
 * [de.ble1st.warden.failsafe.FailsafeActivity] (eigener Presence-Ersatz, s.
 * [WardenLockSession]-Klassendoc). Beendet die Activity sofort, wenn kein gültiger Nachweis
 * (mehr) vorliegt — der Nutzer landet damit automatisch auf der nächsttieferen Activity im
 * Task-Stack, bis [de.ble1st.warden.ui.WardenStatusActivity] erreicht ist, die statt sich selbst
 * zu beenden [WardenLockActivity] öffnet. Gibt `true` zurück, wenn die Activity beendet wurde
 * (Aufrufer sollte danach nichts mehr tun).
 */
fun Activity.finishIfWardenLockSessionMissing(session: WardenLockSession): Boolean {
    if (session.isAuthenticated()) return false
    finish()
    return true
}
