package com.installment.customerapp.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Time source for offline enforcement. Changing the phone's clock must not postpone a restriction:
 *  - Within the same boot as the last server sync: server time + monotonic elapsed time (immune to clock edits).
 *  - After a reboot: never earlier than the latest time this app has already seen.
 * Date/time settings are also blocked by device-owner policy.
 */
object TrustedClock {
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun anchor(context: Context, prefs: Prefs, serverMs: Long) {
        prefs.serverAnchorMs = serverMs
        prefs.elapsedAnchorMs = SystemClock.elapsedRealtime()
        prefs.bootCountAnchor = bootCount(context)
        if (serverMs > prefs.lastTrustedMs) prefs.lastTrustedMs = serverMs
    }

    fun now(context: Context, prefs: Prefs): Long {
        val wall = System.currentTimeMillis()
        val sameBoot = prefs.serverAnchorMs > 0 &&
            prefs.bootCountAnchor != -1 && prefs.bootCountAnchor == bootCount(context)
        val estimate = if (sameBoot) {
            prefs.serverAnchorMs + (SystemClock.elapsedRealtime() - prefs.elapsedAnchorMs)
        } else {
            wall
        }
        // Time only moves forward as far as this app is concerned.
        val result = maxOf(estimate, prefs.lastTrustedMs)
        if (result > prefs.lastTrustedMs) prefs.lastTrustedMs = result
        return result
    }
}
