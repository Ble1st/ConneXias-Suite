package de.ble1st.warden.crypto

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * Meilenstein F.1 (Konzept Abschnitt 2b/(7) Härtung, Punkt 1): "Nur Ein-Operation-,
 * biometrie-gebundene Keys helfen — Zeitfenster-Keys nicht." Erzeugt/verwaltet den
 * AndroidKeyStore-Schlüssel, an dem der Presence-Nachweis hängt.
 *
 * **Kein Zeitfenster** (`setUserAuthenticationValidityDurationSeconds(N>0)` wäre für *jeden*
 * Code derselben UID innerhalb der letzten `N` Sekunden nutzbar — ein kompromittierter
 * Herald-Prozess, der zufällig in diesem Fenster mitläuft, könnte den Schlüssel ebenso
 * verwenden). Stattdessen `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`
 * (API 30+): Timeout 0 verlangt eine **frische** Authentifizierung für *jede einzelne*
 * kryptografische Operation, gebunden an genau die eine `Cipher`-Instanz, die
 * `BiometricPrompt.CryptoObject` umschließt (`:warden-app`s `PresenceManager`, s. dort für den
 * eigentlichen `BiometricPrompt.authenticate()`-Aufruf — diese Klasse selbst hat bewusst keine
 * `androidx.biometric`-Abhängigkeit, reine Keystore-/JCA-/Framework-Ebene:
 * `android.hardware.biometrics.BiometricManager` reicht für die reine Verfügbarkeitsprüfung).
 *
 * `setInvalidatedByBiometricEnrollment(true)`: ein neu eingeschriebener Finger/Face (kurzer
 * physischer Zugriff eines Angreifers) invalidiert den Schlüssel, statt ihn nutzbar zu halten.
 *
 * **Empirischer Fund (Testgerät Samsung A15, 2026-08-13):** `setUserAuthenticationParameters(0,
 * AUTH_BIOMETRIC_STRONG)` verlangt bereits bei der **Schlüsselerzeugung** — nicht erst bei der
 * Nutzung — mindestens einen eingerichteten Class-3(Strong)-Biometrie-Sensor
 * (`KeyGenerator.init()` wirft sonst `IllegalStateException: "At least one biometric must be
 * enrolled to create keys requiring user authentication for every use"`, keine erst zur Laufzeit
 * auftretende `UserNotAuthenticatedException`). Ohne diesen Vorab-Check per
 * [android.hardware.biometrics.BiometricManager] würde ein Gerät ohne eingerichtete Biometrie
 * eine rohe Plattform-Exception aus der Keystore-Tiefe sehen statt eines verständlichen Zustands
 * — [isAvailable] macht das explizit, [newUnauthenticatedCipher] wirft dafür
 * [PresenceUnavailableException] statt der rohen Plattform-Exception durchzureichen.
 *
 * **Kein persistiertes Boolean-/Zeitstempel-Flag irgendwo** — der Presence-"Zustand" existiert
 * ausschließlich als kurzlebiger, bereits authentifizierter [Cipher] in [PresenceProof]; ein
 * gleich-UID-Dateizugriff kann daran nichts fälschen (2b/(7) Härtung, Punkt 2).
 *
 * **Selbstheilung bei `KeyPermanentlyInvalidatedException`:** genau [setInvalidatedByBiometricEnrollment]
 * greift, sobald *nach* Schlüsselerzeugung ein zusätzlicher Finger/Face eingerichtet wird — der
 * alte Schlüssel bleibt dann als toter Keystore-Eintrag zurück, jedes künftige `cipher.init()`
 * würde ohne Behandlung mit einer rohen Plattform-Exception abbrechen. [newUnauthenticatedCipher]
 * fängt das ab, löscht den ungültigen Eintrag und erzeugt einmalig neu — derselbe Fail-Safe-
 * Grundsatz wie beim [PresenceUnavailableException]-Pfad: ein struktureller Zustand wird
 * behandelt, keine rohe Tiefen-Exception durchgereicht.
 */
class PresenceGate(private val context: Context, private val alias: String = DEFAULT_ALIAS) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Live geprüft (keine eigene Speicherung, Konzept Abschnitt 3: "Wahrheit im System") — ein
     * Ergebnis von vor einer Minute sagt nichts über jetzt, der Nutzer kann Biometrie jederzeit
     * ein-/ausrichten. */
    fun isAvailable(): Boolean {
        val biometricManager = context.getSystemService(BiometricManager::class.java) ?: return false
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun existingOrNewKey(): SecretKey =
        (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey()

    private fun generateKey(): SecretKey {
        if (!isAvailable()) {
            throw PresenceUnavailableException(
                "Kein Class-3(Strong)-Biometrie-Sensor eingerichtet — Presence-Nachweis (F.1) " +
                    "kann nicht erzeugt werden. Biometrie in den Systemeinstellungen einrichten.",
            )
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    /**
     * Ein frischer, initialisierter, aber **noch nicht authentifizierter** [Cipher]. Jeder
     * Versuch, ihn vor einer erfolgreichen Biometrie-Prüfung zu benutzen (`doFinal()`), wirft
     * `android.security.keystore.UserNotAuthenticatedException` — genau dieses Verhalten macht
     * einen Datei-/Flag-Bypass wirkungslos: Besitz eines `Cipher`-Objekts allein beweist nichts,
     * nur eine tatsächlich erfolgreiche `BiometricPrompt.authenticate(CryptoObject(cipher))`
     * schaltet ihn frei.
     *
     * Wirft [PresenceUnavailableException], wenn [isAvailable] `false` ist (s. Klassendoc,
     * empirischer Fund) — nie eine rohe `IllegalStateException` aus der Keystore-Tiefe.
     */
    fun newUnauthenticatedCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            // s. Klassendoc "Selbstheilung": alter Schlüssel durch neu eingerichtete Biometrie
            // entwertet — löschen und genau einmal neu erzeugen, kein Endlos-Retry.
            keyStore.deleteEntry(alias)
            cipher.init(Cipher.ENCRYPT_MODE, generateKey())
        }
        return cipher
    }

    companion object {
        const val DEFAULT_ALIAS = "warden.presence.gate"
    }
}

/** Wird geworfen, wenn [PresenceGate.newUnauthenticatedCipher] aufgerufen wird, obwohl kein
 * Class-3(Strong)-Biometrie-Sensor eingerichtet ist — Fail-Safe (Invariante 6): der Aufrufer
 * muss das eindeutig als "Presence strukturell nicht verfügbar" erkennen, nie als generischen
 * Fehler, der zufällig doch noch als Erfolg durchgehen könnte. */
class PresenceUnavailableException(message: String) : Exception(message)
