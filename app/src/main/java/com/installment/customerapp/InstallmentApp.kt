package com.installment.customerapp

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.ui.Notifier
import com.installment.customerapp.work.Scheduler

class InstallmentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        initFirebase()
        if (Prefs.get(this).isEnrolled) Scheduler.schedulePeriodic(this)
    }

    /**
     * Firebase is configured from build-time values (CI secrets) instead of a google-services.json committed to git.
     * Without them push is skipped and the app relies on its 15-minute background sync.
     */
    private fun initFirebase() {
        if (BuildConfig.FCM_PROJECT_ID.isBlank() || BuildConfig.FCM_APP_ID.isBlank() || BuildConfig.FCM_API_KEY.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                val prefs = Prefs.get(this)
                if (prefs.fcmToken != token) {
                    prefs.fcmToken = token
                    if (prefs.isEnrolled) Scheduler.syncSoon(this)
                }
            }
        }.onFailure { Log.w("InstallmentApp", "Firebase init failed", it) }
    }
}
