package de.ble1st.warden.domain.cellsecurity

import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/**
 * "Mobilfunkzellen-Auffälligkeitserkennung" (2026-08-29, Feature 2 aus
 * `docs/umsetzungsplan-7-features.md`, dort "IMSI-Catcher Detection" genannt) — reine Werte-Logik,
 * framework-frei testbar, dieselbe Trennung wie [de.ble1st.warden.domain.sim.SimChangeDecision].
 *
 * **Bewusst KEIN globaler "bekannte Zellen"-Abgleich**, anders als der ursprüngliche Plan
 * (`CellDatabase`, "periodisch mit Barbican-Servern synchronisiert" — es gibt in diesem Projekt
 * weder einen Server noch eine Zell-Datenbank, s. `CLAUDE.md`: Warden hat bewusst keinen
 * Fern-/Push-Kanal). Ein normal genutztes Telefon sieht auf jeder Fahrt/Reise ständig neue,
 * legitime Zellen — eine Positivliste würde dort ununterbrochen falsch schlagen. Stattdessen wird
 * nur der **letzte** Messwert gegen den **aktuellen** verglichen (dieselbe "ein-Wert-Baseline"-
 * Idee wie bei [de.ble1st.warden.domain.sim.SimChangeDecision]) und auf vier Anomalien geprüft, die
 * bei einem normalen Zellwechsel *zusammen* nicht auftreten:
 *
 * 1. [CellSecurityIndicator.AREA_CODE_CHANGED_SAME_CELL] — dieselbe Zell-ID, aber ein anderer
 *    LAC/TAC. Ein echter Mobilfunkmast ändert seinen LAC/TAC nicht, ohne dass sich auch die
 *    Zell-ID ändert; das kommt in echten Netzen praktisch nicht vor. Der mit Abstand
 *    verlässlichste der vier Indikatoren — reicht deshalb allein für [ThreatSeverity.CRITICAL].
 * 2. [CellSecurityIndicator.GENERATION_DOWNGRADE] — die registrierte Zelle meldet jetzt eine
 *    ältere Funkgeneration als beim letzten Messwert (z. B. LTE → GSM). Ein bekannter
 *    IMSI-Catcher-Trick (2G hat schwächere/keine Authentisierung) — aber auch der Alltagsfall
 *    "aus dem LTE-Gebiet in ein reines 2G-Funkloch gefahren". Allein nur [ThreatSeverity.WARNING].
 * 3. [CellSecurityIndicator.INVALID_AREA_CODE] — die aktuell registrierte Zelle liefert gar keinen
 *    LAC/TAC. **Unverifiziert** (kein Live-Test auf echter Hardware in dieser Session, s.
 *    Klassendoc-Fußnote unten) — bewusst nur [ThreatSeverity.WARNING], nie allein ausschlaggebend.
 * 4. [CellSecurityIndicator.SIGNAL_JUMP] — die Signalstärke der registrierten Zelle springt aus
 *    einem zuvor schwachen Empfang plötzlich stark nach oben (typisch für einen nahen, absichtlich
 *    stark sendenden Fremd-Sender). Ebenfalls nur [ThreatSeverity.WARNING] allein — echte
 *    Handover-Sprünge sind nicht ausgeschlossen.
 *
 * **Das hier ist ein Verdachts-Indikator, keine belastbare IMSI-Catcher-Erkennung**: eine
 * Nutzer-App kann auf Android weder auf das Baseband noch auf den tatsächlichen
 * Authentisierungs-Handshake zugreifen. Wie bei [de.ble1st.warden.registry.FactoryResetProtectionSafeguard]s
 * Dokumentation gilt: nicht als beweiskräftig behandeln, solange keine echte Feldverifikation
 * (bekannter Rogue-Basestation-Test) stattgefunden hat.
 */
object CellSecurityDecision {

    /** Ab dieser Sprungstärke (dBm) aus schwachem Empfang gilt ein Signalsprung als auffällig. */
    private const val SIGNAL_JUMP_THRESHOLD_DBM = 25

