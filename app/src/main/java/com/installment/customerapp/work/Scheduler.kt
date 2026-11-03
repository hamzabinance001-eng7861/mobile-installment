package com.installment.customerapp.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.ApiClient
import com.installment.customerapp.data.ApiException
import com.installment.customerapp.data.Prefs
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "periodic-sync"
    private const val SOON = "sync-soon"
    private const val ENROLL = "enroll"

    /** Runs every 15 minutes with NO network constraint so offline enforcement keeps being evaluated. */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Sync as soon as there is connectivity (used after boot, FCM pings and failed attempts). */
    fun syncSoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(androidx.work.workDataOf(SyncWorker.KEY_RETRY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, req)
    }

    fun enqueueEnrollment(context: Context) {
        val req = OneTimeWorkRequestBuilder<EnrollWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ENROLL, ExistingWorkPolicy.REPLACE, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 1) Offline enforcement first: needs no connectivity.
        Enforcer.evaluate(applicationContext)
        // 2) Then try to talk to the server.
        return when (SyncManager.sync(applicationContext)) {
            is SyncManager.Outcome.Failed -> if (inputData.getBoolean(KEY_RETRY, false)) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_RETRY = "retry"
    }
}

/** Exchanges the one-time enrollment code for a device token. Retries while offline. */
class EnrollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (prefs.isEnrolled) return Result.success()
        val url = prefs.apiUrl
        val code = prefs.pendingCode
        if (url.isNullOrBlank() || code.isNullOrBlank()) return Result.failure()

        val policy = DevicePolicy(applicationContext)
        return try {
            val fcm = prefs.fcmToken ?: tryFcmToken()
            prefs.deviceToken = ApiClient(url).register(code, fcm, policy.isDeviceOwner)
            prefs.pendingCode = null
            prefs.enrollError = null
            policy.applyBaseline()
            Scheduler.schedulePeriodic(applicationContext)
            SyncManager.sync(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            // 4xx: wrong/expired/used code. Retrying cannot help.
            prefs.enrollError = e.message
            if (e.code in 400..499) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.i("EnrollWorker", "Enrollment will retry: ${e.message}")
            Result.retry()
        }
    }

    private fun tryFcmToken(): String? = runCatching {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) null
        else com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
    }.getOrNull()
}

/** Re-arms everything after a reboot or app update and re-asserts the restricted screen if needed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs.get(context)
        if (prefs.isEnrolled) {
            val policy = DevicePolicy(context)
            policy.applyBaseline()
            Scheduler.schedulePeriodic(context)
            Enforcer.evaluate(context)
            Scheduler.syncSoon(context)
        } else if (!prefs.pendingCode.isNullOrBlank()) {
            Scheduler.enqueueEnrollment(context)
        }
    }
}
