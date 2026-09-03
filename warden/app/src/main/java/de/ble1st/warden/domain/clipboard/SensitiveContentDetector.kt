package de.ble1st.warden.domain.clipboard

/**
 * "Sensible-Einfügung-Alarm" (2026-09-03, Ideenliste Punkt 1 aus dem ClipboardGuard-
 * Folgegespräch) — baut auf der bereits vorhandenen Erfassungspipeline auf
 * ([de.ble1st.warden.clipboard.ClipboardAccessController]): statt den erfassten Text nur passiv
 * in die Historie zu schreiben, prüft diese reine, framework-freie Funktion ihn gegen drei grobe
 * Muster und löst bei Treffer eine sofortige Benachrichtigung aus ("du hast gerade etwas
 * Sensibles in App X eingefügt").
 *
 * **Ausdrücklich Heuristiken, keine verlässliche Erkennung** — dieselbe Ehrlichkeit wie
 * `CellSecurityDecision`s eigener Klassendoc-Vorbehalt ("Verdachtsheuristik, keine verifizierte
 * Erkennung"). Falsch-Negative sind der Normalfall (jede Formulierung, jedes Format, das die
 * Muster unten nicht trifft, wird übersehen), Falsch-Positive sind möglich (ein 24 Zeichen langer
 * zufälliger String ohne echten API-Key dahinter sieht identisch aus). Kein Ersatz für einen
 * Passwort-Manager, nur ein zusätzlicher Stolperdraht.
 *
 * Arbeitet auf dem **gesamten** erfassten Feldinhalt (s. [de.ble1st.warden.clipboard
 * .ClipboardAccessibilityService]-Klassendoc: `event.text` ist immer der komplette aktuelle
 * Feldinhalt, nicht nur der gerade eingefügte Abschnitt) — deshalb suchen alle drei Muster nach
 * einem **Treffer irgendwo im Text**, nicht danach, dass der gesamte Text dem Muster entspricht;
 * ein bereits vorhandener Feldinhalt drumherum verhindert die Erkennung also nicht.
 */
object SensitiveContentDetector {

    enum class Category { SEED_PHRASE_LIKE, CREDIT_CARD_LIKE, API_KEY_LIKE }

    /** BIP-39-Seed-Phrasen haben genau eine dieser Wortzahlen. */
    private val SEED_PHRASE_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    private val CREDIT_CARD_DIGIT_RUN = Regex("""(?:\d[ -]?){13,19}""")

    private val API_KEY_PREFIXES = listOf(
        "sk-", "AKIA", "ghp_", "gho_", "github_pat_", "xox", "AIza", "glpat-", "ya29.",
    )

    fun detect(text: String): Category? = when {
        containsSeedPhraseLikeRun(text) -> Category.SEED_PHRASE_LIKE
        containsCreditCardLikeNumber(text) -> Category.CREDIT_CARD_LIKE
        containsApiKeyLikeToken(text) -> Category.API_KEY_LIKE
        else -> null
    }

    /** Sucht eine zusammenhängende Folge von 12/15/18/21/24 kleingeschriebenen, rein
     * alphabetischen 3-8-Zeichen-"Wörtern" irgendwo im (whitespace-getrennten) Text — bewusst
     * ohne die echte BIP-39-Wortliste (2048 Einträge, hier nicht eingebunden): eine grobe
     * Form-Heuristik statt eines exakten Abgleichs, entsprechend mehr Falsch-Positive/-Negative
     * als ein echter Wortlisten-Vergleich hätte. */
    private fun containsSeedPhraseLikeRun(text: String): Boolean {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val minCount = SEED_PHRASE_WORD_COUNTS.min()
        if (tokens.size < minCount) return false
        val isWordLike = tokens.map { token ->
            token.length in 3..8 && token.all { it.isLetter() } && token == token.lowercase()
        }
        for (count in SEED_PHRASE_WORD_COUNTS) {
            if (tokens.size < count) continue
            for (start in 0..(tokens.size - count)) {
                if ((start until start + count).all { isWordLike[it] }) return true
            }
        }
        return false
    }

    /** Findet Ziffernfolgen (Leerzeichen/Bindestriche als Trenner toleriert) mit 13-19 Ziffern und
     * prüft sie gegen den Luhn-Algorithmus — dieselbe Prüfsumme, die jede echte Kartennummer
     * erfüllen muss, senkt die Falsch-Positiv-Rate gegenüber einer reinen Längenprüfung deutlich
     * (eine zufällige 16-stellige Zahl besteht Luhn nur mit ~10% Wahrscheinlichkeit). */
    private fun containsCreditCardLikeNumber(text: String): Boolean =
        CREDIT_CARD_DIGIT_RUN.findAll(text).any { match ->
            val digits = match.value.filter(Char::isDigit)
            digits.length in 13..19 && luhnValid(digits)
        }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /** Jedes whitespace-getrennte Token wird einzeln geprüft: entweder ein bekanntes
     * Anbieter-Präfix (OpenAI, AWS, GitHub, Slack, Google, GitLab, …), oder ein generischer,
     * lang genug + gemischt genug wirkender Zufallstoken. */
    private fun containsApiKeyLikeToken(text: String): Boolean =
        text.split(Regex("\\s+")).any(::isApiKeyLikeToken)

    private fun isApiKeyLikeToken(token: String): Boolean {
        if (token.length >= 16 && API_KEY_PREFIXES.any { token.startsWith(it) }) return true
        if (token.length !in 24..128) return false
        if (!token.all { it.isLetterOrDigit() || it in "_-/+=" }) return false
        val hasDigit = token.any(Char::isDigit)
        val hasUpper = token.any(Char::isUpperCase)
        val hasLower = token.any(Char::isLowerCase)
        return hasDigit && hasUpper && hasLower
    }
}
