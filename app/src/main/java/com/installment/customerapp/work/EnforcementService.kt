package com.installment.customerapp.work

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.ui.LockActivity
import com.installment.customerapp.ui.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Small always-on foreground service (shows the "this phone is managed" notice). Some brands (Xiaomi, Oppo, Realme,
 * Vivo, Infinix/Tecno) stop background jobs aggressively; a foreground service is the most reliable way to keep the
 * offline deadline check and the restricted screen alive. Runs only on enrolled Device Owner phones.
 */
class EnforcementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        val shop = Prefs.get(this).summaryJson?.let { runCatching { org.json.JSONObject(it).optString("shopName") }.getOrNull() }
        val notification = Notifier.buildManaged(this, shop)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_MANAGED, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(Notifier.ID_MANAGED, notification)
        }
        running = true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Enforcer.evaluate(context)
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            var tick = 0
            while (isActive) {
                tick(tick++)
                delay(30_000)
            }
        }
    }

    private var failures = 0

    private fun tick(n: Int) {
        val prefs = Prefs.get(this)
        if (!prefs.isEnrolled) { stopSelf(); return }
        val restricted = Enforcer.evaluate(this)
        // If the system closed the restricted screen (or it never opened after boot), bring it back.
        if (restricted && !LockActivity.visible) DevicePolicy(this).launchLockScreen()

        // Poll the server about once a minute while it is reachable, so an admin Lock/Restore from the panel is applied
        // within about a minute even without a push message. Back off when the phone is offline to save battery.
        val every = 2 * (1 + minOf(failures, 4)) // ticks of 30 s: 1 min, then up to 5 min
        if (n % every == 0) {
            when (SyncManager.sync(this)) {
                is SyncManager.Outcome.Failed -> failures++
                else -> failures = 0
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        scope.cancel()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false

        /** Safe to call from anywhere; does nothing unless the phone is enrolled as Device Owner. */
        fun start(context: Context) {
            if (running) return
            val app = context.applicationContext
            if (!Prefs.get(app).isEnrolled || !DevicePolicy(app).isDeviceOwner) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, EnforcementService::class.java))
            } catch (e: Exception) {
                // Background-start restrictions can refuse this; it is retried from the next foreground entry point.
                Log.w("EnforcementService", "Could not start foreground service", e)
            }
        }
    }
}
