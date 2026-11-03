package com.installment.customerapp.data

import android.os.Build
import com.installment.customerapp.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Server answered with an error status. 401 means the device token was revoked (device released). */
class ApiException(val code: Int, message: String) : IOException(message)

/** Plain HttpURLConnection client: no third-party networking dependency, HTTPS enforced by network config. */
class ApiClient(private val baseUrl: String, private val token: String? = null) {

    fun register(code: String, fcmToken: String?, isDeviceOwner: Boolean): String {
        val body = JSONObject()
            .put("enrollmentCode", code)
            .put("fcmToken", fcmToken ?: JSONObject.NULL)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("isDeviceOwner", isDeviceOwner)
        val res = JSONObject(call("POST", "/api/device/register", body.toString()))
        return res.getString("deviceToken")
    }

    fun sync(fcmToken: String?, restrictionActive: Boolean, isDeviceOwner: Boolean): SyncResult {
        val body = JSONObject()
            .put("fcmToken", fcmToken ?: JSONObject.NULL)
            .put("restrictionActive", restrictionActive)
            .put("isDeviceOwner", isDeviceOwner)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("androidVersion", Build.VERSION.RELEASE)
        val o = JSONObject(call("POST", "/api/device/sync", body.toString()))
        val summaryObj = o.getJSONObject("summary")
        return SyncResult(
            summary = Summary.from(summaryObj),
            summaryRaw = summaryObj.toString(),
            policy = Policy.from(o.getJSONObject("policy")),
            serverTimeMs = parseUtc(o.getString("serverTime")),
            commands = ServerCommand.list(o.getJSONArray("commands")),
        )
    }

    fun reportResult(commandId: Int, success: Boolean, error: String?) {
        val body = JSONObject().put("success", success).put("error", error ?: JSONObject.NULL)
        call("POST", "/api/device/commands/$commandId/result", body.toString())
    }

    private fun call(method: String, path: String, json: String?): String {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val title = runCatching { JSONObject(text).optString("title") }.getOrNull().orEmpty()
                throw ApiException(code, title.ifEmpty { "HTTP $code" })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
