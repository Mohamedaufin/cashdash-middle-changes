@file:Suppress("DEPRECATION")
package com.cash.dash

import android.app.Service
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import android.content.ComponentName
import android.text.TextUtils

class FloatingWidgetService : Service() {

    companion object {
        const val ACTION_SHOW_WIDGET = "ACTION_SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "ACTION_HIDE_WIDGET"
        const val EXTRA_APP_NAME = "EXTRA_APP_NAME"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var trackerView: View? = null

    private var currentAppName: String = "Expense"
    private var isWidgetShowing = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private fun getThemedContext(): Context {
        // Force the widget to ALWAYS use the dark theme (Theme.Cashdash)
        // regardless of the user's base app settings.
        return androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_Cashdash)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_SHOW_WIDGET) {
            currentAppName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Expense"
            if (!isWidgetShowing) {
                showBubble()
            } else if (trackerView != null) {
                // Update title if tracker is already open
                trackerView?.findViewById<EditText>(R.id.edtTitle)?.setText(currentAppName)
            }
        } else if (action == ACTION_HIDE_WIDGET) {
            hideAll()
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return

        hideAll()

        val themedContext = getThemedContext()
        val inflater = LayoutInflater.from(themedContext)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            showTracker()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, params)
        isWidgetShowing = true
    }

    private fun showTracker() {
        if (!Settings.canDrawOverlays(this)) return

        hideAll()

        val themedContext = getThemedContext()
        val inflater = LayoutInflater.from(themedContext)
        trackerView = inflater.inflate(R.layout.layout_floating_tracker, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        trackerView?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            showBubble()
        }

        val switchTracking = trackerView?.findViewById<SwitchCompat>(R.id.switchTracking)
        val trackerBody = trackerView?.findViewById<LinearLayout>(R.id.trackerBody)

        val prefs = getSharedPreferences("SmartAssistantPrefs", Context.MODE_PRIVATE)
        val isTrackingOn = prefs.getBoolean("tracking_enabled", false)
        switchTracking?.isChecked = isTrackingOn
        trackerBody?.visibility = if (isTrackingOn) View.VISIBLE else View.GONE

        switchTracking?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // If Usage Stats isn't enabled, we can ask for it
                if (!hasUsageStatsPermission()) {
                    switchTracking.isChecked = false
                    val dialogView = LayoutInflater.from(getThemedContext()).inflate(R.layout.dialog_confirm_action, null)
                    val dialog = androidx.appcompat.app.AlertDialog.Builder(getThemedContext())
                        .setView(dialogView)
                        .setCancelable(false)
                        .create()

                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    val tvTitle = dialogView.findViewById<TextView>(R.id.tvConfirmTitle)
                    val tvMessage = dialogView.findViewById<TextView>(R.id.tvConfirmMessage)
                    val btnPositive = dialogView.findViewById<Button>(R.id.btnConfirmAction)
                    val btnNegative = dialogView.findViewById<Button>(R.id.btnConfirmCancel)

                    tvTitle.text = "Data Collection Disclosure"
                    tvMessage.text = "CashDash collects data about which apps you open to detect when you are using supported shopping applications. This enables the Taptrack widget to appear automatically while you shop. This data is kept strictly on your device and never shared."
                    tvTitle.gravity = android.view.Gravity.CENTER
                    tvMessage.gravity = android.view.Gravity.START
                    tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    tvTitle.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    btnPositive.text = "I Agree"
                    btnPositive.setTextColor(android.graphics.Color.WHITE)
                    btnNegative.text = "Cancel"
                    btnNegative.visibility = View.VISIBLE

                    btnPositive.setOnClickListener {
                        dialog.dismiss()
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        hideAll()
                    }

                    btnNegative.setOnClickListener {
                        dialog.dismiss()
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    } else {
                        dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                    }
                    dialog.show()

                    val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
                    dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                    return@setOnCheckedChangeListener
                }
                
                // Start the foreground service
                val serviceIntent = Intent(this, AppUsageTrackerService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
                prefs.edit().putBoolean("tracking_enabled", true).apply()
                trackerBody?.visibility = View.VISIBLE
                populateTrackerData()
            } else {
                prefs.edit().putBoolean("tracking_enabled", false).apply()
                
                // Stop the AppUsageTrackerService
                stopService(Intent(this@FloatingWidgetService, AppUsageTrackerService::class.java))
                
                // Disappear the widget completely
                hideAll()
            }
        }

        if (isTrackingOn) {
            populateTrackerData()
        }

        trackerView?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideAll()
                true
            } else {
                false
            }
        }

        windowManager.addView(trackerView, params)
        isWidgetShowing = true
    }

    private fun populateTrackerData() {
        val edtTitle = trackerView?.findViewById<EditText>(R.id.edtTitle)
        val edtAmount = trackerView?.findViewById<EditText>(R.id.edtAmount)
        val spinner = trackerView?.findViewById<Spinner>(R.id.spinnerAllocation)
        val tvWalletHint = trackerView?.findViewById<TextView>(R.id.tvWalletHint)
        val btnSave = trackerView?.findViewById<android.widget.TextView>(R.id.btnSave)

        edtTitle?.setText(currentAppName)

        serviceScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FloatingWidgetService)
            val prefsWallet = getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
            val walletMaxObj = prefsWallet.all["initial_balance"]
            val walletMax = walletMaxObj?.toString()?.toFloatOrNull()?.toInt() ?: 0
            val currentWalletObj = prefsWallet.all["wallet_balance"]
            val currentWallet = currentWalletObj?.toString()?.toFloatOrNull()?.toInt() ?: 0

            val prefsCat = getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
            val savedCategories = prefsCat.getStringSet("categories", emptySet()) ?: emptySet()
            val categoriesList = savedCategories.toMutableList()
            if (!categoriesList.contains("Food")) categoriesList.add("Food")
            if (!categoriesList.contains("Shopping")) categoriesList.add("Shopping")
            if (!categoriesList.contains("no choice")) categoriesList.add("no choice")

            val displayList = mutableListOf<String>()
            for (cat in categoriesList) {
                val limitObj = prefsCat.all["LIMIT_$cat"]
                val limit = limitObj?.toString()?.toFloatOrNull()?.toInt() ?: -1
                val spent = getSpentForCategory(db, cat).toInt()
                if (limit > 0) {
                    displayList.add("$cat - ₹$spent / ₹$limit")
                } else {
                    displayList.add(cat)
                }
            }

            withContext(Dispatchers.Main) {
                tvWalletHint?.text = "Wallet: ₹$currentWallet / ₹$walletMax"
                val themedContext = getThemedContext()
                val adapter = ArrayAdapter(themedContext, R.layout.spinner_item_tracker, displayList)
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_tracker)
                spinner?.adapter = adapter

                btnSave?.setOnClickListener {
                    val amountStr = edtAmount?.text.toString().trim()
                    if (amountStr.isEmpty()) {
                        Toast.makeText(this@FloatingWidgetService, "Enter amount", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val amount = amountStr.toFloatOrNull() ?: 0f
                    val selectedIdx = spinner?.selectedItemPosition ?: 0
                    val rawCategory = categoriesList.getOrNull(selectedIdx) ?: "no choice"

                    saveExpense(edtTitle?.text.toString().trim(), amount, rawCategory)
                }
            }
        }
    }

    private suspend fun getSpentForCategory(db: AppDatabase, category: String): Float {
        val startCal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        val endCal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)) }
        val txns = db.transactionDao().getTransactionsInRange(startCal.timeInMillis, endCal.timeInMillis)
        return txns.filter { it.category == category }.sumOf { it.amount.toDouble() }.toFloat()
    }

    private fun saveExpense(title: String, amount: Float, category: String) {
        serviceScope.launch(Dispatchers.IO) {
            HistoryDataManager.saveTransaction(this@FloatingWidgetService, title, amount, category)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@FloatingWidgetService, "Saved Successfully!", Toast.LENGTH_SHORT).show()
                showBubble()
                // Broadcast to update UI
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this@FloatingWidgetService)
                    .sendBroadcast(Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))
            }
        }
    }

    private fun hideAll() {
        try {
            if (bubbleView != null) {
                windowManager.removeView(bubbleView)
                bubbleView = null
            }
            if (trackerView != null) {
                windowManager.removeView(trackerView)
                trackerView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isWidgetShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        hideAll()
        serviceScope.cancel()
    }

    // Helper to check if usage stats permission is enabled
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
