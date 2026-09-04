package de.ble1st.gallery.data.media

enum class SortOrder { DATE, NAME, SIZE }

/** Reine Sortierlogik (kein MediaStore-Zugriff) für [de.ble1st.gallery.ui.grid.MediaGridScreen] —
 * dieselbe Handvoll Kriterien wie ConneXias Files' Dateibrowser-Sortierung, hier auf [MediaItem]
 * statt `FileEntry`. */
fun sortedItems(items: List<MediaItem>, order: SortOrder): List<MediaItem> = when (order) {
    SortOrder.DATE -> items.sortedByDescending { it.dateSortMillis }
    SortOrder.NAME -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    SortOrder.SIZE -> items.sortedByDescending { it.sizeBytes }
}
