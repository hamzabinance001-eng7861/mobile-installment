package com.installment.customerapp.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class Summary(
    val shopName: String,
    val shopPhone: String,
    val shopAddress: String,
    val currency: String,
    val customerName: String,
    val deviceModel: String,
    val hasContract: Boolean,
    val contractStatus: String,
    val totalPrice: Double,
    val amountPaid: Double,
    val remainingBalance: Double,
    val nextDueDate: String?,
    val nextDueAmount: Double?,
    val overdueAmount: Double,
    val overdueDays: Int,
    val graceDays: Int,
) {
    companion object {
        fun from(o: JSONObject) = Summary(
            shopName = o.optString("shopName"),
            shopPhone = o.optString("shopPhone"),
            shopAddress = o.optString("shopAddress"),
            currency = o.optString("currency", ""),
            customerName = o.optString("customerName"),
            deviceModel = o.optString("deviceModel"),
            hasContract = o.optBoolean("hasContract"),
            contractStatus = o.optString("contractStatus"),
            totalPrice = o.optDouble("totalPrice", 0.0),
            amountPaid = o.optDouble("amountPaid", 0.0),
            remainingBalance = o.optDouble("remainingBalance", 0.0),
            nextDueDate = if (o.isNull("nextDueDate")) null else o.optString("nextDueDate"),
            nextDueAmount = if (o.isNull("nextDueAmount")) null else o.optDouble("nextDueAmount"),
            overdueAmount = o.optDouble("overdueAmount", 0.0),
            overdueDays = o.optInt("overdueDays", 0),
            graceDays = o.optInt("gracePeriodDays", 0),
        )
    }
}

data class Policy(
    val enabled: Boolean,
    /** Server time (epoch ms) after which the phone restricts itself; null when nothing applies. */
    val restrictAfterMs: Long?,
    val message: String,
) {
    companion object {
        fun from(o: JSONObject) = Policy(
            enabled = o.optBoolean("autoRestrictEnabled"),
            restrictAfterMs = if (o.isNull("restrictAfterUtc")) null else parseUtc(o.getString("restrictAfterUtc")),
            message = o.optString("message"),
        )
    }
}

data class ServerCommand(val id: Int, val type: String, val reason: String?) {
    companion object {
        fun list(a: JSONArray): List<ServerCommand> = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ServerCommand(o.getInt("id"), o.getString("type"), if (o.isNull("reason")) null else o.optString("reason"))
        }
    }
}

data class SyncResult(val summary: Summary, val summaryRaw: String, val policy: Policy, val serverTimeMs: Long, val commands: List<ServerCommand>)

/** ASP.NET writes UTC instants with or without a trailing Z; treat both as UTC. */
fun parseUtc(s: String): Long {
    val t = if (s.endsWith("Z") || s.contains("+")) s else "${s}Z"
    return Instant.parse(t).toEpochMilli()
}
