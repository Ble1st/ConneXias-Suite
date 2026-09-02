package de.ble1st.files.data.fileops

import java.io.File
import java.io.IOException

/**
 * Die eigentlichen Datei-Operationen — reine, blockierende java.io-Funktionen ohne
 * Coroutine-/Android-Abhängigkeit, damit sie unit-testbar bleiben (s. FileOperationsTest). Der
 * Aufrufer (FileOperationService) ist dafür verantwortlich, sie auf einem Hintergrund-Dispatcher
 * auszuführen und regelmäßig `isCancelled` abzufragen.
 */
object FileOperations {

    /**
     * Liefert einen Namen, der in [parent] noch nicht existiert — "Bild.jpg" wird bei Kollision zu
     * "Bild (1).jpg", dann "Bild (2).jpg" usw. Ohne diese Auflösung würde ein Kopiervorgang in
     * denselben Ordner (oder ein Name-Konflikt beim Einfügen) sonst stillschweigend die
     * Zieldatei überschreiben (java.io.File-Operationen kennen keinen "fail if exists"-Modus außer
     * über umständliche NIO-APIs, die auf einem SAF-Pfad ohnehin nicht greifen würden).
     */
    fun uniqueName(parent: File, desiredName: String): String {
        if (!File(parent, desiredName).exists()) return desiredName
        val dotIndex = desiredName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val extension = if (dotIndex > 0) desiredName.substring(dotIndex) else ""
        var counter = 1
        var candidate: String
        do {
            candidate = "$baseName (${counter})$extension"
            counter++
        } while (File(parent, candidate).exists())
        return candidate
    }

