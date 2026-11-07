package com.installment.customerapp.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.work.Scheduler

/**
 * The QR code carries the server address and the one-time enrollment code in the "admin extras bundle".
 * Depending on the Android version that bundle reaches the app through either the provisioning-complete broadcast
 * or the policy-compliance activity (Android 10+). Both paths call this single function.
 */
object ProvisioningExtras {
    private const val TAG = "ProvisioningExtras"

    fun bundleFrom(intent: Intent): PersistableBundle? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }

    /** Stores the extras (if present), applies the baseline protections and starts enrollment. Safe to call twice. */
    fun consume(context: Context, intent: Intent) {
        val prefs = Prefs.get(context)
        val extras = bundleFrom(intent)
        val url = extras?.getString("api_url")
        val code = extras?.getString("enrollment_code")
        Log.i(TAG, "provisioning extras received: url=${!url.isNullOrBlank()} code=${!code.isNullOrBlank()}")

        if (!url.isNullOrBlank()) prefs.apiUrl = url
        if (!code.isNullOrBlank() && !prefs.isEnrolled) prefs.pendingCode = code

        DevicePolicy(context).applyBaseline()
        Scheduler.schedulePeriodic(context)
        if (!prefs.isEnrolled && !prefs.pendingCode.isNullOrBlank() && !prefs.apiUrl.isNullOrBlank()) {
            Scheduler.enqueueEnrollment(context)
        }
    }
}
