package de.ble1st.warden.appmanagement

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Verteilung: als Asset
 * gebündelt". Port des Musters aus dem ConneXias-Framework-Quellprojekt
 * (`core/data/.../update/SilentUpdateInstaller.kt`), stark vereinfacht: kein
 * Downgrade-Schutz-Sonderfall nötig (anders als dort ein *Update* für ein bereits laufendes,
 * unabhängig versioniertes Paket) — Sentinel wird hier immer im Lockstep mit der aktuell
 * laufenden Warden-Version gebündelt (`app/build.gradle.kts`s `copySentinelApkFor<Variant>`-Task),
 * ein "Downgrade" auf eine ältere Sentinel-Version ohne gleichzeitiges Warden-Downgrade kann
 * strukturell nicht vorkommen.
 *
 * **Silent bedeutet hier wörtlich:** als Device Owner darf Warden über [PackageInstaller]
 * installieren, ohne dass das System einen Bestätigungsdialog einblendet — dieselbe
 * Sonderbehandlung wie bei [AppUninstaller.uninstall].
 *
 * **Warum ein Temp-File statt eines direkten `AssetFileDescriptor`-Streams:**
 * [PackageInstaller.Session.openWrite] verlangt eine bekannte Länge (`-1` ist zwar erlaubt, aber
 * `context.assets.openFd(...)` liefert für in `assets/` gepackte Dateien ohnehin keinen
 * kompressionsfreien Zugriff garantiert — Kopie in eine reguläre Datei im `cacheDir` ist der
 * robuste, dokumentierte Weg, denselben die alte Vorlage bereits nutzte).
 */
class SentinelSilentInstaller(
    private val context: Context,
    private val statusReceiverIntent: () -> Intent = {
        Intent(context, SentinelInstallResultReceiver::class.java)
    },
) {
    fun install(): SentinelInstallOutcome {
        val apkFile = try {
            extractApkFromAssets()
        } catch (e: Exception) {
            return SentinelInstallOutcome.AssetUnavailable(e)
        }
        return try {
            SentinelInstallOutcome.SessionCommitted(commitSession(apkFile))
        } catch (e: Exception) {
            SentinelInstallOutcome.InstallerError(e)
        } finally {
            apkFile.delete()
        }
    }

    private fun extractApkFromAssets(): File {
        val tempFile = File.createTempFile("sentinel", ".apk", context.cacheDir)
        context.assets.open(ASSET_NAME).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    private fun commitSession(apkFile: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Plattformeigene Durchsetzung (derselbe Grund wie SilentUpdateInstallers S13-Fund): der
        // OS-Installer selbst verweigert commit(), falls die tatsächlich geschriebenen APK-Bytes
        // ein anderes Paket beschreiben als hier erwartet.
        params.setAppPackageName(SENTINEL_PACKAGE_NAME)
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite(WRITE_NAME, 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusReceiverIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
        return sessionId
    }

    companion object {
        const val SENTINEL_PACKAGE_NAME = "de.ble1st.warden.sentinel"

        /** Muss mit dem `rename { "sentinel.apk" }` in `app/build.gradle.kts`s
         * `copySentinelApkFor<Variant>`-Task übereinstimmen. */
        private const val ASSET_NAME = "sentinel.apk"
        private const val WRITE_NAME = "payload"
    }
}

/** Ergebnis von [SentinelSilentInstaller.install] — nur der **synchrone** Teil: das eigentliche
 * Installationsergebnis (`STATUS_SUCCESS`/`STATUS_FAILURE*`) kommt asynchron über
 * [SentinelInstallResultReceiver]. */
sealed class SentinelInstallOutcome {
    /** Session erfolgreich erzeugt, beschrieben und committet. */
    data class SessionCommitted(val sessionId: Int) : SentinelInstallOutcome()

    /** `assets/sentinel.apk` fehlt oder ist nicht lesbar — sollte bei einem regulär gebauten
     * Warden-APK strukturell nie vorkommen (`copySentinelApkFor<Variant>` läuft vor jedem
     * `merge<Variant>Assets`), aber ein Fail-Safe-Rückgabewert statt eines Absturzes ist trotzdem
     * die richtige Haltung — dieselbe wie überall im Projekt bei nicht verifizierbaren Dateien. */
    data class AssetUnavailable(val cause: Exception) : SentinelInstallOutcome()

    /** Session-Erzeugung/-Schreiben/-Commit ist mit einer Exception fehlgeschlagen (z. B.
     * `PackageInstaller` lehnt ab). */
    data class InstallerError(val cause: Exception) : SentinelInstallOutcome()
}