    /**
     * Reduziert [name] auf einen reinen Dateinamen ohne Pfadanteile — sonst könnte ein
     * präparierter Name (getippt, oder von einer fremden ContentProvider-DISPLAY_NAME-Spalte /
     * einem WebDAV-Href geliefert) über ein enthaltenes "/" bzw. "\" oder "../"-Segmente außerhalb
     * des beabsichtigten Zielordners landen ("Path Traversal"). Wirft statt stillschweigend zu
     * kürzen, wenn danach nichts Sinnvolles übrig bleibt (leer, nur "." oder "..").
     */
    fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") {
            throw IOException("Ungültiger Name: „$name“")
        }
        return base
    }

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val target = File(parent, uniqueName(parent, sanitizeName(name)))
        if (!target.mkdirs()) throw IOException("Ordner konnte nicht angelegt werden: ${target.path}")
        target
    }

    fun createFile(parent: File, name: String): Result<File> = runCatching {
        val target = File(parent, uniqueName(parent, sanitizeName(name)))
        if (!target.createNewFile()) throw IOException("Datei konnte nicht angelegt werden: ${target.path}")
        target
    }

    fun rename(target: File, newName: String): Result<File> = runCatching {
        val parent = target.parentFile ?: throw IOException("Kein übergeordneter Ordner")
        val destination = File(parent, sanitizeName(newName))
        if (destination.exists()) throw IOException("„$newName“ existiert bereits")
        if (!target.renameTo(destination)) throw IOException("Umbenennen fehlgeschlagen: ${target.path}")
        destination
    }

    /**
     * Liefert die Namen aus [sources], die in [destinationDir] bereits existieren — Top-Level
     * only, wie [ConflictPolicy] das auch nur dort anwendet. Wird vom ViewModel *vor* dem
     * Enqueuen eines Kopier-/Verschiebe-Jobs aufgerufen, um zu entscheiden, ob der
     * Konflikt-Dialog überhaupt nötig ist (kein Konflikt → direkt einfügen wie bisher).
     */
    fun findConflicts(sources: List<File>, destinationDir: File): List<String> =
        sources.filter { File(destinationDir, it.name).exists() }.map { it.name }

    /**
     * Kopiert [sources] nach [destinationDir]. Läuft rekursiv für Ordner; jede einzelne
     * Datei/jeder Ordner scheitert unabhängig (ein defekter symbolischer Link o. Ä. bricht nicht
     * den gesamten Batch ab) — Ergebnis ist deshalb eine Liste von Outcomes statt eines einzelnen
     * Result.
     */
    fun copy(
        sources: List<File>,
        destinationDir: File,
        conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        isCancelled: () -> Boolean,
        onProgress: (fileName: String, processed: Int, total: Int) -> Unit,
    ): List<OperationOutcome> {
        val total = sources.sumOf { countEntries(it) }
        var processed = 0
        val outcomes = mutableListOf<OperationOutcome>()
        for (source in sources) {
            if (isCancelled()) break
            if (isSameOrDescendant(destinationDir, source)) {
                // Ordner in sich selbst (oder einen eigenen Unterordner) kopieren würde ohne diese
                // Prüfung endlos wachsen (der neu kopierte Inhalt wäre selbst wieder Teil der
                // rekursiven Quelle). Statt das zu erkennen und irgendwo abzubrechen: gar nicht
                // erst starten, klar als Fehler markieren.
                outcomes += OperationOutcome(source.path, IOException("Ziel liegt innerhalb der Quelle: ${source.path}"))
                processed += countEntries(source)
                onProgress(source.name, processed, total)
                continue
            }
            val existing = File(destinationDir, source.name)
            if (existing.exists() && conflictPolicy == ConflictPolicy.SKIP) {
                processed += countEntries(source)
                outcomes += OperationOutcome(source.path, skipped = true)
                onProgress(source.name, processed, total)
                continue
            }
            if (existing.exists() && conflictPolicy == ConflictPolicy.OVERWRITE) {
                processed = copyWithOverwrite(source, existing, destinationDir, processed, total, isCancelled, onProgress, outcomes)
                continue
            }
            val targetName = if (conflictPolicy == ConflictPolicy.KEEP_BOTH) {
                uniqueName(destinationDir, source.name)
            } else {
                source.name
            }
            processed = copyRecursive(
                source = source,
                destination = File(destinationDir, targetName),
                processed = processed,
                total = total,
                isCancelled = isCancelled,
                onProgress = onProgress,
                outcomes = outcomes,
            )
        }
        return outcomes
    }

    /** Prüft, ob [candidate] gleich [ancestor] ist oder darunter liegt (kanonische Pfade, damit
     * `..`/Symlinks nicht an der Prüfung vorbeiführen). Grundlage für die Selbstkopie-/
     * Selbstverschiebe-Erkennung in [copy]/[move]. */
    private fun isSameOrDescendant(candidate: File, ancestor: File): Boolean {
        val candidateCanonical = runCatching { candidate.canonicalFile }.getOrDefault(candidate.absoluteFile)
        val ancestorCanonical = runCatching { ancestor.canonicalFile }.getOrDefault(ancestor.absoluteFile)
        var current: File? = candidateCanonical
        while (current != null) {
            if (current == ancestorCanonical) return true
            current = current.parentFile
        }
        return false
    }

    /** OVERWRITE-Konfliktauflösung: kopiert [source] zuerst unter einem temporären Namen neben
     * [existing], löscht die vorhandene Zieldatei/den Zielordner erst *nach* einer vollständig
     * erfolgreichen Kopie und benennt dann um. Vorher wurde [existing] sofort gelöscht und erst
     * danach kopiert — schlug der Kopiervorgang fehl (Speicher voll, Abbruch, IO-Fehler), war die
     * ursprüngliche Datei bereits weg, ohne dass eine funktionierende Kopie an ihrer Stelle stand. */
    private fun copyWithOverwrite(
        source: File,
        existing: File,
        destinationDir: File,
        processed: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onProgress: (String, Int, Int) -> Unit,
        outcomes: MutableList<OperationOutcome>,
    ): Int {
        val tempTarget = File(destinationDir, uniqueName(destinationDir, ".crx-overwrite-${source.name}"))
        val tempOutcomes = mutableListOf<OperationOutcome>()
        val nextProcessed = copyRecursive(source, tempTarget, processed, total, isCancelled, onProgress, tempOutcomes)
        if (tempOutcomes.all { it.succeeded }) {
            deleteRecursive(existing, isCancelled)
            val finalTarget = File(destinationDir, source.name)
            if (tempTarget.renameTo(finalTarget)) {
                outcomes += tempOutcomes
            } else {
                deleteRecursive(tempTarget, isCancelled)
                outcomes += OperationOutcome(source.path, IOException("Ersetzen fehlgeschlagen: ${source.path}"))
            }
        } else {
            deleteRecursive(tempTarget, isCancelled)
            outcomes += tempOutcomes
        }
        return nextProcessed
    }

    private fun copyRecursive(
        source: File,
        destination: File,
        processed: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onProgress: (String, Int, Int) -> Unit,
        outcomes: MutableList<OperationOutcome>,
    ): Int {
        if (isCancelled()) return processed
        var nextProcessed = processed
        runCatching {
            if (source.isDirectory) {
                if (!destination.mkdirs() && !destination.isDirectory) {
                    throw IOException("Ordner konnte nicht angelegt werden: ${destination.path}")
                }
                source.listFiles()?.forEach { child ->
                    if (isCancelled()) return@forEach
                    nextProcessed = copyRecursive(
                        child,
                        File(destination, child.name),
                        nextProcessed,
                        total,
                        isCancelled,
                        onProgress,
                        outcomes,
                    )
                }
            } else {
                source.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }.onSuccess {
            outcomes += OperationOutcome(source.path)
        }.onFailure { error ->
            outcomes += OperationOutcome(source.path, error)
        }
        nextProcessed++
        onProgress(source.name, nextProcessed, total)
        return nextProcessed
    }

    /** Verschieben = Kopieren + Löschen der Quelle, außer beide Seiten liegen auf demselben
     * Dateisystem — dann reicht `renameTo` als billige Umbenennung ohne Datenkopie. Ein Umzug über
     * Speichergrenzen hinweg (intern → SD-Karte) braucht in jedem Fall den Kopierpfad, weil
     * `renameTo` bei unterschiedlichen Mountpoints laut Doku fehlschlagen darf. */
    fun move(
        sources: List<File>,
        destinationDir: File,
        conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        isCancelled: () -> Boolean,
        onProgress: (fileName: String, processed: Int, total: Int) -> Unit,
    ): List<OperationOutcome> {
        val total = sources.sumOf { countEntries(it) }
        var processed = 0
        val outcomes = mutableListOf<OperationOutcome>()
        for (source in sources) {
            if (isCancelled()) break
            if (isSameOrDescendant(destinationDir, source)) {
                outcomes += OperationOutcome(source.path, IOException("Ziel liegt innerhalb der Quelle: ${source.path}"))
                processed += countEntries(source)
                onProgress(source.name, processed, total)
                continue
            }
            val existing = File(destinationDir, source.name)
            if (existing.exists() && conflictPolicy == ConflictPolicy.SKIP) {
                // Quelle bleibt unangetastet liegen — anders als bei Kopieren gibt es hier
                // sonst das Risiko, Daten zu verlieren, wenn die Quelle trotz übersprungenem
                // Ziel gelöscht würde.
                processed += countEntries(source)
                outcomes += OperationOutcome(source.path, skipped = true)
                onProgress(source.name, processed, total)
                continue
            }
            if (existing.exists() && conflictPolicy == ConflictPolicy.OVERWRITE) {
                // Erst versuchen, direkt über die vorhandene Zieldatei zu renamen (POSIX rename(2)
                // ersetzt eine bestehende reguläre Datei atomar) — nur wenn das nicht klappt
                // (unterschiedliche Mountpoints, Ziel ist ein Ordner) auf den sicheren
                // Kopier-dann-Ersetzen-Pfad ausweichen, der [existing] erst nach vollständigem
                // Erfolg löscht statt vorab (s. copyWithOverwrite-Doc für dasselbe Problem beim
                // reinen Kopieren).
                val renamedOverExisting = if (!existing.isDirectory) {
                    runCatching { source.renameTo(existing) }.getOrDefault(false)
                } else {
                    false
                }
                if (renamedOverExisting) {
                    processed += countEntries(existing)
                    outcomes += OperationOutcome(source.path)
                    onProgress(source.name, processed, total)
                    continue
                }
                processed = moveWithOverwrite(source, existing, destinationDir, processed, total, isCancelled, onProgress, outcomes)
                continue
            }
            val targetName = if (conflictPolicy == ConflictPolicy.KEEP_BOTH) {
                uniqueName(destinationDir, source.name)
            } else {
                source.name
            }
            val target = File(destinationDir, targetName)
            val renamed = runCatching { source.renameTo(target) }.getOrDefault(false)
            if (renamed) {
                processed += countEntries(target)
                outcomes += OperationOutcome(source.path)
                onProgress(source.name, processed, total)
                continue
            }
            // Fallback über Speichergrenzen hinweg: kopieren, dann Quelle löschen.
            val before = outcomes.size
            processed = copyRecursive(source, target, processed, total, isCancelled, onProgress, outcomes)
            val copyFailed = outcomes.drop(before).any { !it.succeeded }
            if (!copyFailed) {
                deleteRecursive(source, isCancelled)
            }
        }
        return outcomes
    }

    /** OVERWRITE-Konfliktauflösung für [move], wenn ein direktes `renameTo` über [existing] nicht
     * möglich war (Ordner-Ziel oder unterschiedliche Mountpoints): kopiert [source] zuerst unter
     * einem temporären Namen, löscht [existing] erst nach vollständigem Kopiererfolg und löscht die
     * Quelle erst, nachdem das Ersetzen tatsächlich geklappt hat. */
    private fun moveWithOverwrite(
        source: File,
        existing: File,
        destinationDir: File,
        processed: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onProgress: (String, Int, Int) -> Unit,
        outcomes: MutableList<OperationOutcome>,
    ): Int {
        val tempTarget = File(destinationDir, uniqueName(destinationDir, ".crx-overwrite-${source.name}"))
        val tempOutcomes = mutableListOf<OperationOutcome>()
        val nextProcessed = copyRecursive(source, tempTarget, processed, total, isCancelled, onProgress, tempOutcomes)
        if (tempOutcomes.all { it.succeeded }) {
            deleteRecursive(existing, isCancelled)
            val finalTarget = File(destinationDir, source.name)
            if (tempTarget.renameTo(finalTarget)) {
                deleteRecursive(source, isCancelled)
                outcomes += tempOutcomes
            } else {
                deleteRecursive(tempTarget, isCancelled)
                outcomes += OperationOutcome(source.path, IOException("Ersetzen fehlgeschlagen: ${source.path}"))
            }
        } else {
            deleteRecursive(tempTarget, isCancelled)
            outcomes += tempOutcomes
        }
        return nextProcessed
    }

    fun delete(
        targets: List<File>,
        isCancelled: () -> Boolean,
        onProgress: (fileName: String, processed: Int, total: Int) -> Unit,
    ): List<OperationOutcome> {
        val total = targets.sumOf { countEntries(it) }
        var processed = 0
        val outcomes = mutableListOf<OperationOutcome>()
        for (target in targets) {
            if (isCancelled()) break
            processed = deleteRecursiveCounting(target, processed, total, isCancelled, onProgress, outcomes)
        }
        return outcomes
    }

    private fun deleteRecursive(file: File, isCancelled: () -> Boolean) {
        if (isCancelled()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it, isCancelled) }
        }
        file.delete()
    }

    private fun deleteRecursiveCounting(
        file: File,
        processed: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onProgress: (String, Int, Int) -> Unit,
        outcomes: MutableList<OperationOutcome>,
    ): Int {
        if (isCancelled()) return processed
        var nextProcessed = processed
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (isCancelled()) return@forEach
                nextProcessed = deleteRecursiveCounting(child, nextProcessed, total, isCancelled, onProgress, outcomes)
            }
        }
        runCatching {
            if (!file.delete() && file.exists()) throw IOException("Löschen fehlgeschlagen: ${file.path}")
        }.onSuccess {
            outcomes += OperationOutcome(file.path)
        }.onFailure { error ->
            outcomes += OperationOutcome(file.path, error)
        }
        nextProcessed++
        onProgress(file.name, nextProcessed, total)
        return nextProcessed
    }

    private fun countEntries(file: File): Int {
        if (!file.isDirectory) return 1
        var count = 1 // der Ordner selbst zählt als ein Fortschritts-Schritt (mkdirs/delete).
        file.listFiles()?.forEach { count += countEntries(it) }
        return count
    }
}