    /** Nur ein Sprung *aus* einem ohnehin schwachen Empfang zählt — ein Sprung zwischen zwei
     * bereits guten Werten ist der Alltag jedes Handovers. */
    private const val WEAK_SIGNAL_BASELINE_DBM = -95

    fun evaluate(previous: CellObservation?, current: CellObservation?): CellSecurityOutcome = when {
        current == null -> CellSecurityOutcome.NotReadable
        previous == null -> CellSecurityOutcome.BaselineEstablished(current)
        else -> {
            val indicators = buildSet {
                if (isAreaCodeChangedSameCell(previous, current)) add(CellSecurityIndicator.AREA_CODE_CHANGED_SAME_CELL)
                if (isGenerationDowngrade(previous, current)) add(CellSecurityIndicator.GENERATION_DOWNGRADE)
                if (isInvalidAreaCode(current)) add(CellSecurityIndicator.INVALID_AREA_CODE)
                if (isSignalJump(previous, current)) add(CellSecurityIndicator.SIGNAL_JUMP)
            }
            if (indicators.isEmpty()) {
                CellSecurityOutcome.Unchanged(current)
            } else {
                CellSecurityOutcome.Suspicious(current, indicators, severityFor(indicators))
            }
        }
    }

    private fun isAreaCodeChangedSameCell(previous: CellObservation, current: CellObservation): Boolean {
        val prevCell = previous.cellId ?: return false
        val currCell = current.cellId ?: return false
        val prevArea = previous.areaCode ?: return false
        val currArea = current.areaCode ?: return false
        return prevCell == currCell && prevArea != currArea
    }

    private fun isGenerationDowngrade(previous: CellObservation, current: CellObservation): Boolean {
        if (previous.generation == CellGeneration.UNKNOWN || current.generation == CellGeneration.UNKNOWN) return false
        return current.generation.ordinal < previous.generation.ordinal
    }

    private fun isInvalidAreaCode(current: CellObservation): Boolean =
        current.generation != CellGeneration.UNKNOWN && (current.areaCode == null || current.areaCode == 0)

    private fun isSignalJump(previous: CellObservation, current: CellObservation): Boolean {
        val prevDbm = previous.signalDbm ?: return false
        val currDbm = current.signalDbm ?: return false
        return prevDbm <= WEAK_SIGNAL_BASELINE_DBM && (currDbm - prevDbm) >= SIGNAL_JUMP_THRESHOLD_DBM
    }

    /** "Worst-signal-wins", dieselbe Regel wie [ThreatSeverity.highest] — aber
     * [CellSecurityIndicator.AREA_CODE_CHANGED_SAME_CELL] ist der einzige Einzelindikator, der
     * allein [ThreatSeverity.CRITICAL] rechtfertigt (s. Klassendoc); alle anderen brauchen
     * Verstärkung durch mindestens ein zweites gleichzeitiges Signal. */
    private fun severityFor(indicators: Set<CellSecurityIndicator>): ThreatSeverity = when {
        CellSecurityIndicator.AREA_CODE_CHANGED_SAME_CELL in indicators -> ThreatSeverity.CRITICAL
        indicators.size >= 2 -> ThreatSeverity.CRITICAL
        else -> ThreatSeverity.WARNING
    }
}

/** Funkgeneration der registrierten Zelle — Reihenfolge ist die Ordinal-Reihenfolge, auf der
 * [CellSecurityDecision] Downgrades erkennt; [UNKNOWN] steht bewusst außerhalb dieser Ordnung
 * (weder als Downgrade- noch als Upgrade-Fall gewertet, s. [CellSecurityDecision.isGenerationDowngrade]). */
enum class CellGeneration {
    UNKNOWN,
    GSM_2G,
    UMTS_3G,
    LTE_4G,
    NR_5G,
}

