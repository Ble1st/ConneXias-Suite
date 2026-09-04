package de.ble1st.warden.domain.clipboard

import de.ble1st.warden.domain.clipboard.SensitiveContentDetector.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitiveContentDetectorTest {

    @Test
    fun plainShortTextIsNotFlagged() {
        assertNull(SensitiveContentDetector.detect("hello world"))
    }

    @Test
    fun twelveWordLowercasePhraseIsFlaggedAsSeedPhraseLike() {
        val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        assertEquals(Category.SEED_PHRASE_LIKE, SensitiveContentDetector.detect(phrase))
    }

    @Test
    fun seedPhraseLikeRunEmbeddedInOtherTextIsStillFound() {
        val text = "please see: abandon ability able about above absent absorb abstract absurd abuse access accident -- thanks"
        assertEquals(Category.SEED_PHRASE_LIKE, SensitiveContentDetector.detect(text))
    }

    @Test
    fun elevenWordsIsNotEnoughForSeedPhrase() {
        val phrase = "abandon ability able about above absent absorb abstract absurd abuse access"
        assertNull(SensitiveContentDetector.detect(phrase))
    }

    @Test
    fun validLuhnCardNumberIsFlaggedAsCreditCardLike() {
        // 4111111111111111 is the well-known Visa test number, passes Luhn.
        assertEquals(Category.CREDIT_CARD_LIKE, SensitiveContentDetector.detect("4111111111111111"))
    }

    @Test
    fun cardNumberWithSpacesStillDetected() {
        assertEquals(Category.CREDIT_CARD_LIKE, SensitiveContentDetector.detect("card: 4111 1111 1111 1111 exp 12/30"))
    }

    @Test
    fun invalidLuhnDigitRunIsNotFlagged() {
        assertNull(SensitiveContentDetector.detect("4111111111111112"))
    }

    @Test
    fun knownApiKeyPrefixIsFlagged() {
        assertEquals(Category.API_KEY_LIKE, SensitiveContentDetector.detect("sk-abcdefghijklmnopqrstuvwxyz123456"))
    }

    @Test
    fun genericMixedRandomTokenIsFlagged() {
        assertEquals(Category.API_KEY_LIKE, SensitiveContentDetector.detect("aB3dEf7hIj9kLm2nOp5qRs8tUv1wXyZ0"))
    }

    @Test
    fun shortAlphanumericTokenIsNotFlagged() {
        assertNull(SensitiveContentDetector.detect("Passwort123"))
    }
}
