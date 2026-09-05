package de.ble1st.warden.domain.attestation

/**
 * Verified-Boot-Zustand aus dem Key-Attestation-Record (`RootOfTrust.verifiedBootState`,
 * AOSP `security/keystore/attestation`). Anders als [de.ble1st.warden.integrity.RootIndicatorScanner]
 * ist das **kein Indiz, sondern ein Nachweis**: der Wert stammt aus dem Attestation-Zertifikat, das
 * die Hardware (TEE/StrongBox) selbst signiert und dessen Kette bis zu Googles Attestation-Root
 * führt — eine App im Sandbox-Sichtfeld kann ihn nicht fälschen, ein Root-Kit im Userspace ebenso
 * wenig.
 *
 * Die vier Werte entsprechen exakt der ASN.1-`ENUMERATED` im Record:
 * - [VERIFIED] (0) — vollständige Vertrauenskette bis zum OEM-Schlüssel, Bootloader gesperrt.
 * - [SELF_SIGNED] (1) — Bootloader entsperrt und mit einem *nutzereigenen* Schlüssel wieder
 *   gesperrt (custom ROM mit eigenem AVB-Key, z. B. GrapheneOS). Nicht dasselbe wie [UNVERIFIED]:
 *   die Kette ist intakt, nur der Vertrauensanker ist nicht der des OEM.
 * - [UNVERIFIED] (2) — Bootloader entsperrt, keine Verifikation. Der klassische Root-Fall.
 * - [FAILED] (3) — Verifikation lief und ist **fehlgeschlagen**.
 *
 * [UNBEKANNT] ist Wardens eigener fünfter Wert für "nicht gelesen/nicht lesbar" und folgt der
 * projektweiten Fail-Safe-Regel: ein fehlgeschlagener Lesevorgang darf nie wie ein bestandener
 * aussehen — er wird aber auch nicht als Verstoß gewertet (s.
 * [AttestationDecision], "Unsicherheit wird nicht bestraft").
 */
enum class VerifiedBootState(val label: String) {
    VERIFIED("Verified — Bootloader gesperrt, OEM-Kette intakt"),
    SELF_SIGNED("Self-signed — eigener Verified-Boot-Schlüssel (z. B. Custom-ROM)"),
    UNVERIFIED("Unverified — Bootloader entsperrt, keine Prüfung"),
    FAILED("Failed — Verified Boot ist fehlgeschlagen"),
    UNBEKANNT("Unbekannt — nicht auslesbar"),
    ;

    companion object {
        /** ASN.1-`ENUMERATED` → Enum. Ein unbekannter Zahlenwert wird [UNBEKANNT], nicht etwa
         * [VERIFIED] — dieselbe Richtung wie überall sonst: im Zweifel nichts behaupten. */
        fun fromAsn1(value: Int): VerifiedBootState = when (value) {
            0 -> VERIFIED
            1 -> SELF_SIGNED
            2 -> UNVERIFIED
            3 -> FAILED
            else -> UNBEKANNT
        }
    }
}

/**
 * Wo der attestierte Schlüssel tatsächlich liegt (`attestationSecurityLevel` im Record). Ergänzt
 * [de.ble1st.warden.domain.encryption.KeystoreSecurityLevel] um die Unterscheidung, die dieser
 * fehlt: `KeyInfo.securityLevel` sagt "hardwaregebunden ja/nein", der Attestation-Record sagt
 * zusätzlich, **ob es StrongBox ist** — ein separater Sicherheitschip statt nur der TEE des
 * Hauptprozessors.
 */
enum class AttestationSecurityLevel(val label: String) {
    SOFTWARE("Software"),
    TRUSTED_ENVIRONMENT("TEE (Trusted Execution Environment)"),
    STRONG_BOX("StrongBox (separater Sicherheitschip)"),
    UNBEKANNT("Unbekannt"),
    ;

    companion object {
        fun fromAsn1(value: Int): AttestationSecurityLevel = when (value) {
            0 -> SOFTWARE
            1 -> TRUSTED_ENVIRONMENT
            2 -> STRONG_BOX
            else -> UNBEKANNT
        }
    }
}

/**
 * Das ausgelesene Ergebnis einer Key-Attestation, framework-frei — [de.ble1st.warden.integrity
 * .KeyAttestationReader] erzeugt es, [AttestationDecision] bewertet es, die UI zeigt es an.
 *
 * **Warum diese Klasse überhaupt existiert (2026-09-05):** Wardens Integritätsbild bestand bis
 * dahin aus `RootIndicatorScanner` (Dateisystem-Heuristik) und `KeystoreSecurityLevelReader`
 * (hardwaregebunden ja/nein). Beides beantwortet *nicht* die eigentliche Frage — ist der
 * Bootloader gesperrt, läuft ein unverändertes System, und wie alt ist der Sicherheitspatch. Genau
 * das steht signiert im Attestation-Record. Der [osPatchLevel] schließt nebenbei die Lücke, an der
 * die Security-Score-Kategorie "Update-Status" seinerzeit gestrichen wurde ("nichts in Warden kann
 * das beantworten", s. `warden/CLAUDE.md`) — sie kann es jetzt.
 *
 * **Grenzen, die ehrlich benannt gehören** (auch im UI-Text, s. `security_scanner_attestation_hint`):
 * Auf kompromittierten Geräten sind Attestation-Bypässe über geleakte Keyboxen dokumentiert, und
 * manche OEMs liefern fehlerhafte oder gar keine Attestation aus. Das hier ist deshalb eine
 * **Ergänzung** der Heuristiken, kein Ersatz — beide Signale stehen nebeneinander im
 * Integritätsblock.
 *
 * @property verifiedBootState Kern-Aussage, s. [VerifiedBootState].
 * @property deviceLocked `RootOfTrust.deviceLocked` — `null`, wenn nicht gelesen.
 * @property securityLevel Wo der Schlüssel liegt, s. [AttestationSecurityLevel].
 * @property osPatchLevel Sicherheitspatch-Stand als `YYYYMM` (z. B. `202508`), `null` wenn nicht
 *   im Record enthalten. Ältere Keymaster-Versionen liefern ihn nicht immer.
 * @property osVersion Android-Version als `AABBCC` (z. B. `160000` für 16.0.0), `null` wenn nicht
 *   enthalten.
 * @property chainTrusted `true`, wenn die Zertifikatskette bis zu einem bekannten Google-
 *   Attestation-Root führt; `false` bei selbstsignierter/unbekannter Wurzel; `null`, wenn die
 *   Prüfung nicht durchgeführt werden konnte. S. [de.ble1st.warden.integrity.KeyAttestationReader]
 *   für die Begründung, warum die Wurzel gegen einen fest eingebauten Fingerabdruck geprüft wird.
 */
data class DeviceAttestation(
    val verifiedBootState: VerifiedBootState,
    val deviceLocked: Boolean?,
    val securityLevel: AttestationSecurityLevel,
    val osPatchLevel: Int?,
    val osVersion: Int?,
    val chainTrusted: Boolean?,
) {
    companion object {
        /** Vollständig unbekanntes Ergebnis — der Rückgabewert, wenn Attestation auf diesem Gerät
         * gar nicht verfügbar ist (alte/fehlerhafte Keymaster-Implementierung, Lesefehler). */
        val UNBEKANNT = DeviceAttestation(
            verifiedBootState = VerifiedBootState.UNBEKANNT,
            deviceLocked = null,
            securityLevel = AttestationSecurityLevel.UNBEKANNT,
            osPatchLevel = null,
            osVersion = null,
            chainTrusted = null,
        )
    }
}
