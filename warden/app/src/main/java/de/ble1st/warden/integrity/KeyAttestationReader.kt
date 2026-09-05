package de.ble1st.warden.integrity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import de.ble1st.warden.domain.attestation.AttestationExtensionParser
import de.ble1st.warden.domain.attestation.DeviceAttestation
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Liest den Android-Key-Attestation-Record aus (2026-09-05, Tier-1 der DPC-Recherche) — der
 * kryptografisch belastbare Gegenpart zu [RootIndicatorScanner]s Heuristik: Verified-Boot-Zustand,
 * Bootloader-Sperre und Sicherheitspatch-Stand, signiert von der Geräte-Hardware selbst.
 *
 * **Ablauf:** ein Wegwerf-Schlüsselpaar im AndroidKeyStore erzeugen, dabei über
 * [KeyGenParameterSpec.Builder.setAttestationChallenge] eine frische Zufalls-Challenge setzen, die
 * dabei entstehende Zertifikatskette abholen und die Attestation-Erweiterung des Leaf-Zertifikats
 * parsen ([AttestationExtensionParser]). Der Schlüssel wird direkt danach wieder gelöscht — er hat
 * keinen anderen Zweck als diesen einen Nachweis.
 *
 * **Warum bei jedem Aufruf ein neuer Schlüssel mit neuer Challenge** und nicht einmalig einer, der
 * liegen bleibt: die Challenge ist genau das, was den Record an *diesen* Lesevorgang bindet. Ein
 * gespeicherter alter Record würde weiterhin "Bootloader gesperrt" behaupten, nachdem das Gerät
 * längst entsperrt wurde — also exakt die Aussage konservieren, gegen die diese Prüfung schützt.
 * Deshalb auch kein Caching des Ergebnisses über Prozessgrenzen hinweg.
 *
 * **`EC` statt `RSA`** — Schlüsselerzeugung ist auf vielen Geräten spürbar schneller, und für einen
 * Schlüssel, der nur Attestation trägt und nie etwas signiert, ist der Typ ohnehin gleichgültig.
 *
 * **Kein StrongBox-Zwang:** `setIsStrongBoxBacked(true)` würde auf Geräten ohne StrongBox-Chip mit
 * `StrongBoxUnavailableException` fehlschlagen. Der Record sagt über
 * [de.ble1st.warden.domain.attestation.AttestationSecurityLevel] ohnehin selbst, wo der Schlüssel
 * gelandet ist — erzwingen würde nur den Lesevorgang auf halben Geräteklassen unmöglich machen.
 *
 * **Fehlerverhalten:** jeder Fehlschlag (Attestation nicht unterstützt, OEM liefert keine
 * Erweiterung, KeyStore-Ausnahme) endet in [DeviceAttestation.UNBEKANNT] — bewusst *nicht* in einer
 * geworfenen Ausnahme wie sonst bei der Fail-Safe-Regel des Projekts: hier ist "nicht auslesbar"
 * ein alltäglicher, erwartbarer Gerätezustand (viele OEMs), kein Anzeichen für Manipulation. Die
 * Unterscheidung bleibt trotzdem sichtbar, weil `UNBEKANNT` in der UI und im Score anders behandelt
 * wird als ein gelesener guter Wert (s. [de.ble1st.warden.domain.attestation.AttestationDecision]).
 */
