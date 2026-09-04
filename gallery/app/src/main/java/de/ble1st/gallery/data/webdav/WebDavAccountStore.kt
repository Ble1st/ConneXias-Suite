package de.ble1st.gallery.data.webdav

import android.content.Context
import de.ble1st.gallery.data.crypto.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistiert das eine konfigurierte WebDAV-Backup-Konto. [SecretStore] statt Klartext-Prefs,
 * weil hier – anders als beim Rest der App – tatsächlich ein Passwort abgelegt wird (dieselbe
 * Wahl wie ConneXias Files' `WebDavAccountStore`).
 */
object WebDavAccountStore {
    private const val PREFS_FILE = "webdav_account"
    private const val KEY_ALIAS = "de.ble1st.gallery.webdav"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    private val _account = MutableStateFlow<WebDavAccount?>(null)
    val account: StateFlow<WebDavAccount?> = _account

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        _account.value = read(context)
        initialized = true
    }

    fun get(context: Context): WebDavAccount? {
        ensureLoaded(context)
        return _account.value
    }

    fun save(context: Context, account: WebDavAccount) {
        store(context).putStrings(
            mapOf(
                KEY_BASE_URL to account.baseUrl,
                KEY_USERNAME to account.username,
                KEY_PASSWORD to account.password,
            ),
        )
        _account.value = account
        initialized = true
    }

    fun clear(context: Context) {
        store(context).clear()
        _account.value = null
        initialized = true
    }

    @Volatile
    private var store: SecretStore? = null

    private fun store(context: Context): SecretStore =
        store ?: synchronized(this) {
            store ?: SecretStore(context, PREFS_FILE, KEY_ALIAS).also { store = it }
        }

    private fun read(context: Context): WebDavAccount? {
        val store = store(context)
        val baseUrl = store.getString(KEY_BASE_URL) ?: return null
        val username = store.getString(KEY_USERNAME) ?: return null
        val password = store.getString(KEY_PASSWORD) ?: return null
        return WebDavAccount(baseUrl, username, password)
    }
}
