package com.installment.customerapp.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.installment.customerapp.R

class InstallmentDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Called when QR / zero-touch provisioning finished and this app became Device Owner. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        ProvisioningExtras.consume(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
