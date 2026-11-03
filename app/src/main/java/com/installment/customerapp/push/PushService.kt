package com.installment.customerapp.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.ui.Notifier
import com.installment.customerapp.work.Scheduler

/**
 * FCM only nudges the app. The payload carries no secrets and never changes device state by itself:
 * a "command" ping triggers an authenticated sync that fetches the real command from the backend.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val prefs = Prefs.get(this)
        prefs.fcmToken = token
        if (prefs.isEnrolled) Scheduler.syncSoon(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val prefs = Prefs.get(this)
        if (!prefs.isEnrolled) return
        val data = message.data
        if (data["type"] == "notification") {
            val title = data["title"].orEmpty()
            val body = data["body"].orEmpty()
            if (title.isNotBlank()) Notifier.showAlert(this, (System.currentTimeMillis() % 100000).toInt() + 1000, title, body)
        }
        Scheduler.syncSoon(this)
    }
}
