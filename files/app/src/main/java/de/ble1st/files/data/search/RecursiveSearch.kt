package de.ble1st.files.data.search

import de.ble1st.files.data.fs.LocalFileSystem
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Namenssuche über den gesamten Teilbaum unterhalb eines Ordners. Bis 2026-09-03 filterte die
 * Suche ausschließlich die bereits geladene Liste des aktuellen Ordners — bei einem Dateimanager,
 * der über `MANAGE_EXTERNAL_STORAGE` den kompletten Gerätespeicher verwaltet, war das die größte
 * funktionale Lücke: "wo liegt diese Datei" ließ sich damit gerade nicht beantworten.
 *
 * Bewusst **breitensuchend** (`ArrayDeque` als Warteschlange) statt rekursiv in die Tiefe: Treffer
 * nahe am Startordner erscheinen zuerst, was fast immer die gesuchten sind — eine Tiefensuche
 * verbringt bei `/storage/emulated/0` erst Sekunden in `Android/data`, bevor sie die eigenen
 * Ordner überhaupt anfasst. Die Breitensuche kostet dafür Speicher für die Warteschlange, was bei
 * den Größenordnungen eines Gerätespeichers unkritisch ist.
 *
 * Drei harte Grenzen, alle mit demselben Zweck — eine Suche darf nie unbegrenzt laufen, weil sie
 * auf einem Gerätespeicher mit Hunderttausenden Dateien sonst faktisch nie endet:
 *
 * - [MAX_RESULTS] — mehr Treffer sind für eine Namenssuche ohnehin unbrauchbar; das Ergebnis wird
 *   dann als `truncated` markiert, damit die UI das ehrlich sagen kann statt eine vollständige
 *   Liste vorzutäuschen.
 * - [MAX_VISITED_DIRECTORIES] — begrenzt die Laufzeit auch dann, wenn es *keine* Treffer gibt (der
 *   teuerste Fall: der ganze Baum wird durchlaufen und nichts gefunden).
 * - [MAX_DEPTH] — zusätzliche Absicherung gegen pathologisch tiefe Bäume.
 *
 * Symbolische Links auf Verzeichnisse werden **nicht** verfolgt (`LocalFileSystem.isSymlink`,
 * `lstat`-basiert) — dieselbe Regel wie beim Kopieren/Löschen/Zählen (analyse.md, 2. Durchgang,
 * Befund 2-12). Ohne sie könnte ein Ringlink die Suche endlos im Kreis laufen lassen, und die drei
 * Grenzen oben würden das zwar irgendwann beenden, aber mit einem sinnlos verbrauchten Budget.
 *
 * Framework-frei (nur `java.io`/`java.nio`) und damit direkt unit-testbar — die Suche selbst hat
 * keinen `Context`, das Abbruch-Signal kommt als Lambda von außen (dasselbe `isCancelled`-Muster
 * wie in `FileOperations`). Deshalb liefert sie bewusst nackte [File]-Objekte statt fertiger
 * `FileEntry`s: deren `from()` löst über `MimeTypeMap` die Dateikategorie auf und wäre damit
 * Android-gebunden. Die Umwandlung passiert im ViewModel, im selben IO-Kontext — sie kostet dort
 * keinen zusätzlichen Durchlauf, hält aber die Suchlogik unter einem reinen JVM-Test lauffähig.
 */
object RecursiveSearch {

    const val MAX_RESULTS = 500
    const val MAX_VISITED_DIRECTORIES = 20_000
    const val MAX_DEPTH = 16

    /** [truncated] bedeutet: die Suche wurde durch eine der Grenzen beendet, es kann weitere
     * Treffer geben. [cancelled] bedeutet: der Aufrufer hat abgebrochen (neue Eingabe, Bildschirm
     * verlassen) — das Ergebnis ist dann unvollständig *und* nicht mehr relevant. */
    data class Result(
        val files: List<File> = emptyList(),
        val truncated: Boolean = false,
        val cancelled: Boolean = false,
    )

    /**
     * Sucht unterhalb von [root] nach Einträgen, deren Name [query] enthält (ohne Berücksichtigung
     * der Groß-/Kleinschreibung). [root] selbst ist nie Teil des Ergebnisses.
     *
     * Blockierend — der Aufrufer sorgt für einen Hintergrund-Thread (`Dispatchers.IO`). Zwischen
     * jedem Verzeichnis wird [isCancelled] geprüft, ein Abbruch kommt also spätestens nach einer
     * Verzeichnislistung an.
     */
    fun search(root: File, query: String, isCancelled: () -> Boolean = { false }): Result {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return Result()

        val results = mutableListOf<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty()) {
            if (isCancelled()) return Result(results.toList(), truncated = false, cancelled = true)
            if (visited >= MAX_VISITED_DIRECTORIES) return Result(results.toList(), truncated = true)

            val (directory, depth) = queue.removeFirst()
            visited++

            // listFiles() liefert null für ein nicht (mehr) lesbares Verzeichnis — auf einem
            // Gerätespeicher völlig normal (Android/data ist selbst mit All-Files-Access teilweise
            // gesperrt). Ein solcher Ordner wird übersprungen, nicht als Fehler behandelt: eine
            // Suche, die an der ersten unlesbaren Stelle abbricht, wäre unbrauchbar.
            val children = directory.listFiles() ?: continue

            for (child in children) {
                if (child.name.lowercase(Locale.ROOT).contains(needle)) {
                    results += child
                    if (results.size >= MAX_RESULTS) return Result(results.toList(), truncated = true)
                }
                // Absteigen nur in echte Verzeichnisse, nie in einen Verzeichnis-Symlink (s.
                // Klassendoc). Die Treffer-Prüfung oben läuft trotzdem auch für den Link selbst —
                // gefunden werden soll er, nur durchsucht wird er nicht.
                if (depth < MAX_DEPTH && child.isDirectory && !LocalFileSystem.isSymlink(child)) {
                    queue.add(child to depth + 1)
                }
            }
        }
        return Result(results.toList())
    }

    /**
     * Der Pfad von [file] relativ zu [root], ohne den Dateinamen selbst — also der Ordner, in dem
     * der Treffer liegt, aus Sicht des Suchstarts. Für einen Treffer direkt in [root] ist das
     * `null` (es gibt nichts anzuzeigen).
     *
     * Die Suchergebnisliste zeigt das als Unterzeile: ohne sie stünden mehrere gleichnamige
     * Treffer aus verschiedenen Ordnern ununterscheidbar untereinander.
     */
    fun relativeParentPath(root: File, file: File): String? {
        val rootPath = root.path.removeSuffix(File.separator)
        val parentPath = file.parent ?: return null
        if (parentPath == rootPath) return null
        if (!parentPath.startsWith("$rootPath${File.separator}")) return parentPath
        return parentPath.removePrefix("$rootPath${File.separator}")
    }
}
