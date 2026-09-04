package de.ble1st.warden.integrity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 8) — geräteweite
 * (nicht app-bezogene) Root-/Custom-ROM-Indikatoren, ergänzend zu [DebuggableOsStatusReader]
 * (dort: `Build.TYPE`). Drei unabhängige, jeweils best-effort geprüfte Kriterien:
 *
 * - [RootIndicatorSignal.SU_BINARY_FOUND] — bekannte `su`-Binary-Pfade auf dem Dateisystem.
 * - [RootIndicatorSignal.MAGISK_PACKAGE_FOUND] — bekannte Magisk-Manager-Paketnamen installiert.
 * - [RootIndicatorSignal.TEST_KEYS_BUILD] — `Build.TAGS` enthält `test-keys` (mit Test-/
 *   Entwicklerschlüsseln statt offiziellen Release-Keys signiertes Systemimage, üblich bei
 *   Custom-ROMs).
 *
 * Bewusst **kein Anspruch auf Vollständigkeit** — Magisk Hide/Zygisk kann su-Binaries und das
 * eigene Paket vor genau dieser Art Prüfung verstecken, umbenannte/versteckte Root-Lösungen
 * existieren. Dient als zusätzlicher Geräte-Integritäts-Hinweis in der UI, nicht als
 * Sicherheits-Gate (nichts hier blockiert automatisch irgendeine Funktion).
 */
class RootIndicatorScanner(private val context: Context) {

    fun scan(): Set<RootIndicatorSignal> {
        val found = mutableSetOf<RootIndicatorSignal>()
        if (SU_BINARY_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }) {
            found += RootIndicatorSignal.SU_BINARY_FOUND
        }
        if (MAGISK_PACKAGE_NAMES.any { isPackageInstalled(it) }) {
            found += RootIndicatorSignal.MAGISK_PACKAGE_FOUND
        }
        if (Build.TAGS?.contains("test-keys") == true) {
            found += RootIndicatorSignal.TEST_KEYS_BUILD
        }
        return found
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        }.isSuccess

    private companion object {
        val SU_BINARY_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/su/bin/su",
            "/system/bin/.ext/.su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
        )
        val MAGISK_PACKAGE_NAMES = listOf(
            "com.topjohnwu.magisk",
            "io.github.huskydg.magisk",
            "com.topjohnwu.magisk.canary",
        )
    }
}
