package com.installment.customerapp.work

import android.content.Context
import android.util.Log
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.ApiClient
import com.installment.customerapp.data.ApiException
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.data.ServerCommand
import com.installment.customerapp.data.Summary
import com.installment.customerapp.data.TrustedClock
import com.installment.customerapp.ui.Notifier
import org.json.JSONObject
import java.text.NumberFormat

object SyncManager {
    private const val TAG = "SyncManager"

    sealed interface Outcome {
        data object Ok : Outcome
        data object NotEnrolled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Pushes device state to the server, applies the returned policy and executes waiting commands. */
    @Synchronized
    fun sync(context: Context): Outcome {
        val prefs = Prefs.get(context)
        val url = prefs.apiUrl
        val token = prefs.deviceToken
        if (url.isNullOrBlank() || token.isNullOrBlank()) return Outcome.NotEnrolled
        val policy = DevicePolicy(context)
        val client = ApiClient(url, token)

        return try {
            val result = client.sync(prefs.fcmToken, prefs.restricted, policy.isDeviceOwner)
            TrustedClock.anchor(context, prefs, result.serverTimeMs)
            prefs.summaryJson = result.summaryRaw
            prefs.policyEnabled = result.policy.enabled
            prefs.restrictAfterMs = result.policy.restrictAfterMs ?: 0L
            prefs.policyMessage = result.policy.message
            prefs.lastSyncMs = System.currentTimeMillis()
            prefs.lastSyncError = null

            Notifier.showManaged(context, result.summary.shopName)
            result.commands.forEach { execute(context, client, policy, prefs, it, result.summary) }
            Enforcer.evaluate(context) // deadline may already have passed
            Enforcer.scheduleAlarm(context)
            Outcome.Ok
        } catch (e: ApiException) {
            if (e.code == 401) {
                // Token revoked: the shop released this device. Finish the release locally.
                Log.i(TAG, "Device token revoked; completing release")
                policy.release()
                return Outcome.NotEnrolled
            }
            prefs.lastSyncError = e.message
            Outcome.Failed(e.message ?: "Server error")
        } catch (e: Exception) {
            prefs.lastSyncError = e.message
            Outcome.Failed(e.message ?: "No connection")
        }
    }

    private fun execute(context: Context, client: ApiClient, policy: DevicePolicy, prefs: Prefs,
                        cmd: ServerCommand, summary: Summary) {
        try {
            when (cmd.type) {
                "Lock" -> {
                    if (!policy.isDeviceOwner) throw IllegalStateException("Not a device owner")
                    policy.restrict(cmd.reason ?: "Restricted by the shop")
                }
                "Unlock" -> policy.unrestrict()
                "Reminder" -> Notifier.showAlert(context, 100 + cmd.id, "Installment reminder", reminderText(summary))
                "SyncStatus" -> Unit
                "Release" -> {
                    // Tell the server first; it revokes the token. The local release completes on the next call
                    // (or right below) so a lost network never leaves the server and phone disagreeing.
                    client.reportResult(cmd.id, true, null)
                    policy.release()
                    return
                }
                else -> throw IllegalArgumentException("Unknown command ${cmd.type}")
            }
            client.reportResult(cmd.id, true, null)
        } catch (e: ApiException) {
            Log.w(TAG, "Could not report result for ${cmd.id}", e) // stays Delivered; retried on next sync
        } catch (e: Exception) {
            Log.w(TAG, "Command ${cmd.id} failed", e)
            runCatching { client.reportResult(cmd.id, false, e.message?.take(200)) }
        }
    }

    fun reminderText(s: Summary): String {
        val fmt = NumberFormat.getIntegerInstance()
        return when {
            s.overdueAmount > 0 -> "${fmt.format(s.overdueAmount)} ${s.currency} is overdue. Please pay ${s.shopName} ${s.shopPhone}."
            s.nextDueDate != null -> "${fmt.format(s.nextDueAmount ?: 0.0)} ${s.currency} is due on ${s.nextDueDate.take(10)}."
            else -> "Your installments are up to date."
        }
    }

    fun summaryOrNull(prefs: Prefs): Summary? =
        prefs.summaryJson?.let { runCatching { Summary.from(JSONObject(it)) }.getOrNull() }
}
