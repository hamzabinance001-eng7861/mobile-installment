package com.installment.customerapp.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.data.TrustedClock

/**
 * Offline enforcement. Runs from WorkManager (every 15 min), a wake-up alarm at the deadline, device boot and
 * every time the app comes to the foreground; none of these need internet.
 *
 * Rule: once the trusted clock passes the deadline delivered by the last sync, the phone restricts itself.
 * The restriction is latched in [Prefs.restricted] and is cleared only by an admin Unlock command or release.
 */
object Enforcer {

    /** @return true when the phone is (now) restricted. */
    fun evaluate(context: Context): Boolean {
        val prefs = Prefs.get(context)
        val policy = DevicePolicy(context)
        if (!prefs.isEnrolled || !policy.isDeviceOwner) return false

        if (prefs.restricted) {
            policy.restrict(prefs.restrictedReason ?: "Restricted") // re-assert lock screen after reboot/crash
            return true
        }
        val deadline = prefs.restrictAfterMs
        if (prefs.policyEnabled && deadline > 0 && TrustedClock.now(context, prefs) >= deadline) {
            policy.restrict("Installment unpaid past its due date")
            return true
        }
        scheduleAlarm(context)
        return false
    }

    /** Wakes the app at the deadline even if nothing else runs (idle device, no connectivity). */
    fun scheduleAlarm(context: Context) {
        val prefs = Prefs.get(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 7, Intent(context, EnforcementAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (!prefs.policyEnabled || prefs.restrictAfterMs <= 0 || prefs.restricted) {
            am.cancel(pi)
            return
        }
        val msUntil = prefs.restrictAfterMs - TrustedClock.now(context, prefs)
        val at = System.currentTimeMillis() + maxOf(msUntil, 1_000L)
        // Inexact-while-idle: needs no special permission; WorkManager's 15-minute check is the backstop.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class EnforcementAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Enforcer.evaluate(context)
    }
}
