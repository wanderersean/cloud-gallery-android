package org.fossify.gallery.cloud

import android.content.Context
import android.content.SharedPreferences

class CloudAccountManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cloud_account", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<OnLoginStateChangeListener>()

    interface OnLoginStateChangeListener {
        fun onLoginStateChanged(isLoggedIn: Boolean)
    }

    companion object {
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"

        @Volatile
        private var instance: CloudAccountManager? = null

        fun getInstance(context: Context): CloudAccountManager {
            return instance ?: synchronized(this) {
                instance ?: CloudAccountManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var jwtToken: String
        get() = prefs.getString(KEY_JWT_TOKEN, "") ?: ""
        set(value) {
            val oldLoggedIn = isLoggedIn
            prefs.edit().putString(KEY_JWT_TOKEN, value).apply()
            val newLoggedIn = isLoggedIn
            if (oldLoggedIn != newLoggedIn) {
                notifyListeners(newLoggedIn)
            }
        }

    var accountId: Long
        get() = prefs.getLong(KEY_ACCOUNT_ID, 0)
        set(value) = prefs.edit().putLong(KEY_ACCOUNT_ID, value).apply()

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    val isLoggedIn: Boolean
        get() = jwtToken.isNotEmpty()

    fun saveLoginInfo(token: String, accountId: Long, displayName: String) {
        val oldLoggedIn = isLoggedIn
        this.jwtToken = token
        this.accountId = accountId
        this.displayName = displayName
        if (!oldLoggedIn) {
            notifyListeners(true)
        }
    }

    fun logout() {
        if (isLoggedIn) {
            prefs.edit()
                .remove(KEY_JWT_TOKEN)
                .remove(KEY_ACCOUNT_ID)
                .remove(KEY_DISPLAY_NAME)
                .apply()
            notifyListeners(false)
        }
    }

    fun addListener(listener: OnLoginStateChangeListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: OnLoginStateChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners(isLoggedIn: Boolean) {
        synchronized(listeners) {
            listeners.toList().forEach { listener ->
                listener.onLoginStateChanged(isLoggedIn)
            }
        }
    }
}
