package de.ble1st.warden.domain.sim

/**
 * "SIM-Wechsel-Erkennung" (2026-08-28, aus der Lückenanalyse) — der einzige Auslöser in diesem
 * Projekt, der ohne die Besitzerin funktioniert. Warden hat bewusst keinen Fern-/Push-Kanal
 * (s. `SensitiveAction.LOCK_NOW`-Klassendoc), damit war der Fall "Gerät ist weg" bislang gar nicht
 * abgedeckt: `LOCK_NOW` erreicht man nur am entsperrten Gerät. Ein SIM-Tausch ist dagegen genau
 * das, was nach einem Diebstahl lokal beobachtbar passiert — ohne Netz, ohne Server, ohne Standort.
 *
 * Reine Werte-Logik ([SimChangeOutcome] aus zwei Fingerabdrücken), framework-frei testbar; das
 * Lesen der echten SIM-Daten und das Reagieren liegen in `de.ble1st.warden.sim.*`.
 *
 * **Der Unterschied zwischen "nicht lesbar" und "keine SIM" ist sicherheitsrelevant** und deshalb
 * hier explizit modelliert: `null` heißt "konnte nicht ermittelt werden" (fehlende Berechtigung,
 * Telefonie-Dienst nicht verfügbar) — daraus darf **nie** eine Reaktion folgen, sonst löst ein
 * Berechtigungsproblem einen Neustart aus. Der leere String heißt dagegen "erfolgreich gelesen,
 * es steckt keine SIM (mehr) drin" — das ist ein echtes Signal, denn eine entfernte SIM ist der
 * erste Griff nach einem Diebstahl.
 */
object SimChangeDecision {

    /** Fingerabdruck für "erfolgreich gelesen, keine SIM vorhanden". */
    const val NO_SIM_FINGERPRINT = ""

    fun evaluate(storedFingerprint: String?, currentFingerprint: String?): SimChangeOutcome = when {
        currentFingerprint == null -> SimChangeOutcome.NotReadable
        storedFingerprint == null -> SimChangeOutcome.BaselineEstablished(currentFingerprint)
        storedFingerprint == currentFingerprint -> SimChangeOutcome.Unchanged
        else -> SimChangeOutcome.Changed(
            newFingerprint = currentFingerprint,
            simRemoved = currentFingerprint == NO_SIM_FINGERPRINT,
        )
    }
}

sealed class SimChangeOutcome {
    /** Kein verwertbarer Messwert — nichts tun, keine Baseline schreiben. */
    data object NotReadable : SimChangeOutcome()

    /** Erster erfolgreicher Messwert (Funktion gerade aktiviert oder App neu installiert):
     * merken, aber **nicht** reagieren — sonst wäre das Einschalten selbst der Auslöser. */
    data class BaselineEstablished(val fingerprint: String) : SimChangeOutcome()

    data object Unchanged : SimChangeOutcome()

    /** [simRemoved] unterscheidet "andere SIM eingelegt" von "SIM entfernt" — nur für die
     * Formulierung der Meldung, die Reaktion ist dieselbe. */
    data class Changed(val newFingerprint: String, val simRemoved: Boolean) : SimChangeOutcome()
}

/**
 * Was bei einem erkannten Wechsel passieren soll. Bewusst abgestuft statt fest verdrahtet: ein
 * SIM-Wechsel ist auch der Alltagsfall "neuer Mobilfunkvertrag" oder "Auslandsreise mit lokaler
 * SIM" — eine nicht abschaltbare Reaktion wäre in dieser Rolle eine Falle statt eines Schutzes.
 */
enum class SimChangeReaction {
    /** Nur Audit-Log-Eintrag und Benachrichtigung. Default beim Einschalten. */
    NUR_MELDEN,

    /** Zusätzlich `DevicePolicyManager.lockNow()` — Biometrie/Smart Lock werden entwertet, die
     * vollen Zugangsdaten sind wieder nötig. */
    SPERREN,

    /** Zusätzlich `DevicePolicyManager.reboot()` — zurück in den BFU-Zustand, derselbe Reflex wie
     * [de.ble1st.warden.domain.failedattempts.FailedAttemptsRebootDecision] und der Duress-PIN. */
    NEUSTART,
    ;

    val label: String
        get() = when (this) {
            NUR_MELDEN -> "Nur melden"
            SPERREN -> "Gerät sofort sperren"
            NEUSTART -> "Neustart (BFU)"
        }
}
