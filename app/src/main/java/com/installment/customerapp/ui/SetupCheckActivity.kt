package com.installment.customerapp.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.work.EnforcementService

/**
 * Checklist for the shop technician (and customer) so the phone keeps enforcing reliably on brands with
 * aggressive battery management. Shows what is on, what is off, and opens the right settings page.
 */
class SetupCheckActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Phone setup check"
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(container) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val prefs = Prefs.get(this)
        val policy = DevicePolicy(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        text("Phone setup check", 22f, bold = true)
        text("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}", 13f, color = 0xFF555555.toInt())

        row("Managed device (Device Owner)", policy.isDeviceOwner,
            if (policy.isDeviceOwner) "Uninstall protection and restrictions are active."
            else "NOT managed. Enroll this phone as Device Owner (see the enrollment guide). Without it nothing is protected.")

        row("Enrolled with the shop", prefs.isEnrolled,
            if (prefs.isEnrolled) "Registered with the server." else "Enter the enrollment code on the main screen.")

        row("Background service running", EnforcementService.running,
            if (EnforcementService.running) "Offline lock check is running every minute."
            else "Not running. Open the app once; if it stops again, fix the battery and auto-start settings below.")

        val notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        row("Notifications allowed", notifOk, if (notifOk) "Reminders can be shown." else "Reminders and the managed notice may be hidden.",
            "Open notification settings") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }

        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        row("Battery: no restrictions", batteryOk,
            if (batteryOk) "Not limited by battery optimisation."
            else "Set to unrestricted, otherwise some phones delay the lock check and syncing.",
            "Allow background activity") { requestBatteryExemption() }

        row("Auto-start / background launch", null, vendorAdvice(),
            "Open ${Build.MANUFACTURER} auto-start settings") { openVendorAutoStart() }

        val last = prefs.lastSyncMs
        row("Last server contact", last > 0,
            if (last > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)) else "Never. Check internet and the server address.")
    }

    /** ok = null means "cannot be detected, check by hand". */
    private fun row(name: String, ok: Boolean?, detail: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val (mark, color) = when (ok) {
            true -> "OK" to 0xFF146C43.toInt()
            false -> "ACTION NEEDED" to 0xFF842029.toInt()
            null -> "CHECK MANUALLY" to 0xFF664D03.toInt()
        }
        text(name, 16f, bold = true, topMargin = 18)
        text(mark, 12f, bold = true, color = color)
        text(detail, 14f)
        if (action != null && ok != true) {
            container.addView(Button(this).apply {
                text = actionLabel
                setOnClickListener { action() }
            })
        }
    }

    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (!tryStart(direct) && !tryStart(list)) openAppDetails()
    }

    private fun vendorAdvice(): String = when (brand()) {
        "xiaomi", "redmi", "poco" -> "Xiaomi/Redmi/Poco: turn on Autostart for this app, set Battery saver to 'No restrictions', and lock the app in Recents."
        "oppo", "realme", "oneplus" -> "Oppo/Realme: allow Auto-launch and Background activity for this app, and set battery usage to 'Allow background activity'."
        "vivo", "iqoo" -> "Vivo: allow Autostart and 'High background power consumption' for this app."
        "infinix", "tecno", "itel", "transsion" -> "Infinix/Tecno: allow Auto-start and set battery to 'No restrictions' for this app in Phone Master / Settings."
        else -> "If this brand closes apps in the background, allow auto-start and unrestricted battery for this app."
    }

    private fun openVendorAutoStart() {
        val candidates: List<ComponentName> = when (brand()) {
            "xiaomi", "redmi", "poco" -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            "oppo", "realme", "oneplus" -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            "vivo", "iqoo" -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            "infinix", "tecno", "itel", "transsion" -> listOf(
                ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                ComponentName("com.itel.autobootmanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"))
            else -> emptyList()
        }
        // Vendor pages differ per OS version; fall back to this app's own settings page if none opens.
        if (candidates.none { tryStart(Intent().setComponent(it)) }) openAppDetails()
    }

    private fun openAppDetails() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun brand() = Build.MANUFACTURER.lowercase()

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = 0xFF222222.toInt(), topMargin: Int = 4) {
        container.addView(TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(topMargin), 0, 0) }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
