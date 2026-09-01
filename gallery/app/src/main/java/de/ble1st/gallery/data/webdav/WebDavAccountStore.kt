package de.ble1st.gallery.data.webdav

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistiert das eine konfigurierte WebDAV-Backup-Konto. [EncryptedSharedPreferences] statt
 * Klartext-Prefs, weil hier – anders als beim Rest der App – tatsächlich ein Passwort abgelegt
 * wird (dieselbe Wahl wie ConneXias Files' `WebDavAccountStore`).
 */
object WebDavAccountStore {
    private const val PREFS_FILE = "webdav_account_encrypted"
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
        prefs(context).edit {
            putString(KEY_BASE_URL, account.baseUrl)
            putString(KEY_USERNAME, account.username)
            putString(KEY_PASSWORD, account.password)
        }
        _account.value = account
        initialized = true
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
        _account.value = null
        initialized = true
    }

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun read(context: Context): WebDavAccount? {
        val prefs = prefs(context)
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return WebDavAccount(baseUrl, username, password)
    }
}