/**
 * Normalisierte Momentaufnahme der aktuell *registrierten* Zelle (nicht der Nachbarzellen) —
 * `null`-Felder bedeuten jeweils "vom Gerät/Netz nicht geliefert", nie "kein Anhaltspunkt für
 * Auffälligkeit" (s. [CellSecurityDecision]-Prüfungen, die `null`-Felder konsequent überspringen
 * statt sie als 0/false zu deuten).
 */
data class CellObservation(
    val mcc: String?,
    val mnc: String?,
    val cellId: Long?,
    val areaCode: Int?,
    val generation: CellGeneration,
    val signalDbm: Int?,
)

enum class CellSecurityIndicator(val label: String) {
    AREA_CODE_CHANGED_SAME_CELL("Gebietscode bei gleicher Zell-ID geändert"),
    GENERATION_DOWNGRADE("Funkgeneration heruntergestuft"),
    INVALID_AREA_CODE("Kein Gebietscode gemeldet"),
    SIGNAL_JUMP("Plötzlicher Signalsprung"),
}

sealed class CellSecurityOutcome {
    /** Kein verwertbarer Messwert (Berechtigung fehlt, Standortdienst aus, kein Empfang) — nichts
     * tun, keine Baseline schreiben, s. [de.ble1st.warden.cellsecurity.CellObservationReader]. */
    data object NotReadable : CellSecurityOutcome()

    /** Erster erfolgreicher Messwert: merken, aber nicht reagieren — sonst wäre das Einschalten
     * der Funktion selbst der Auslöser. */
    data class BaselineEstablished(val observation: CellObservation) : CellSecurityOutcome()

    /** Baseline muss trotzdem fortgeschrieben werden (normale Zellwechsel sind der Normalfall). */
    data class Unchanged(val observation: CellObservation) : CellSecurityOutcome()

    data class Suspicious(
        val observation: CellObservation,
        val indicators: Set<CellSecurityIndicator>,
        val severity: ThreatSeverity,
    ) : CellSecurityOutcome()
}

/**
 * Was bei einer erkannten Auffälligkeit passieren soll — dieselbe abgestufte Idee wie
 * [de.ble1st.warden.domain.sim.SimChangeReaction], aber ohne dessen `SPERREN`-Stufe: das Signal
 * hier ist per Klassendoc deutlich rauschbehafteter als ein SIM-Wechsel, ein reflexhaftes
 * `lockNow()` bei jedem einzelnen Fund (auch nur [ThreatSeverity.WARNING]) wäre eine Zumutung im
 * Alltag. [de.ble1st.warden.cellsecurity.CellSecurityController] wendet [NETZWERK_SPERREN]/
 * [NEUSTART] deshalb ohnehin nur bei [ThreatSeverity.CRITICAL] an, nie bei bloßem `WARNING`.
 */
enum class CellSecurityReaction {
    /** Nur Audit-Log-Eintrag und Benachrichtigung. Default beim Einschalten. */
    NUR_MELDEN,

    /** Zusätzlich [de.ble1st.warden.netlock.NetLockdownController.arm] — derselbe reale Netz-
     * Kill-Switch, den auch der Dashboard-Schalter in `NetworkScreen` bedient (kein fiktiver
     * "Barbican-Server", s. `CLAUDE.md`-"Netz-Sperre"-Abschnitt). Reversibel über denselben
     * Schalter. */
    NETZWERK_SPERREN,

    /** Zusätzlich `DevicePolicyManager.reboot()` — zurück in den BFU-Zustand, derselbe Reflex wie
     * [de.ble1st.warden.domain.sim.SimChangeReaction.NEUSTART]/[de.ble1st.warden.domain.failedattempts.FailedAttemptsRebootDecision]. */
    NEUSTART,
    ;

    val label: String
        get() = when (this) {
            NUR_MELDEN -> "Nur melden"
            NETZWERK_SPERREN -> "Netz-Sperre aktivieren"
            NEUSTART -> "Neustart (BFU)"
        }
}
