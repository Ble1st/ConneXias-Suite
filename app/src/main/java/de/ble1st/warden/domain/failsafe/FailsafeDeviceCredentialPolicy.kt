package de.ble1st.warden.domain.failsafe

/**
 * Failsafe may reset the real device lock. The new credential must satisfy
 * [android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH] and then some:
 * HIGH's numeric floor is 8 non-sequential digits; this path requires **16** so a
 * recovered phone is not left on an 8-digit PIN after an air-gap recovery.
 *
 * Digit-only: reject a constant-step value (1111…, 1234…, 2468…) and any 4-digit
 * run that is repeating or ±1 sequential (same idea as Android's HIGH PIN check).
 * Mixed / alphabetic: length 16 is enough (HIGH only asks for 6 letters); all-same
 * characters are still rejected.
 */
object FailsafeDeviceCredentialPolicy {
    const val MIN_LENGTH: Int = 16
    private const val WEAK_RUN = 4

    fun isAcceptable(credential: String): Boolean {
        if (credential.length < MIN_LENGTH) return false
        if (credential.all { it.isDigit() }) return !isWeakDigitPin(credential)
        return !credential.all { it == credential[0] }
    }

    internal fun isWeakDigitPin(pin: String): Boolean {
        if (isConstantStep(pin)) return true
        if (pin.length < WEAK_RUN) return false
        for (start in 0..pin.length - WEAK_RUN) {
            val run = pin.substring(start, start + WEAK_RUN)
            val step = run[1] - run[0]
            if (step in -1..1 && isConstantStep(run)) return true
        }
        return false
    }

    /** Same idea as Android's HIGH examples: 1111, 1234, 4321, 2468. */
    internal fun isRepeatingOrOrderedDigits(pin: String): Boolean = isConstantStep(pin)

    private fun isConstantStep(pin: String): Boolean {
        if (pin.length < 2) return true
        val step = pin[1] - pin[0]
        return pin.zipWithNext { a, b -> b - a }.all { it == step }
    }
}
