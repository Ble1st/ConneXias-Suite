package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 4) — bildet
 * einen stabilen Fingerprint über die Signer-Zertifikate eines Pakets (SHA-256, hex, mehrere
 * Signer sortiert und verkettet), für [de.ble1st.warden.domain.appmanagement
 * .SigningCertChangeDecision]. Berücksichtigt sowohl den Mehrfach-Signer- als auch den
 * Rotations-Fall ([android.content.pm.SigningInfo.hasMultipleSigners] steuert, welche der beiden
 * Historien tatsächlich den *aktuell gültigen* Signer beschreibt — bei rotierten Zertifikaten
 * (`hasMultipleSigners() == false`) ist [android.content.pm.SigningInfo.getSigningCertificateHistory]
 * die richtige Quelle, bei mehreren gleichzeitig gültigen Signern (seltener,
 * `hasMultipleSigners() == true`) [android.content.pm.SigningInfo.getApkContentsSigners]).
 *
 * `null`, wenn das Paket nicht (mehr) auflösbar ist oder keine Signing-Info liefert — der
 * Aufrufer behandelt das wie "keine Baseline für dieses Paket", nicht wie ein Fehler.
 */
class SigningCertReader(private val context: Context) {

    fun fingerprintFor(packageName: String): String? = runCatching {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val signingInfo = context.packageManager.getPackageInfo(packageName, flags).signingInfo ?: return null
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        if (signers.isNullOrEmpty()) return null
        signers.map { sha256Hex(it.toByteArray()) }.sorted().joinToString(",")
    }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