class KeyAttestationReader(
    private val logTag: String = TAG,
) {

    fun read(): DeviceAttestation {
        val alias = "$KEY_ALIAS_PREFIX${System.nanoTime()}"
        return try {
            val chain = generateAndFetchChain(alias)
                ?: return DeviceAttestation.UNBEKANNT
            val leaf = chain.firstOrNull() as? X509Certificate ?: return DeviceAttestation.UNBEKANNT
            val extension = leaf.getExtensionValue(AttestationExtensionParser.ATTESTATION_EXTENSION_OID)
                ?: return DeviceAttestation.UNBEKANNT
            // getExtensionValue liefert den DER-kodierten OCTET STRING, der den eigentlichen
            // Erweiterungsinhalt umschließt — eine Hülle abziehen, bevor geparst wird.
            val keyDescription = unwrapOctetString(extension) ?: return DeviceAttestation.UNBEKANNT
            AttestationExtensionParser.parse(
                keyDescription = keyDescription,
                chainTrusted = evaluateChainTrust(chain),
            )
        } catch (e: Exception) {
            Log.i(logTag, "Key-Attestation nicht auslesbar (auf vielen Geräten normal)", e)
            DeviceAttestation.UNBEKANNT
        } finally {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private fun generateAndFetchChain(alias: String): Array<java.security.cert.Certificate>? {
        val challenge = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build(),
        )
        generator.generateKeyPair()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getCertificateChain(alias)
    }

    /**
     * Prüft, ob die Kette bei einer bekannten Google-Attestation-Wurzel endet.
     *
     * **Bewusst nur ein Fingerabdruck-Vergleich des Wurzel-Zertifikats, keine vollständige
     * PKIX-Validierung** — und schon gar kein Sperrlisten-Abruf: letzterer bräuchte Netzzugang zu
     * `android.googleapis.com`, und Warden hat als erklärte Eigenschaft *keinen* Server-Kontakt
     * (s. `warden/CLAUDE.md`, "kein Remote-/Push-Kanal"). Eine Prüfung, die ohne Netz stillschweigend
     * durchfällt, wäre schlechter als eine, die ehrlich nur das prüft, was lokal prüfbar ist.
     * Ergebnis ist deshalb ein Hinweis ([DeviceAttestation.chainTrusted]), keine Vertrauensbasis:
     * die eigentliche Aussagekraft steckt im Record selbst, den nur die Hardware erzeugen kann.
     *
     * `null` heißt "nicht beurteilbar" (leere/einelementige Kette), nicht "nicht vertrauenswürdig".
     */
    private fun evaluateChainTrust(chain: Array<java.security.cert.Certificate>): Boolean? {
        val root = chain.lastOrNull() as? X509Certificate ?: return null
        if (chain.size < 2) return null
        val publicKeyHash = runCatching {
            java.security.MessageDigest.getInstance("SHA-256").digest(root.publicKey.encoded)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        return publicKeyHash in GOOGLE_ROOT_PUBLIC_KEY_SHA256
    }

    private fun unwrapOctetString(der: ByteArray): ByteArray? {
        // Erwartet: 04 <len> <inhalt>. Länge kann kurz- oder langform sein.
        if (der.size < 2 || der[0] != 0x04.toByte()) return null
        var index = 1
        var length = der[index].toInt() and 0xFF
        index++
        if (length and 0x80 != 0) {
            val lengthBytes = length and 0x7F
            if (lengthBytes == 0 || lengthBytes > 4 || index + lengthBytes > der.size) return null
            length = 0
            repeat(lengthBytes) {
                length = (length shl 8) or (der[index].toInt() and 0xFF)
                index++
            }
        }
        if (index + length > der.size) return null
        return der.copyOfRange(index, index + length)
    }

    private companion object {
        const val TAG = "KeyAttestationReader"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "warden_attestation_"
        const val CHALLENGE_BYTES = 32

        /**
         * SHA-256 über den DER-kodierten `SubjectPublicKeyInfo` der Google-Attestation-Wurzeln.
         *
         * **Herkunft, nachvollziehbar (2026-09-05):** abgerufen von `https://android.googleapis
         * .com/attestation/root` (Googles eigener, öffentlicher Endpunkt, der die aktuell gültigen
         * Wurzeln als PEM-Liste ausliefert) und lokal per
         * `openssl x509 -pubkey -noout | sha256` berechnet. Beide dort enthaltenen Wurzeln stehen
         * hier — die alte RSA-4096-Wurzel (`serialNumber=f92009e853b6b045`, gültig bis 2042) und
         * die 2025 hinzugekommene EC-Wurzel (`CN=Key Attestation CA1`, gültig bis 2035). Wer das
         * nachrechnen will, braucht genau diese zwei Befehle; die Werte sind **nicht** aus dem
         * Gedächtnis notiert.
         *
         * Trifft keiner zu, ist das ein Hinweis, kein Urteil (s. [evaluateChainTrust]) — Google
         * kann die Liste erweitern, und ein Warden-Build von heute darf ein Gerät von übermorgen
         * nicht deswegen als manipuliert bezeichnen.
         */
        val GOOGLE_ROOT_PUBLIC_KEY_SHA256 = setOf(
            "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",
            "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec",
        )
    }
}
