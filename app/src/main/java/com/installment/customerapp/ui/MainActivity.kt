package com.installment.customerapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
        applyInsets()

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.enrollButton).setOnClickListener { enrollManually() }
        findViewById<Button>(R.id.setupCheckButton).setOnClickListener {
            startActivity(Intent(this, SetupCheckActivity::class.java))
        }
    }

    /** Edge-to-edge: keep the gradient header behind the status bar but push its content below it. */
    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val baseTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, baseTop + bars.top, header.paddingRight, header.paddingBottom)
            v.setPadding(0, 0, 0, bars.bottom)
            insets
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
        val s = SyncManager.summaryOrNull(prefs)
        findViewById<TextView>(R.id.shopName).text = s?.shopName?.ifBlank { null } ?: getString(R.string.app_name)
        findViewById<TextView>(R.id.shopContact).apply {
            val contact = s?.let { listOf(it.shopPhone, it.shopAddress).filter(String::isNotBlank).joinToString("  •  ") }.orEmpty()
            text = contact
            visibility = if (contact.isEmpty()) View.GONE else View.VISIBLE
        }

        val isOwner = policy.isDeviceOwner
        findViewById<TextView>(R.id.managedBanner).text =
            getString(R.string.managed_text, s?.shopName?.ifBlank { null } ?: "the shop")
        findViewById<View>(R.id.managedCard).visibility = if (isOwner) View.VISIBLE else View.GONE
        findViewById<View>(R.id.notManagedCard).visibility = if (isOwner) View.GONE else View.VISIBLE

        val showEnroll = !prefs.isEnrolled || prefs.needsReenroll
        findViewById<View>(R.id.enrollSection).visibility = if (showEnroll) View.VISIBLE else View.GONE
        findViewById<View>(R.id.summarySection).visibility = if (prefs.isEnrolled && s != null) View.VISIBLE else View.GONE
        if (showEnroll) {
            findViewById<TextView>(R.id.enrollStatus).text = when {
                prefs.needsReenroll && prefs.pendingCode.isNullOrBlank() ->
                    "The shop's server does not recognise this phone. It stays managed. Ask the shop for a new enrollment code and enter it below."
                prefs.enrollError != null -> "Enrollment failed: ${prefs.enrollError}. Ask the shop for a new code."
                !prefs.pendingCode.isNullOrBlank() -> "Enrolling... this continues automatically when the phone is online."
                else -> "This phone is not connected to the shop yet. Enter the details the shop gave you."
            }
        }
        if (s != null) renderSummary(s)

        val last = prefs.lastSyncMs
        findViewById<TextView>(R.id.syncLine).text = when {
            prefs.lastSyncError != null && last > 0 -> "Last updated ${fmt(last)}\nLatest attempt failed: ${prefs.lastSyncError}"
            last > 0 -> "Last updated ${fmt(last)}"
            else -> ""
        }
    }

    private fun renderSummary(s: Summary) {
        val n = NumberFormat.getIntegerInstance()
        fun money(v: Double) = "${n.format(v)} ${s.currency}".trim()

        findViewById<TextView>(R.id.deviceLine).apply {
            val line = listOf(s.customerName, s.deviceModel).filter { it.isNotBlank() }.joinToString("  •  ")
            text = line
            visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        }

        findViewById<TextView>(R.id.remaining).text = if (s.hasContract) money(s.remainingBalance) else "-"

        // Paid progress
        val progress = findViewById<LinearProgressIndicator>(R.id.paidProgress)
        val paidText = findViewById<TextView>(R.id.paidText)
        if (s.hasContract && s.totalPrice > 0) {
            val pct = (s.amountPaid / s.totalPrice * 100).coerceIn(0.0, 100.0)
            progress.visibility = View.VISIBLE
            progress.setProgressCompat(pct.toInt(), true)
            paidText.text = "Paid ${money(s.amountPaid)} of ${money(s.totalPrice)}  •  ${pct.toInt()}%"
        } else {
            progress.visibility = View.GONE
            paidText.text = if (s.hasContract) "" else "No installment plan is linked to this phone."
        }

        // Next installment
        val amountView = findViewById<TextView>(R.id.nextDueAmount)
        val dateView = findViewById<TextView>(R.id.nextDueDate)
        val inView = findViewById<TextView>(R.id.nextDueIn)
        val due = s.nextDueDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        if (s.hasContract && due != null) {
            amountView.text = money(s.nextDueAmount ?: 0.0)
            dateView.text = due.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
            val days = ChronoUnit.DAYS.between(LocalDate.now(), due)
            inView.visibility = View.VISIBLE
            inView.text = when {
                days > 1 -> "in $days days"
                days == 1L -> "tomorrow"
                days == 0L -> "today"
                else -> "${-days} days ago"
            }
        } else {
            amountView.text = if (s.hasContract) "All paid" else "-"
            dateView.text = if (s.hasContract) "Nothing is due right now" else ""
            inView.visibility = View.GONE
        }

        // Plan details
        findViewById<TextView>(R.id.details).text = if (!s.hasContract) "-" else buildString {
            appendLine("Phone price:  ${money(s.totalPrice)}")
            appendLine("Amount paid:  ${money(s.amountPaid)}")
            appendLine("Status:  ${s.contractStatus}")
            append("Grace period:  ${s.graceDays} days")
        }

        renderSchedule(s, ::money)

        // One-tap call to the shop
        findViewById<Button>(R.id.callShopMain).apply {
            if (s.shopPhone.isNotBlank()) {
                visibility = View.VISIBLE
                text = "Call ${s.shopName.ifBlank { "the shop" }}"
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", s.shopPhone, null))) }
                }
            } else visibility = View.GONE
        }

        // Overdue
        val overdueCard = findViewById<View>(R.id.overdueCard)
        if (s.overdueAmount > 0) {
            overdueCard.visibility = View.VISIBLE
            findViewById<TextView>(R.id.overdueBox).text =
                "${money(s.overdueAmount)} is overdue (${s.overdueDays} days past the grace period).\n" +
                    "Please pay ${s.shopName}${if (s.shopPhone.isNotBlank()) " on ${s.shopPhone}" else ""} to avoid the phone being restricted."
        } else overdueCard.visibility = View.GONE

        findViewById<TextView>(R.id.policyLine).apply {
            val msg = prefs.policyMessage.orEmpty()
            text = msg
            visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
        }
    }

    /** Timeline of every installment: coloured dot, date, what is left, and its status. */
    private fun renderSchedule(s: Summary, money: (Double) -> String) {
        val card = findViewById<View>(R.id.scheduleCard)
        val list = findViewById<android.widget.LinearLayout>(R.id.scheduleList)
        list.removeAllViews()
        if (!s.hasContract || s.schedule.isEmpty()) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE

        val fmtDate = DateTimeFormatter.ofPattern("d MMM yyyy")
        // Show every unpaid installment plus the most recent paid ones, so long plans stay short.
        val firstOpen = s.schedule.indexOfFirst { it.status != "Paid" }.let { if (it < 0) s.schedule.size else it }
        val visible = s.schedule.filterIndexed { i, _ -> i >= firstOpen - 2 }
        val hidden = s.schedule.size - visible.size
        if (hidden > 0) list.addView(plainText("$hidden earlier installment${if (hidden == 1) "" else "s"} paid", 12f, R.color.muted))

        for (item in visible) {
            val (dot, fg, soft) = when (item.status) {
                "Paid" -> Triple(R.color.success, R.color.success, R.color.success_soft)
                "Overdue" -> Triple(R.color.danger, R.color.danger, R.color.danger_soft)
                "Partial" -> Triple(R.color.warn, R.color.warn, R.color.warn_soft)
                else -> Triple(R.color.muted, R.color.brand_dark, R.color.info_soft)
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(getColor(dot))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(10), dp(10)).apply { setMargins(0, 0, dp(12), 0) }
            })
            val middle = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val date = runCatching { LocalDate.parse(item.dueDate.take(10)).format(fmtDate) }.getOrDefault(item.dueDate.take(10))
            middle.addView(plainText("Installment ${item.number}", 15f, R.color.ink, bold = true))
            middle.addView(plainText(date, 13f, R.color.muted))
            row.addView(middle)

            val right = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            val left = (item.amount - item.paid).coerceAtLeast(0.0)
            right.addView(plainText(if (item.status == "Paid") money(item.amount) else money(left), 15f, R.color.ink, bold = true, end = true))
            right.addView(android.widget.TextView(this).apply {
                text = item.status
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(fg))
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(getColor(soft)); cornerRadius = dp(999).toFloat() }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, 0) }
            })
            row.addView(right)
            list.addView(row)
        }
    }

    private fun plainText(t: String, size: Float, colorRes: Int, bold: Boolean = false, end: Boolean = false) =
        android.widget.TextView(this).apply {
            text = t
            textSize = size
            setTextColor(getColor(colorRes))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (end) gravity = android.view.Gravity.END
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
