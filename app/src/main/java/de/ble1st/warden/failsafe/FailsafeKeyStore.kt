package de.ble1st.warden.failsafe

import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.OfflineFailsafeVerifier

/**
 * Meilenstein D.1 (Konzept Abschnitt 9/19): "Public Key in Warden hinterlegen." Persistiert den
 * öffentlichen Teil des Offline-Failsafe-Schlüsselpaars über [EnvelopeFile]
 * ([FailsafeStorage.buildTrustedKeyFile]).
 *
 * **Kein Platzhalter-Schlüssel:** Warden startet ohne konfigurierten Schlüssel
 * ([configuredPublicKey] liefert `null`) — der private Schlüssel muss laut Konzept Abschnitt 9
 * offline auf einer Air-Gap-Maschine erzeugt werden (`tools/failsafe-offline/README.md`), es
 * gibt keinen sinnvollen "Standard"-Schlüssel, den diese Klasse vorab einsetzen könnte, ohne eine
 * Sicherheit vorzutäuschen, die nicht besteht. `FailsafeDecisionResult.NoKeyConfigured`
 * (`:core:domain`, `FailsafeDecision`) ist der explizite, ehrliche Zustand dafür.
 *
 * Envelope-verschlüsselt statt Klartext, obwohl ein öffentlicher Schlüssel keine Vertraulichkeit
 * braucht — der Keystore-gebundene Envelope-Schutz gibt zusätzlich **Integrität** (Auth-Tag):
 * ein Angreifer ohne App-UID/Keystore-Zugriff kann den hinterlegten Schlüssel nicht durch einen
 * eigenen ersetzen, ohne dass [EnvelopeFile.read] mit einer Auth-Tag-Prüfung fehlschlägt. Dieselbe
 * Ein-APK-Grenze wie überall im Projekt (CLAUDE.md "Herald-Isolation"): schützt nicht gegen einen
 * kompromittierten gleich-UID-Prozess.
 */
class FailsafeKeyStore(private val envelopeFile: EnvelopeFile) {

    /** Aktuell hinterlegter öffentlicher Schlüssel, oder `null`, wenn noch keiner konfiguriert
     * wurde. */
    fun configuredPublicKey(): ByteArray? =
        if (envelopeFile.hasStorage()) envelopeFile.read() else null

    /** Hinterlegt (oder ersetzt) den öffentlichen Failsafe-Schlüssel. `publicKey` muss exakt
     * [OfflineFailsafeVerifier]-kompatibel sein (32 Byte, Ed25519). */
    fun configurePublicKey(publicKey: ByteArray) {
        require(publicKey.size == PUBLIC_KEY_LENGTH_BYTES) {
            "Öffentlicher Schlüssel muss $PUBLIC_KEY_LENGTH_BYTES Byte lang sein (Ed25519), war ${publicKey.size}"
        }
        envelopeFile.write(publicKey)
    }

    companion object {
        const val PUBLIC_KEY_LENGTH_BYTES = 32
    }
}
