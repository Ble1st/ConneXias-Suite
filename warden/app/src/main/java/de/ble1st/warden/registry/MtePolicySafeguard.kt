package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/**
 * Erzwingt Arm Memory Tagging Extension (MTE) über `DevicePolicyManager.setMtePolicy`
 * (API 34, bei `minSdk 35` also unbedingt verfügbar) — 2026-09-05, Tier-1 der DPC-Recherche.
 *
 * **Was das bringt:** MTE ist eine CPU-Erweiterung, die ganze Klassen von Speicherfehlern
 * (Use-after-free, Buffer-Overflow) zur Laufzeit erkennt — also genau die Kategorie, aus der
 * Exploit-Ketten gegen Android üblicherweise gebaut werden. Anders als jeder andere Safeguard im
 * Katalog schaltet dieser keine *Funktion* ab, sondern eine *Härtung* an; er kostet etwas
 * Rechenleistung und ist deshalb bewusst optional.
 *
 * **`MTE_ENABLED` wirkt erst nach einem Neustart** — die Richtlinie wird sofort gesetzt, das
 * Verhalten des Systems ändert sich aber erst beim nächsten Boot. [isActive] fragt deshalb die
 * *Richtlinie* ab (`getMtePolicy`), nicht den tatsächlichen CPU-Zustand: das ist genau das, was
 * `apply()` setzt, und damit die richtige Grundlage für [RegistryReconciler] (dieselbe
 * "isActive() prüft, was apply() setzt"-Regel wie bei [AutoLockTimeoutSafeguard], s.
 * `warden/CLAUDE.md`). Die UI weist gesondert auf den nötigen Neustart hin.
 *
 * **Hardware-Abhängigkeit, ehrlich behandelt:** MTE braucht ARMv9 (Pixel 8 und neuer, einige
 * Snapdragon-8-Gen-3-Geräte). Auf allem anderen — einschließlich des Warden-Testgeräts SM-A156B
 * mit Exynos 1330 — wirft `setMtePolicy` `UnsupportedOperationException`. Dieser Fall wird
 * abgefangen und als "nicht unterstützt" behandelt, **nicht** als Fehlschlag: [isActive] liefert
 * dann dauerhaft `false`, `apply()` bleibt folgenlos, und der Reconciler versucht es nicht in einer
 * Endlosschleife erneut (er sieht schlicht "gewünscht: an, tatsächlich: aus" und korrigiert, was
 * genauso folgenlos bleibt). Ohne diese Behandlung würde ein einziger nicht unterstützender
 * Gerätetyp jeden Boot-Reconcile mit einer geworfenen Ausnahme abbrechen — und damit *alle*
 * übrigen Safeguards mit sich reißen.
 */
class MtePolicySafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        runSupported { devicePolicyManager().mtePolicy = DevicePolicyManager.MTE_ENABLED }
    }

    /**
     * Zurück auf [DevicePolicyManager.MTE_NOT_CONTROLLED_BY_POLICY], **nicht** auf
     * `MTE_DISABLED`: "Warden schreibt nichts mehr vor" ist die richtige Rücknahme einer
     * Härtung. `MTE_DISABLED` würde MTE aktiv *abschalten* und damit ein Gerät, auf dem es
     * herstellerseitig oder über AAPM an ist, schlechter stellen als vor dem Einschalten dieses
     * Safeguards — dieselbe Asymmetrie wie bei
     * [de.ble1st.warden.appmanagement.DangerousPermissionRevoker]s Rückweg über
     * `PERMISSION_GRANT_STATE_DEFAULT` statt `GRANTED`.
     */
    override fun revert() {
        runSupported { devicePolicyManager().mtePolicy = DevicePolicyManager.MTE_NOT_CONTROLLED_BY_POLICY }
    }

    override fun isActive(): Boolean =
        runCatching { devicePolicyManager().mtePolicy == DevicePolicyManager.MTE_ENABLED }
            .getOrDefault(false)

    /** Ob die Hardware MTE überhaupt kann — für die UI, damit ein ausgegrauter Schalter erklärbar
     * ist, statt wie ein defekter zu wirken. Es gibt keine reine Abfrage-API dafür; einzig
     * verlässlicher Indikator ist, ob `getMtePolicy` ohne Ausnahme antwortet. */
    fun isSupportedByHardware(): Boolean =
        runCatching { devicePolicyManager().mtePolicy }.isSuccess

    private fun runSupported(block: () -> Unit) {
        try {
            block()
        } catch (e: UnsupportedOperationException) {
            Log.i(TAG, "MTE von dieser Hardware nicht unterstützt — Safeguard bleibt folgenlos", e)
        }
    }

    companion object {
        const val ID: String = "mte_enabled"
        private const val TAG = "MtePolicySafeguard"
    }
}
