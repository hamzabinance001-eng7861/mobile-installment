package com.installment.customerapp.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.installment.customerapp.R
import com.installment.customerapp.admin.DevicePolicy
import com.installment.customerapp.data.Prefs
import com.installment.customerapp.work.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * Restricted-mode screen (lock-task kiosk). It explains why the phone is restricted and offers only:
 * call the shop, make an emergency call, and re-check payment status. It closes itself only when the shop
 * restores the phone from the admin panel.
 */
class LockActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var policy: DevicePolicy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        prefs = Prefs.get(this)
        policy = DevicePolicy(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* blocked while restricted */ }
        })

        val shopPhone = SyncManager.summaryOrNull(prefs)?.shopPhone.orEmpty()
        findViewById<Button>(R.id.callShop).apply {
            isEnabled = shopPhone.isNotBlank()
            setOnClickListener { dial(shopPhone) }
        }
        // Opens the dialer with no number so the user can dial the local emergency number.
        findViewById<Button>(R.id.emergencyCall).setOnClickListener { dial("") }
        findViewById<Button>(R.id.checkStatus).setOnClickListener { check() }
    }

    override fun onStart() {
        super.onStart()
        if (policy.isDeviceOwner && prefs.restricted) {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isLockTaskPermitted(packageName)) runCatching { startLockTask() }
        }
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (!prefs.restricted) { leave(); return }
        val s = SyncManager.summaryOrNull(prefs)
        val n = NumberFormat.getIntegerInstance()
        val msg = StringBuilder()
        msg.append(prefs.restrictedReason ?: "This phone has been restricted.").append("\n\n")
        if (s != null) msg.append("Please contact ${s.shopName}${if (s.shopPhone.isNotBlank()) " on ${s.shopPhone}" else ""}.\n")
        msg.append("The phone is restored by the shop after payment is confirmed.")
        findViewById<TextView>(R.id.lockMessage).text = msg.toString()
        findViewById<TextView>(R.id.lockAmount).apply {
            if (s != null && s.overdueAmount > 0) {
                text = "Overdue: ${n.format(s.overdueAmount)} ${s.currency}".trim()
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }
    }

    private fun check() {
        val status = findViewById<TextView>(R.id.lockStatus)
        status.text = "Checking..."
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { SyncManager.sync(applicationContext) }
            status.text = when {
                outcome is SyncManager.Outcome.Failed -> "No connection to the shop's server. The phone stays restricted until the shop restores it."
                prefs.restricted -> "Still restricted. The shop must restore this phone after payment."
                else -> ""
            }
            if (!prefs.restricted) leave()
        }
    }

    /** Called only when the admin has restored the phone. */
    private fun leave() {
        runCatching { stopLockTask() }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun dial(number: String) {
        val uri = if (number.isBlank()) Uri.parse("tel:") else Uri.fromParts("tel", number, null)
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) }
    }

    companion object {
        /** True while the restricted screen is in front; lets the background service re-open it if not. */
        @Volatile var visible = false
    }
}
