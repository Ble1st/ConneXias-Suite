package de.ble1st.gallery.data.webdav

/**
 * Der eine konfigurierte WebDAV-Backup-Server (Nextcloud/ownCloud/generischer mod_dav-Server) —
 * anders als ConneXias Files' `WebDavAccount` (dort eine Liste mehrerer Server für einen vollen
 * Datei-Browser) genügt hier ein einziges Konto: Cloud-Sync ist ein einfacher
 * Ein-Wege-Sicherungs-Anwendungsfall ("meine Fotos auf meinen Server"), keine
 * Mehrserver-Dateiverwaltung. [baseUrl] ohne abschließenden Schrägstrich (wird bei jeder
 * Pfad-Verkettung in [WebDavClient] selbst ergänzt).
 */
data class WebDavAccount(
    val baseUrl: String,
    val username: String,
    val password: String,
)
