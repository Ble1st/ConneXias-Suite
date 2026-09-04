package de.ble1st.warden.domain.failsafe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [FailsafeResponseMessage] ist das prüfende Ende einer Nachricht, deren signierendes Ende in
 * einem anderen Repo-Teil und einer anderen Sprache liegt (`rust/engine/src/bin/
 * failsafe_keytool.rs`, läuft auf der Air-Gap-Maschine). Weichen die beiden auch nur um ein Byte
 * voneinander ab, ist der Offline-Failsafe unbenutzbar — und zwar genau dann, wenn er gebraucht
 * wird, also im denkbar schlechtesten Moment.
 *
 * Deshalb steht in [matchesSharedTestVector] ein **fest eingetragener Testvektor**, kein
 * nachgerechneter Erwartungswert: derselbe Vektor ist im Rust-Keytool hinterlegt. Ein Test, der
 * die Erwartung mit derselben Formel berechnet, die er prüfen soll, würde eine gemeinsame
 * Formeländerung auf beiden Seiten stillschweigend mitgehen — genau der Fehler, den dieser Test
 * fangen muss.
 */
class FailsafeResponseMessageTest {

    private val challenge = ByteArray(32) { 0xAA.toByte() }
    private val credential = "korrekthorsebatterie"

    /** Muss byteweise identisch zum gleichnamigen Vektor in `failsafe_keytool.rs` bleiben. */
    private val sharedVectorHex =
        "77617264656e3a6661696c736166653a76320020" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "564543640357e7fd1f0fc0b9c2ca3246e986e3de0f502c040c0663b61dfcb27f"

    @Test
    fun matchesSharedTestVector() {
        assertEquals(sharedVectorHex, FailsafeResponseMessage.build(challenge, credential).toHex())
    }

    @Test
    fun layoutIsTagThenLengthThenChallengeThenDigest() {
        val message = FailsafeResponseMessage.build(challenge, credential)
        val tag = FailsafeResponseMessage.DOMAIN_TAG.toByteArray(Charsets.UTF_8)

        assertArrayEquals(tag, message.copyOfRange(0, tag.size))
        assertArrayEquals(byteArrayOf(0x00, 0x20), message.copyOfRange(tag.size, tag.size + 2))
        assertArrayEquals(challenge, message.copyOfRange(tag.size + 2, tag.size + 34))
        assertEquals(tag.size + 2 + 32 + 32, message.size)
    }

    /**
     * Der eigentliche Fund vom 2026-08-28: vorher war die neue Geräte-PIN gar nicht Teil der
     * signierten Nachricht, eine abgefangene Antwort ließ sich also mit einer beliebigen anderen
     * PIN erneut einreichen.
     */
    @Test
    fun differentCredentialYieldsDifferentMessage() {
        assertNotEquals(
            FailsafeResponseMessage.build(challenge, "passwort-eins-lang").toHex(),
            FailsafeResponseMessage.build(challenge, "passwort-zwei-lang").toHex(),
        )
    }

    @Test
    fun differentChallengeYieldsDifferentMessage() {
        assertNotEquals(
            FailsafeResponseMessage.build(ByteArray(32) { 0x01 }, credential).toHex(),
            FailsafeResponseMessage.build(ByteArray(32) { 0x02 }, credential).toHex(),
        )
    }

    /**
     * Der Domain-Trenner macht die Umstellung selbst sicher: eine Signatur nach dem alten Schema
     * (über die nackte Challenge) kann unter dem neuen nicht gelten.
     */
    @Test
    fun neverEqualsBareChallenge() {
        assertNotEquals(challenge.toHex(), FailsafeResponseMessage.build(challenge, credential).toHex())
    }

    /** Das Längenpräfix hält Challenges unterschiedlicher Länge auseinander — ohne es wären bei
     * variabler Länge zwei verschiedene Eingabepaare auf dieselbe Bytefolge abbildbar. */
    @Test
    fun challengeLengthIsEncoded() {
        val short = FailsafeResponseMessage.build(ByteArray(16), credential)
        val long = FailsafeResponseMessage.build(ByteArray(32), credential)
        val tagSize = FailsafeResponseMessage.DOMAIN_TAG.toByteArray(Charsets.UTF_8).size

        assertArrayEquals(byteArrayOf(0x00, 0x10), short.copyOfRange(tagSize, tagSize + 2))
        assertArrayEquals(byteArrayOf(0x00, 0x20), long.copyOfRange(tagSize, tagSize + 2))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
