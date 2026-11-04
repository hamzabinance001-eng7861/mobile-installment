package com.installment.customerapp.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.installment.customerapp.R

object Notifier {
    private const val CH_MANAGED = "managed"
    private const val CH_ALERTS = "alerts"
    const val ID_MANAGED = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MANAGED, context.getString(R.string.channel_managed), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Persistent notice explaining why the phone is managed. */
    fun showManaged(context: Context, shopName: String?) {
        if (!canPost(context)) return
        context.getSystemService(NotificationManager::class.java).notify(ID_MANAGED, buildManaged(context, shopName))
    }

    /** Also used as the foreground-service notification. */
    fun buildManaged(context: Context, shopName: String?): Notification {
        val shop = shopName?.takeIf { it.isNotBlank() } ?: "the shop"
        val n = NotificationCompat.Builder(context, CH_MANAGED)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.managed_title))
            .setContentText(context.getString(R.string.managed_text, shop))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.managed_text, shop)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        n.flags = n.flags or Notification.FLAG_NO_CLEAR
        return n
    }

    fun showAlert(context: Context, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
