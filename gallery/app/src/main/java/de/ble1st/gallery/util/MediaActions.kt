package de.ble1st.gallery.util

import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import de.ble1st.gallery.R

sealed interface DeleteOutcome {
    data class Deleted(val count: Int) : DeleteOutcome
    data class NeedsConfirmation(val intentSender: IntentSender) : DeleteOutcome
}

/**
 * MediaStore liefert bereits `content://`-Uris — kein eigener FileProvider nötig (anders als
 * ConneXias Files' `FileActions`, s. AndroidManifest.xml-Klassendoc).
 */
object MediaActions {

    fun share(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = context.contentResolver.getType(uris.first()) ?: "*/*"
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = "*/*"
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // analyse.md (2. Durchgang, Mittel): EXTRA_STREAM allein reicht bei manchen Ziel-Apps für
        // den Lesezugriff nicht — sie erwarten die zu gewährenden Uris im ClipData (üblicher Weg
        // bei Mehrfachauswahl, von manchen Apps aber auch für eine einzelne Uri verlangt).
        intent.clipData = uris.drop(1).fold(ClipData.newUri(context.contentResolver, "media", uris.first())) { clip, uri ->
            clip.apply { addItem(ClipData.Item(uri)) }
        }
        launchOrToast(context, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openWith(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchOrToast(context, intent)
    }

    /**
     * Löschstrategie nach API-Level (s. [de.ble1st.gallery.permission.MediaPermission]-Klassendoc):
     * - API 30+: `MediaStore.createDeleteRequest` liefert einen `IntentSender` für einen
     *   System-Bestätigungsdialog, der auch fremde (nicht von dieser App angelegte) Einträge
     *   abdeckt — nach Bestätigung führt das System das Löschen selbst aus, kein erneuter
     *   `delete`-Aufruf durch die App nötig. Der weit überwiegende Fall in der Praxis: diese App
     *   liest fast ausschließlich Einträge, die andere Apps (z. B. ConneXias Kamera) angelegt
     *   haben, "eigene" MediaStore-Einträge sind die Ausnahme.
     * - API 29: direkter `ContentResolver.delete` gelingt nur für selbst angelegte Einträge sofort;
     *   für fremde wirft er `RecoverableSecurityException`. Deren `IntentSender` gewährt beim
     *   Bestätigen nur die Berechtigung — anders als bei `createDeleteRequest` führt das System
     *   den Löschvorgang NICHT selbst aus, ein erneuter `delete`-Aufruf nach Bestätigung wäre
     *   nötig. Dieser erneute Aufruf ist in v1 bewusst nicht gebaut (s. README "Bekannte
     *   Einschränkung") — auf genau API 29 bleibt es beim einmaligen Antippen von "Löschen" nicht
     *   garantiert vollständig, ein zweiter Tap nach der Bestätigung schließt den Vorgang ab.
     * - API < 29: direkter `ContentResolver.delete` mit WRITE_EXTERNAL_STORAGE genügt immer ohne
     *   Bestätigungsdialog (vor dem RecoverableSecurityException-Mechanismus).
     */
    fun requestDelete(context: Context, uris: List<Uri>): DeleteOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            return DeleteOutcome.NeedsConfirmation(pendingIntent.intentSender)
        }
        var deleted = 0
        for (uri in uris) {
            try {
                deleted += context.contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                // RecoverableSecurityException existiert erst ab API 29 — auf API 26–28 darf die
                // Klasse nicht einmal referenziert werden (schon das Laden der Klassenreferenz
                // würde dort scheitern), eine SecurityException ist dort also immer endgültig.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    recoveryIntentSenderOrNull(e)?.let { return DeleteOutcome.NeedsConfirmation(it) }
                }
                throw e
            }
        }
        return DeleteOutcome.Deleted(deleted)
    }

    /**
     * Die von Grid/Betrachter aus aufgerufene "Löschen"-Aktion — ab API 30 landet ein Element
     * dabei bewusst zunächst im Papierkorb ([de.ble1st.gallery.ui.trash.TrashScreen], 30 Tage
     * automatischer Ablauf laut Plattformverhalten) statt sofort endgültig gelöscht zu werden;
     * erst dessen eigene "Endgültig löschen"-Aktion ruft [requestDelete] auf. Unterhalb von API 30
     * (kein Papierkorb-Konzept) bleibt es beim direkten, sofortigen Löschen wie zuvor — [requestDelete]
     * deckt diesen Fall bereits vollständig ab, hier nur zum passenden Aufruf verzweigt.
     */
    fun requestRemove(context: Context, uris: List<Uri>): DeleteOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return DeleteOutcome.NeedsConfirmation(requestTrash(context, uris, trashed = true))
        }
        return requestDelete(context, uris)
    }

    /** Papierkorb (`MediaStore.createTrashRequest`) existiert erst ab API 30 — der Einstiegspunkt
     * in [de.ble1st.gallery.ui.albums.AlbumsScreen] ist auf älteren Versionen bereits per
     * SDK-Prüfung ausgeblendet, diese Funktion wird dort also nie erreicht. `trashed=true` legt in
     * den Papierkorb, `trashed=false` stellt wieder her — beides derselbe Bestätigungsdialog-Flow
     * wie [requestDelete] (System führt die Aktion nach Bestätigung selbst aus). */
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestTrash(context: Context, uris: List<Uri>, trashed: Boolean): IntentSender {
        val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, trashed)
        return pendingIntent.intentSender
    }

    /** Eigene, `@RequiresApi`-annotierte Funktion statt eines Inline-`as?` im Aufrufer: Lint
     * erkennt `Build.VERSION.SDK_INT`-Wächter um einen Aufruf, nicht aber "dieser lokale Wert ist
     * nicht null, also war die Guard-Bedingung schon wahr" — der eigentliche
     * `RecoverableSecurityException`-Zugriff muss dafür in einer eigenen, klar auf API 29+
     * beschränkten Funktion stehen. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recoveryIntentSenderOrNull(e: SecurityException): IntentSender? {
        val recoverable = e as? RecoverableSecurityException ?: (e.cause as? RecoverableSecurityException)
        return recoverable?.userAction?.actionIntent?.intentSender
    }

    private fun launchOrToast(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.error_no_app_found), Toast.LENGTH_SHORT).show()
        }
    }
}
