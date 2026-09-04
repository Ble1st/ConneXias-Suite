package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 4) — Baseline
 * für [de.ble1st.warden.domain.appmanagement.SigningCertChangeDecision]: der zuletzt gesehene
 * Signatur-Fingerprint ([SigningCertReader]) pro Paket. Eine Zeile pro Paket
 * (`SharedPreferences`-Key = Paketname) statt eines serialisierten Blobs — einfacher
 * Random-Access-Lookup/-Update pro Paket, keine Notwendigkeit die ganze Map bei jeder Änderung
 * neu zu schreiben.
 *
 * Dieselbe "Klartext-`SharedPreferences` reicht" Begründung wie [ActivationHistoryStore]: ein
 * verlorener Cache bedeutet nur "nächster Signaturwechsel wird als neue Baseline statt als Fund
 * behandelt", kein Sicherheitsverlust — Neuinstallationen mit geändertem Signer würden ohnehin
 * i. d. R. auch andere Signale auslösen (z. B. [de.ble1st.warden.domain.appmanagement
 * .SuspiciousSignal.UNKNOWN_INSTALL_SOURCE]).
 */
class SigningCertHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun fingerprints(): Map<String, String> =
        prefs.all.entries.mapNotNull { (pkg, value) -> (value as? String)?.let { pkg to it } }.toMap()

    fun record(packageName: String, fingerprint: String) {
        prefs.edit { putString(packageName, fingerprint) }
    }

    private companion object {
        const val PREFS_NAME = "warden_signing_cert_history"
    }
}
