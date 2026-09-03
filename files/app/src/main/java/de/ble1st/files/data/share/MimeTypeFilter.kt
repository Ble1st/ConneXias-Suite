package de.ble1st.files.data.share

import java.util.Locale

/**
 * Abgleich eines konkreten MIME-Typs (`image/jpeg`) gegen die Typmuster, die eine fremde App per
 * `ACTION_GET_CONTENT` angefragt hat (`image/` + `*`, `application/pdf`, [WILDCARD]).
 *
 * (Die Untertyp-Wildcard steht in den Kommentaren dieser Datei bewusst getrennt als `image/`
 * + `*` statt zusammengeschrieben: Kotlin verschachtelt Blockkommentare, ein `/`+`*` im KDoc
 * öffnet also einen inneren Kommentar und lässt den äußeren offen — der Compiler meldet das
 * dann erst viele Zeilen später an einer völlig unverdächtigen Stelle.)
 *
 * Bewusst eine eigene, framework-freie Implementierung statt `android.content.ClipDescription
 * .compareMimeTypes` oder `androidx.core.util.MimeTypeFilter`: Erstere ist `@hide`/nicht Teil der
 * öffentlichen API, Letztere behandelt den Fall "Datei hat gar keinen erkennbaren Typ" nicht so,
 * wie er hier gebraucht wird (s. [matches]). Und framework-frei heißt: direkt unit-testbar, ohne
 * Robolectric — dieselbe Trennung wie sonst in dieser App zwischen `data/`-Logik und
 * Android-Anbindung.
 *
 * Die Muster kommen von einer fremden App und sind nicht vertrauenswürdig: ein leeres, kaputtes
 * oder mehrfach mit `/` versehenes Muster darf nicht zu einer Exception führen, sondern gilt
 * schlicht als "passt nicht".
 */
object MimeTypeFilter {

    const val WILDCARD = "*/*"

    /**
     * Passt [mimeType] auf mindestens eines der [patterns]?
     *
     * Sonderfälle, beide bewusst so entschieden:
     * - **Leere Musterliste** = keine Einschränkung → alles passt. Ein Aufrufer, der weder `type`
     *   noch `EXTRA_MIME_TYPES` setzt, hat keine Erwartung formuliert.
     * - **[mimeType] ist `null`** (Dateiendung unbekannt, `MimeTypeMap` kennt sie nicht) → passt
     *   nur, wenn ein Muster ohnehin alles zulässt. Andernfalls wäre die Alternative, eine Datei
     *   unbekannten Typs an eine App zu geben, die ausdrücklich `image/` + `*` verlangt hat — genau der
     *   Vertragsbruch, den dieser Filter beseitigen soll. Lieber nicht anbieten als falsch
     *   anbieten.
     */
    fun matches(mimeType: String?, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return true
        if (patterns.any { normalize(it) == WILDCARD }) return true
        val actual = mimeType?.let { normalize(it) } ?: return false
        return patterns.any { pattern -> matchesSingle(actual, normalize(pattern)) }
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)

    private fun matchesSingle(actual: String, pattern: String): Boolean {
        if (pattern == actual) return true
        val slash = pattern.indexOf('/')
        // Kein oder mehr als ein "/" — kein gültiger MIME-Typ, gilt als "passt nicht".
        if (slash <= 0 || pattern.indexOf('/', slash + 1) != -1) return false
        val patternType = pattern.substring(0, slash)
        val patternSubtype = pattern.substring(slash + 1)
        if (patternSubtype != "*") return false
        return actual.startsWith("$patternType/")
    }
}
