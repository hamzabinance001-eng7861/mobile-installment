package com.installment.customerapp.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.ui.LockActivity

/**
 * All device-wide management goes through Android's official DevicePolicyManager and only works while this
 * app is the Device Owner (provisioned on a factory-reset phone). On a normally installed APK every call here
 * is skipped and [isDeviceOwner] is false: nothing below pretends to protect such a phone.
 */
class DevicePolicy(private val context: Context) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, InstallmentDeviceAdminReceiver::class.java)
    private val pkg = context.packageName
    private val prefs = Prefs.get(context)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(pkg)

    /** Protections that stay on for the whole life of the agreement. Idempotent; safe to call on every boot. */
    fun applyBaseline() {
        if (!isDeviceOwner) return
        runCatching { dpm.setUninstallBlocked(admin, pkg, true) }.logFail("uninstall block")
        if (Build.VERSION.SDK_INT >= 30) {
            // Stops the user force-stopping or clearing data of the managed app from Settings.
            runCatching { dpm.setUserControlDisabledPackages(admin, listOf(pkg)) }.logFail("user control")
        } else {
            addRestriction(UserManager.DISALLOW_APPS_CONTROL)
        }
        listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_DEBUGGING_FEATURES, // disables USB debugging
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        ).forEach(::addRestriction)
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                dpm.setPermissionGrantState(admin, pkg, android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.logFail("notification permission")
        }
    }

    /** Lock-task kiosk screen. Calls to the shop and emergency calls stay possible through the dialer. */
    fun restrict(reason: String) {
        prefs.restrictedReason = reason
        prefs.restricted = true
        if (!isDeviceOwner) return
        val allowed = mutableListOf(pkg)
        dialerPackage()?.let { allowed.add(it) }
        runCatching { dpm.setLockTaskPackages(admin, allowed.toTypedArray()) }.logFail("lock task packages")
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }.logFail("lock task features")
            runCatching { dpm.setStatusBarDisabled(admin, true) }.logFail("status bar")
        }
        // Home button and reboot both land on the restricted screen. The home alias exists only while restricted.
        setLockHomeEnabled(true)
        runCatching {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, lockHome)
        }.logFail("preferred home")
        launchLockScreen()
    }

    private val lockHome = ComponentName(context, "com.installment.customerapp.ui.LockHome")

    /** Enables/disables the HOME alias of the restricted screen. Never leave it enabled on an unrestricted phone. */
    fun setLockHomeEnabled(enabled: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                lockHome,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.logFail("lock home alias")
    }

    /** Self-heal: if the phone is not restricted, the home alias must be off (e.g. after a release or an old build). */
    fun ensureHomeNotHijacked() {
        if (!prefs.restricted) setLockHomeEnabled(false)
    }

    fun unrestrict() {
        prefs.restricted = false
        prefs.restrictedReason = null
        setLockHomeEnabled(false)
        if (!isDeviceOwner) return
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }.logFail("clear home")
        if (Build.VERSION.SDK_INT >= 28) runCatching { dpm.setStatusBarDisabled(admin, false) }.logFail("status bar")
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }.logFail("lock task packages")
    }

    fun launchLockScreen() {
        runCatching {
            context.startActivity(Intent(context, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.logFail("launch lock screen")
    }

    /** Authorized release: remove every restriction and give up device ownership. */
    fun release() {
        unrestrict()
        if (isDeviceOwner) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, false) }.logFail("uninstall unblock")
            if (Build.VERSION.SDK_INT >= 30) runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
            ALL_RESTRICTIONS.forEach { r -> runCatching { dpm.clearUserRestriction(admin, r) } }
            runCatching { dpm.clearDeviceOwnerApp(pkg) }.logFail("clear device owner")
        }
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll() }
        prefs.clearAll()
    }

    private fun dialerPackage(): String? =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName

    private fun addRestriction(r: String) {
        runCatching { dpm.addUserRestriction(admin, r) }.logFail("restriction $r")
    }

    private fun Result<*>.logFail(what: String) {
        exceptionOrNull()?.let { Log.w(TAG, "Policy step failed: $what", it) }
    }

    companion object {
        private const val TAG = "DevicePolicy"
        private val ALL_RESTRICTIONS = listOf(
            UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_DEBUGGING_FEATURES, UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_NETWORK_RESET, UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_APPS_CONTROL,
        )
    }
}
