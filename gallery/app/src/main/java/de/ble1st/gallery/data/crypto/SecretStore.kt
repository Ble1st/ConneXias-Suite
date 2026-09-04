package de.ble1st.gallery.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Legt einzelne Zeichenketten verschlüsselt ab: AES-256/GCM mit einem Schlüssel, der im
 * Android-Keystore erzeugt wird und diesen nie verlässt.
 *
 * Ersetzt seit 2026-09-04 `androidx.security:security-crypto`
 * (`EncryptedSharedPreferences` und `MasterKey`). Jetpack Security ist von Google eingestellt — die
 * Bibliothek ist samt ihrer Tink-Abhängigkeit als veraltet markiert, ohne dass ein Nachfolger
 * benannt wäre, und der Compiler meldet das bei jedem Übersetzungslauf. Der Umfang, den diese
 * beiden Apps davon nutzten, ist genau das hier Umgesetzte: ein paar Zeichenketten in einer
 * Preferences-Datei, verschlüsselt mit einem gerätegebundenen Schlüssel.
 *
 * Unterschiede zum abgelösten `EncryptedSharedPreferences`, bewusst so gewählt:
 *
 * * **Die Schlüsselnamen bleiben im Klartext** (dort waren sie mit AES-SIV verschlüsselt). Sie
 *   sind fest verdrahtete Konstanten wie `password` oder `accounts` — sie verraten nur, was der
 *   Aufrufer ohnehin im Quelltext stehen hat. Verschlüsselt sind die Werte.
 * * **Ein unlesbarer Wert liefert `null` statt einer Ausnahme.** Der Fall tritt ein, wenn der
 *   Keystore-Schlüssel weg ist, die Preferences-Datei aber nicht — etwa nach einem
 *   Geräte-Backup ohne Keystore-Inhalt (beide Apps setzen zwar `allowBackup="false"`, das ist
 *   aber eine Zusicherung des Systems, nicht dieser Klasse). `EncryptedSharedPreferences` warf
 *   dort eine `GeneralSecurityException` bis in den Aufrufer und damit in den Absturz. Für ein
 *   gespeichertes WebDAV-Konto ist "nicht eingerichtet" die richtige Antwort: der Nutzer trägt
 *   die Zugangsdaten neu ein.
 *
 * Es gibt keine Übernahme der alten Daten. Beide Apps sind noch nicht veröffentlicht, es
 * existiert also kein Bestand, den es zu übernehmen gälte; eine Migration hieße, die veraltete
 * Bibliothek genau dafür weiter mitzuschleppen. Die Preferences-Datei heißt deshalb anders als
 * vorher — eine noch vorhandene alte Datei aus einem Entwicklungs-Build wird nicht gelesen und
 * nicht angefasst.
 */
class SecretStore(context: Context, fileName: String, private val keyAlias: String) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun getString(key: String): String? = prefs.getString(key, null)?.let(::decrypt)

    fun putString(key: String, value: String) {
        prefs.edit { putString(key, encrypt(value)) }
    }

    /** Setzt mehrere Werte in einem Schreibvorgang — sonst läge zwischen zwei `putString` ein
     * Zustand mit halb erneuertem Konto in der Datei. */
    fun putStrings(values: Map<String, String>) {
        prefs.edit { values.forEach { (key, value) -> putString(key, encrypt(value)) } }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // Der Initialisierungsvektor ist kein Geheimnis, muss zum Entschlüsseln aber vorliegen.
        // Seine Länge steht mit im Satz, statt die 12 Byte des Keystore-Anbieters als gegeben
        // anzunehmen — so bleibt ein einmal geschriebener Wert auch lesbar, wenn eine künftige
        // Android-Fassung eine andere Länge wählt.
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val ivSize = raw[0].toInt()
        require(ivSize in 1..MAX_IV_SIZE && raw.size > ivSize + 1)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, raw, 1, ivSize),
        )
        String(cipher.doFinal(raw, ivSize + 1, raw.size - ivSize - 1), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Holt den Schlüssel aus dem Keystore oder legt ihn beim ersten Aufruf an.
     *
     * `@Synchronized`, weil zwei gleichzeitige Erstaufrufe sonst zwei Schlüssel unter demselben
     * Alias erzeugen würden: der zweite ersetzt den ersten, und alles, was mit dem ersten
     * geschrieben wurde, wäre unlesbar.
     */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Bewusst ohne setUserAuthenticationRequired: die WebDAV-Zugangsdaten werden
                // auch aus dem Hintergrund gebraucht (Files' Übertragungs-Warteschlange,
                // Galeries Sicherung über den WorkManager) — mit Nutzer-Authentifizierung wäre
                // der Schlüssel dort gesperrt.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val MAX_IV_SIZE = 64
    }
}
