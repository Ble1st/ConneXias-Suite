package de.ble1st.warden.domain.failsafe

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Baut die Bytefolge, über die die Offline-Failsafe-Antwort tatsächlich signiert wird
 * (2026-08-28, aus der Code-/Sicherheitsanalyse).
 *
 * **Der Fund:** bis dahin signierte die Betreiberin auf der Air-Gap-Maschine nur die **Challenge**,
 * und `OfflineFailsafeExecutor.submitResponse` prüfte auch nur diese. Die zweite, mindestens
 * ebenso folgenreiche Eingabe desselben Aufrufs — `newDeviceCredential`, also die neue
 * Sperrbildschirm-PIN des Geräts — ging in nichts Signiertes ein. Wer eine gültige Antwort in die
 * Hände bekam (sie wird auf einem zweiten Rechner berechnet, abgelesen und abgetippt, sie ist kein
 * On-Device-Geheimnis), konnte sie innerhalb der Challenge-TTL erneut einreichen und dabei eine
 * **beliebige eigene** Geräte-PIN setzen. Die Signatur bewies "jemand mit dem Wartungsschlüssel
 * wollte einen Failsafe" — nicht "… und zwar mit *dieser* neuen PIN".
 *
 * **Die Bindung:** signiert wird jetzt `TAG ‖ u16be(challenge.size) ‖ challenge ‖
 * SHA-256(credential)`. Die neue PIN selbst verlässt die Air-Gap-Maschine nicht — nur ihr Digest
 * steckt in der signierten Nachricht, und der wird auf dem Gerät aus der eingetippten PIN neu
 * berechnet. Eine Antwort, die zu einer anderen PIN gehört, verifiziert nicht mehr.
 *
 * **Warum ein Domain-Trenner ([DOMAIN_TAG]) davor:** er macht die Umstellung selbst sicher. Eine
 * Signatur nach dem alten Schema (über die nackte Challenge) kann unter dem neuen nicht mehr
 * gelten, und umgekehrt — die beiden Nachrichtenräume sind disjunkt, statt sich zufällig zu
 * überlappen. Das `v2` im Tag ist deshalb Teil der Sicherheitsaussage, nicht bloß Kosmetik: eine
 * künftige dritte Fassung erhält `v3` und ist damit automatisch inkompatibel zu beiden.
 *
 * **Warum die Längenangabe:** `FailsafeChallengeStore.issueNewChallenge` nimmt `lengthBytes` als
 * Parameter, die Challenge ist also nicht strukturell auf 32 Byte festgelegt. Ohne Längenpräfix
 * wären bei variabler Länge zwei verschiedene (Challenge, Digest)-Paare auf dieselbe Bytefolge
 * abbildbar — heute nicht ausnutzbar, aber genau die Art stiller Annahme, die eine spätere
 * Änderung an einer ganz anderen Stelle gefährlich macht.
 *
 * **Muss byteweise identisch in `rust/engine/src/bin/failsafe_keytool.rs` gespiegelt sein** —
 * die beiden Implementierungen sind das signierende und das prüfende Ende derselben Nachricht.
 * Wer hier etwas ändert, ändert dort mit, sonst ist der Failsafe unbenutzbar.
 */
object FailsafeResponseMessage {

    const val DOMAIN_TAG: String = "warden:failsafe:v2"

    fun build(challenge: ByteArray, newDeviceCredential: String): ByteArray {
        val challengeLength = challenge.size
        require(challengeLength in 0..0xFFFF) {
            "Challenge zu lang für 2-Byte-Längenpräfix ($challengeLength Byte)"
        }
        val credentialDigest = MessageDigest.getInstance("SHA-256")
            .digest(newDeviceCredential.toByteArray(StandardCharsets.UTF_8))
        return DOMAIN_TAG.toByteArray(StandardCharsets.UTF_8) +
            byteArrayOf((challengeLength ushr 8).toByte(), challengeLength.toByte()) +
            challenge +
            credentialDigest
    }
}
