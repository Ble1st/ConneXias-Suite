package de.ble1st.warden.crypto

import javax.crypto.Cipher

private val GATE_PLAINTEXT = "warden:presence:v1".toByteArray()

/**
 * Meilenstein F.1 (Konzept Abschnitt 2b/(7) Härtung, Punkt 2): der eigentliche Presence-Nachweis
 * — ein bereits (per `BiometricPrompt.authenticate(CryptoObject)`) authentifizierter [Cipher],
 * niemals ein Boolean/Zeitstempel. `consume()` **ist** die Prüfung: sie funktioniert nur, wenn
 * der Cipher wirklich freigeschaltet wurde, und **nur einmal** — genau das verlangt Konzept
 * 2b/(7): "der eigentliche DPM-Aufruf muss den freigeschalteten Schlüssel tatsächlich
 * benötigen (nicht nur einen zuvor gesetzten Flag lesen)".
 *
 * Doppelte Absicherung des Einmal-Gebrauchs: der interne `consumed`-Merker **und** die
 * Keystore-/JCA-Semantik selbst (ein per-Operation-authentifizierter `Cipher` lässt nach einem
 * `doFinal()` kein zweites erfolgreiches `doFinal()` mehr zu — das zugrundeliegende
 * Keymaster-Operation-Handle wird nach einer Operation geschlossen). Der Merker ist trotzdem
 * explizit vorhanden, damit ein zweiter Aufruf **deterministisch** `false` liefert, statt sich
 * auf ein Cipher-internes Verhalten zu verlassen, das je nach Provider/Android-Version variieren
 * könnte (Fail-Safe, Invariante 6: im Zweifel ablehnen).
 *
 * Öffentlicher Konstruktor: `BiometricPrompt.authenticate()` (`androidx.biometric`) läuft
 * zwangsläufig in `:warden-app` (braucht `FragmentActivity`, s. `PresenceManager` dort) — der
 * authentifizierte `Cipher` aus `AuthenticationResult.cryptoObject` muss von dort aus in ein
 * [PresenceProof] gepackt werden können. Kein Encapsulation-Verlust: [PresenceProof] verwahrt
 * kein Geheimnis, das ein Aufrufer nicht ohnehin schon besitzt (den Cipher selbst) — die
 * eigentliche Sicherheitsgrenze ist die Keystore-Authentifizierung des Ciphers, nicht der
 * Sichtbarkeitsmodifikator dieses Konstruktors.
 */
class PresenceProof(private val cipher: Cipher) {

    @Volatile
    private var consumed = false

    /** `true` nur bei genau der ersten, tatsächlich erfolgreichen kryptografischen Operation
     * auf dem authentifizierten Cipher. Jeder weitere Aufruf — und jeder Aufruf auf einem nie
     * authentifizierten Cipher — liefert `false`. */
    @Synchronized
    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return try {
            cipher.doFinal(GATE_PLAINTEXT)
            true
        } catch (e: Exception) {
            false
        }
    }
}
