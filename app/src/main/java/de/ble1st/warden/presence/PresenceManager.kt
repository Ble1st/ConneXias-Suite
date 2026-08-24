package de.ble1st.warden.presence

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.crypto.PresenceGate
import de.ble1st.warden.crypto.PresenceProof
import de.ble1st.warden.crypto.PresenceUnavailableException

/**
 * Meilenstein F.1/F.2 (Konzept Abschnitt 2b/(7)): die einzige Stelle im Projekt, die
 * `androidx.biometric.BiometricPrompt` anfasst — braucht zwingend eine `FragmentActivity`
 * (Plattform-Anforderung), deshalb in `:warden-app`, nicht in `:core:crypto` (dessen
 * [PresenceGate] bewusst UI-frei bleibt, s. dortige Klassendoc).
 *
 * **Aus Warden selbst gezeigt, nicht aus Herald** (Konzept 2b/(7) Punkt 3: "Bestätigung
 * out-of-band anzeigen ... Warden selbst") — `activity` muss eine Warden-eigene Activity sein
 * (`SensitiveActionActivity`), nie eine Herald-UI-Komponente, sobald Herald existiert
 * (Meilenstein G).
 */
class PresenceManager(
    private val activity: FragmentActivity,
    private val gate: PresenceGate = PresenceGate(activity),
) {
    sealed class Result {
        /** Biometrie erfolgreich bestätigt — `proof` ist genau einmal verwendbar
         * ([PresenceProof.consume]). */
        data class Success(val proof: PresenceProof) : Result()

        /** Kein Class-3(Strong)-Biometrie-Sensor eingerichtet (s. [PresenceGate]-Klassendoc,
         * empirischer Fund auf dem Testgerät) — Prompt wurde gar nicht erst gezeigt. */
        data object Unavailable : Result()

        /** Nutzer hat abgebrochen, oder ein Fehler ist aufgetreten (z. B. zu viele
         * Fehlversuche, Biometrie-Hardware nicht verfügbar). */
        data object Cancelled : Result()
    }

    /** Zeigt den System-Biometrie-Dialog. `title`/`subtitle` sind der Out-of-band-
     * Bestätigungstext (F.2) — was bestätigt wird, muss hier explizit stehen, damit der Nutzer
     * es sieht, selbst wenn eine kompromittierte Herald-UI etwas anderes anzeigen würde. */
    fun request(title: String, subtitle: String, onResult: (Result) -> Unit) {
        val cipher = try {
            gate.newUnauthenticatedCipher()
        } catch (e: PresenceUnavailableException) {
            onResult(Result.Unavailable)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Abbrechen")
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    onResult(
                        if (authenticatedCipher != null) {
                            Result.Success(PresenceProof(authenticatedCipher))
                        } else {
                            Result.Cancelled
                        },
                    )
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(Result.Cancelled)
                }

                // onAuthenticationFailed() (einzelner Fehlversuch, z. B. Finger nicht erkannt)
                // bewusst ohne Callback — die System-Prompt-UI bleibt laut BiometricPrompt-
                // Vertrag offen und erlaubt einen erneuten Versuch, kein Grund, hier schon ein
                // Endergebnis zu melden.
            },
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
