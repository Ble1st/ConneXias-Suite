package de.ble1st.files.data.webdav

import java.util.UUID

/**
 * Ein konfigurierter WebDAV-Server (Nextcloud/ownCloud/generischer mod_dav-Server). [baseUrl] ohne
 * abschließenden Schrägstrich (wird bei jeder Pfad-Verkettung in [WebDavClient] selbst ergänzt).
 */
data class WebDavAccount(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val baseUrl: String,
    val username: String,
    val password: String,
)
