package de.ble1st.files.data.webdav

import android.content.Context
import de.ble1st.files.data.crypto.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistiert konfigurierte WebDAV-Server. [SecretStore] statt Klartext-Prefs, weil hier – anders
 * als beim Rest der App – tatsächlich ein Passwort abgelegt wird. Ein simples
 * JSON-Array als ein einziger String-Wert statt eines eigenen Room-Schemas nur für eine
 * Handvoll Server-Einträge, die ein Nutzer typischerweise konfiguriert (org.json ist Teil der
 * Android-SDK, keine zusätzliche Abhängigkeit für Serialisierung nötig).
 */
object WebDavAccountStore {
    private const val PREFS_FILE = "webdav_accounts"
    private const val KEY_ALIAS = "de.ble1st.files.webdav"
    private const val KEY_ACCOUNTS = "accounts"

    private val _accounts = MutableStateFlow<List<WebDavAccount>>(emptyList())
    val accounts: StateFlow<List<WebDavAccount>> = _accounts

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        _accounts.value = readAll(context)
        initialized = true
    }

    fun list(context: Context): List<WebDavAccount> {
        ensureLoaded(context)
        return _accounts.value
    }

    fun upsert(context: Context, account: WebDavAccount) {
        ensureLoaded(context)
        _accounts.update { current ->
            val next = current.filterNot { it.id == account.id } + account
            persist(context, next)
            next
        }
    }

    fun remove(context: Context, accountId: String) {
        ensureLoaded(context)
        _accounts.update { current ->
            val next = current.filterNot { it.id == accountId }
            persist(context, next)
            next
        }
    }

    @Volatile
    private var store: SecretStore? = null

    private fun store(context: Context): SecretStore =
        store ?: synchronized(this) {
            store ?: SecretStore(context, PREFS_FILE, KEY_ALIAS).also { store = it }
        }

    private fun readAll(context: Context): List<WebDavAccount> {
        val raw = store(context).getString(KEY_ACCOUNTS) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                WebDavAccount(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    baseUrl = obj.getString("baseUrl"),
                    username = obj.getString("username"),
                    password = obj.getString("password"),
                )
            }.getOrNull()
        }
    }

    private fun persist(context: Context, accounts: List<WebDavAccount>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put("id", account.id)
                    put("label", account.label)
                    put("baseUrl", account.baseUrl)
                    put("username", account.username)
                    put("password", account.password)
                },
            )
        }
        store(context).putString(KEY_ACCOUNTS, array.toString())
    }
}
