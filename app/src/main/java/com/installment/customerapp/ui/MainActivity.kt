package com.installment.customerapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.installment.customerapp.R
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.data.Summary
import com.installment.customerapp.work.EnforcementService
import com.installment.customerapp.work.Enforcer
import com.installment.customerapp.work.Scheduler
import com.installment.customerapp.work.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** The only screen a customer sees: shop info, what is paid, what is left, what is due. No account, no login. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var policy: DevicePolicy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        policy = DevicePolicy(this)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.enrollButton).setOnClickListener { enrollManually() }
        findViewById<Button>(R.id.setupCheckButton).setOnClickListener {
            startActivity(Intent(this, SetupCheckActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        policy.ensureHomeNotHijacked()
        if (Enforcer.evaluate(this) || prefs.restricted) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        render()
        if (prefs.isEnrolled) {
            EnforcementService.start(this) // foreground entry point: allowed even where background starts are refused
            refresh(silent = true)
        }
    }

    private fun enrollManually() {
        val url = findViewById<EditText>(R.id.apiUrlInput).text.toString().trim()
        val code = findViewById<EditText>(R.id.codeInput).text.toString().trim().uppercase()
        if (url.isEmpty() || code.isEmpty()) {
            findViewById<TextView>(R.id.enrollStatus).text = "Enter the server address and the enrollment code."
            return
        }
        prefs.apiUrl = url
        prefs.pendingCode = code
        prefs.enrollError = null
        Scheduler.enqueueEnrollment(this)
        findViewById<TextView>(R.id.enrollStatus).text = "Enrolling..."
    }

    private fun refresh(silent: Boolean = false) {
        val status = findViewById<TextView>(R.id.syncLine)
        if (!silent) status.text = "Refreshing..."
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { SyncManager.sync(applicationContext) }
            if (outcome is SyncManager.Outcome.Failed && !silent) status.text = "Could not reach the server: ${outcome.message}"
            if (prefs.restricted) {
                startActivity(Intent(this@MainActivity, LockActivity::class.java))
                finish()
            } else render()
        }
    }

    private fun render() {
        val shopName = findViewById<TextView>(R.id.shopName)
        val shopContact = findViewById<TextView>(R.id.shopContact)
        val managed = findViewById<TextView>(R.id.managedBanner)
        val notManaged = findViewById<View>(R.id.notManagedBanner)
        val enroll = findViewById<View>(R.id.enrollSection)
        val summarySection = findViewById<View>(R.id.summarySection)

        val s = SyncManager.summaryOrNull(prefs)
        shopName.text = s?.shopName ?: getString(R.string.app_name)
        shopContact.text = s?.let { listOf(it.shopPhone, it.shopAddress).filter(String::isNotBlank).joinToString("  |  ") }.orEmpty()
        managed.text = getString(R.string.managed_text, s?.shopName?.ifBlank { null } ?: "the shop")
        managed.visibility = if (policy.isDeviceOwner) View.VISIBLE else View.GONE
        notManaged.visibility = if (policy.isDeviceOwner) View.GONE else View.VISIBLE

        val showEnroll = !prefs.isEnrolled || prefs.needsReenroll
        enroll.visibility = if (showEnroll) View.VISIBLE else View.GONE
        summarySection.visibility = if (prefs.isEnrolled && s != null) View.VISIBLE else View.GONE
        if (showEnroll) {
            findViewById<TextView>(R.id.enrollStatus).text = when {
                prefs.needsReenroll && prefs.pendingCode.isNullOrBlank() ->
                    "The shop's server does not recognise this phone. It stays managed. Ask the shop for a new enrollment code and enter it below."
                prefs.enrollError != null -> "Enrollment failed: ${prefs.enrollError}. Ask the shop for a new code."
                !prefs.pendingCode.isNullOrBlank() -> "Enrolling... this continues automatically when the phone is online."
                else -> "This phone is not enrolled yet."
            }
        }
        if (s != null) renderSummary(s)

        val last = prefs.lastSyncMs
        findViewById<TextView>(R.id.syncLine).text = when {
            prefs.lastSyncError != null && last > 0 -> "Last synchronised ${fmt(last)} (latest attempt failed: ${prefs.lastSyncError})"
            last > 0 -> "Last synchronised ${fmt(last)}"
            else -> ""
        }
    }

    private fun renderSummary(s: Summary) {
        val n = NumberFormat.getIntegerInstance()
        fun money(v: Double) = "${n.format(v)} ${s.currency}"
        findViewById<TextView>(R.id.deviceLine).text = "${s.customerName} - ${s.deviceModel}"
        findViewById<TextView>(R.id.remaining).text = if (s.hasContract) money(s.remainingBalance) else "-"
        findViewById<TextView>(R.id.details).text = if (!s.hasContract) "No installment plan is linked to this phone." else buildString {
            appendLine("Phone price: ${money(s.totalPrice)}")
            appendLine("Amount paid: ${money(s.amountPaid)}")
            appendLine("Plan status: ${s.contractStatus}")
            if (s.nextDueDate != null) append("Next installment: ${money(s.nextDueAmount ?: 0.0)} on ${s.nextDueDate.take(10)}")
            else append("No installment is due.")
        }
        val overdue = findViewById<TextView>(R.id.overdueBox)
        if (s.overdueAmount > 0) {
            overdue.visibility = View.VISIBLE
            overdue.text = "Overdue: ${money(s.overdueAmount)} (${s.overdueDays} days past the grace period of ${s.graceDays} days). " +
                "Please pay ${s.shopName} on ${s.shopPhone} to avoid the phone being restricted."
        } else overdue.visibility = View.GONE
        findViewById<TextView>(R.id.policyLine).text = prefs.policyMessage.orEmpty()
    }

    private fun fmt(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
