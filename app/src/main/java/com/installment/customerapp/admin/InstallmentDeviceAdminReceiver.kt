package com.installment.customerapp.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import com.installment.customerapp.R
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.work.Scheduler

class InstallmentDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Called when QR / zero-touch provisioning finished and this app became Device Owner. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
        val prefs = Prefs.get(context)
        extras?.getString("api_url")?.let { prefs.apiUrl = it }
        extras?.getString("enrollment_code")?.let { prefs.pendingCode = it }

        DevicePolicy(context).applyBaseline()
        Scheduler.schedulePeriodic(context)
        Scheduler.enqueueEnrollment(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
