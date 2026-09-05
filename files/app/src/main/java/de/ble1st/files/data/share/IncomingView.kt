package de.ble1st.files.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import de.ble1st.files.data.fileops.FileOperations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Von einer fremden App per ACTION_VIEW ("Öffnen mit ConneXias Files" — E-Mail-Anhang,
 * Download-Benachrichtigung, Browser) empfangene Einzel-Uri. [de.ble1st.files.MainActivity]
 * befüllt das bei onCreate/onNewIntent, [de.ble1st.files.nav.FilesNavHost] konsumiert es, sobald
 * Speicherzugriff besteht (analyse.md Abschnitt 5 — "Files ohne ACTION_VIEW").
 *
 * Anders als [IncomingShare] (Import in einen vom Nutzer gewählten Zielordner) landet die Datei
 * hier zunächst nur in einem frischen, mit dieser einen Datei gefüllten Cache-Ordner — der wird
 * anschließend wie ein ganz gewöhnlicher Ordner geöffnet. Dadurch läuft der Tap auf die Datei über
 * denselben, bereits vollständig getesteten Dispatch wie jede lokale Datei (eigener Betrachter
 * oder "Öffnen mit anderer App", s. FilesNavHost.handleFileOpen) — kein separater,
 * halb-funktionaler Betrachter-Pfad nur für extern geöffnete Dateien. Ein "Speichern" ergibt sich
 * dabei kostenlos: der Nutzer kann die Datei über die ganz normale Auswahl-/Verschieben-Funktion
 * an einen dauerhaften Ort kopieren, statt dass die App das stillschweigend selbst entscheidet.
 */
object IncomingView {
    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending

    /** Obergrenze für eine extern angezeigte Datei — dieselbe Abwägung wie bei
     * WebDavClient.MAX_DOWNLOAD_BYTES: die Quelle ist eine fremde App, nicht der Nutzer selbst. */
    private const val MAX_VIEW_BYTES = 10L * 1024 * 1024 * 1024

    fun setFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "content") return
        _pending.value = uri
    }

    fun consume(): Uri? {
        val uri = _pending.value
        _pending.value = null
        return uri
    }

    /**
     * Kopiert [uri] in einen frischen Unterordner von `cacheDir` und liefert dessen Verzeichnis
     * zurück (nicht die Datei selbst — [de.ble1st.files.nav.FilesNavHost] öffnet dieses
     * Verzeichnis direkt als Browser-Ziel). `null` bei jedem Fehler (Stream nicht lesbar, Name
     * nicht sanierbar, Budget überschritten) — der Aufrufer zeigt dann eine Fehlermeldung statt
     * abzustürzen. Muss auf einem IO-Dispatcher laufen.
     */
    fun copyToCache(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: "datei"
        // displayName kommt aus einer fremden ContentProvider-DISPLAY_NAME-Spalte — nicht
        // vertrauenswürdig, s. FileOperations.sanitizeName-Doc (dieselbe Lücke wie beim
        // SAF-Import/WebDAV-Download).
        val safeName = runCatching { FileOperations.sanitizeName(displayName) }.getOrNull() ?: return null
        val dir = File(context.cacheDir, "external_view/${UUID.randomUUID()}")
        if (!dir.mkdirs()) return null
        val target = File(dir, safeName)
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> copyLimited(input, output, MAX_VIEW_BYTES) }
            } ?: return@runCatching null
            dir
        }.getOrElse {
            dir.deleteRecursively()
            null
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    /** Byte-Budget-geprüfte Streaming-Kopie — dasselbe Muster wie
     * `ZipOperations.copyLimited`/`WebDavClient.copyLimited`, hier ein drittes Mal dupliziert statt
     * geteilt, weil die drei Aufrufer in unterschiedlichen, unabhängig verifizierten Modulen liegen
     * (Zip-Extraktion, WebDAV-Client, Intent-Empfang) und eine gemeinsame Utility-Datei an dieser
     * Stelle mehr Kopplung als Nutzen brächte. */
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, remainingBudget: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > remainingBudget) {
                throw IOException("Datei überschreitet die maximale Größe für externes Anzeigen")
            }
            output.write(buffer, 0, read)
        }
    }
}
