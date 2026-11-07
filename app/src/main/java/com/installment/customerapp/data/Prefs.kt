package com.installment.customerapp.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Local state kept in app-private storage (not backed up, see data_extraction_rules.xml).
 * Everything the phone needs to enforce the agreed policy while offline lives here.
 */
class Prefs private constructor(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("installment_state", Context.MODE_PRIVATE)

    var apiUrl: String?
        get() = sp.getString("api_url", null)
        set(v) = sp.edit().putString("api_url", v?.trim()?.trimEnd('/')).apply()

    /** One-time code delivered by provisioning extras, waiting to be exchanged for a token. */
    var pendingCode: String?
        get() = sp.getString("pending_code", null)
        set(v) = sp.edit().putString("pending_code", v).apply()

    var enrollError: String?
        get() = sp.getString("enroll_error", null)
        set(v) = sp.edit().putString("enroll_error", v).apply()

    var deviceToken: String?
        get() = sp.getString("device_token", null)
        set(v) = sp.edit().putString("device_token", v).apply()

    var fcmToken: String?
        get() = sp.getString("fcm_token", null)
        set(v) = sp.edit().putString("fcm_token", v).apply()

    /** Latched restriction. Only an admin Unlock command (or release) clears it. */
    var restricted: Boolean
        get() = sp.getBoolean("restricted", false)
        set(v) = sp.edit().putBoolean("restricted", v).commit().let { }

    var restrictedReason: String?
        get() = sp.getString("restricted_reason", null)
        set(v) = sp.edit().putString("restricted_reason", v).apply()

    var policyEnabled: Boolean
        get() = sp.getBoolean("policy_enabled", false)
        set(v) = sp.edit().putBoolean("policy_enabled", v).apply()

    /** Trusted-clock epoch millis after which the phone restricts itself; 0 = none. */
    var restrictAfterMs: Long
        get() = sp.getLong("restrict_after", 0L)
        set(v) = sp.edit().putLong("restrict_after", v).apply()

    var policyMessage: String?
        get() = sp.getString("policy_message", null)
        set(v) = sp.edit().putString("policy_message", v).apply()

    var summaryJson: String?
        get() = sp.getString("summary", null)
        set(v) = sp.edit().putString("summary", v).apply()

    var lastSyncMs: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var lastSyncError: String?
        get() = sp.getString("last_sync_error", null)
        set(v) = sp.edit().putString("last_sync_error", v).apply()

    // Trusted clock anchors (see TrustedClock)
    var serverAnchorMs: Long
        get() = sp.getLong("server_anchor", 0L)
        set(v) = sp.edit().putLong("server_anchor", v).apply()

    var elapsedAnchorMs: Long
        get() = sp.getLong("elapsed_anchor", 0L)
        set(v) = sp.edit().putLong("elapsed_anchor", v).apply()

    var bootCountAnchor: Int
        get() = sp.getInt("boot_anchor", -1)
        set(v) = sp.edit().putInt("boot_anchor", v).apply()

    var lastTrustedMs: Long
        get() = sp.getLong("last_trusted", 0L)
        set(v) = sp.edit().putLong("last_trusted", v).apply()

    /** Set when the server no longer recognises this phone's token; the phone stays managed until re-enrolled. */
    var needsReenroll: Boolean
        get() = sp.getBoolean("needs_reenroll", false)
        set(v) = sp.edit().putBoolean("needs_reenroll", v).apply()

    val isEnrolled: Boolean get() = !deviceToken.isNullOrEmpty()

    fun clearAll() = sp.edit().clear().commit().let { }

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context).also { instance = it } }
    }
}
