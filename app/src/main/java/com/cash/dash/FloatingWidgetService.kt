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
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isSystemDark = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val themeResId = if (!isSystemDark) R.style.Theme_Cashdash_White else R.style.Theme_Cashdash
        return androidx.appcompat.view.ContextThemeWrapper(this, themeResId)
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
                        if (Math.abs(dx) > 20 || Math.abs(dy) > 20) isClick = false
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
                    val themedContext = getThemedContext()
                    val dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_confirm_action, null)
                    val dialog = android.app.AlertDialog.Builder(themedContext)
                        .setView(dialogView)
                        .setCancelable(false)
                        .create()

                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    val tvTitle = dialogView.findViewById<TextView>(R.id.tvConfirmTitle)
                    val tvMessage = dialogView.findViewById<TextView>(R.id.tvConfirmMessage)
                    val btnPositive = dialogView.findViewById<Button>(R.id.btnConfirmAction)
                    val btnNegative = dialogView.findViewById<Button>(R.id.btnConfirmCancel)

                    tvTitle.text = "Data Collection Disclosure"
                    tvMessage.text = "CashDash collects data about which apps you open to detect when you are using supported shopping applications. This enables the TapTrack widget to appear automatically while you shop. This data is kept strictly on your device and never shared."
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

        val addTime = System.currentTimeMillis()
        trackerView?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (System.currentTimeMillis() - addTime > 300) {
                    showBubble()
                }
                true
            } else {
                false
            }
        }

        trackerView?.isFocusable = true
        trackerView?.isFocusableInTouchMode = true
        trackerView?.requestFocus()
        trackerView?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    showBubble()
                }
                true
            } else {
                false
            }
        }

        windowManager.addView(trackerView, params)
        isWidgetShowing = true
    }

    private var selectedAllocation: String? = null
    private var isDropdownExpanded = false

    private fun populateTrackerData() {
        val edtTitle = trackerView?.findViewById<EditText>(R.id.edtTitle)
        val edtAmount = trackerView?.findViewById<EditText>(R.id.edtAmount)
        val layoutAllocationBtn = trackerView?.findViewById<LinearLayout>(R.id.layoutAllocationBtn)
        val tvSelectedAllocation = trackerView?.findViewById<TextView>(R.id.tvSelectedAllocation)
        val imgAllocationArrow = trackerView?.findViewById<ImageView>(R.id.imgAllocationArrow)
        val layoutAllocationExpandable = trackerView?.findViewById<LinearLayout>(R.id.layoutAllocationExpandable)
        val tvWalletHint = trackerView?.findViewById<TextView>(R.id.tvWalletHint)
        val btnSave = trackerView?.findViewById<android.widget.TextView>(R.id.btnSave)

        edtTitle?.setText(currentAppName)

        // Read app memory
        val savedAlloc = getSharedPreferences("AppAllocationPrefs", Context.MODE_PRIVATE)
            .getString("ALLOC_$currentAppName", null)

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
            if (categoriesList.isEmpty()) {
                categoriesList.add("Create new allocation")
            }

            val displayList = mutableListOf<String>()
            for (cat in categoriesList) {
                if (cat == "Create new allocation") {
                    displayList.add(cat)
                } else {
                    val limitObj = prefsCat.all["LIMIT_$cat"]
                    val limit = limitObj?.toString()?.toFloatOrNull()?.toInt() ?: -1
                    val spent = getSpentForCategory(db, cat).toInt()
                    if (limit > 0) {
                        displayList.add("$cat - ₹$spent / ₹$limit")
                    } else {
                        displayList.add(cat)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val themedContext = getThemedContext()
                tvWalletHint?.text = "Wallet: ₹$currentWallet / ₹$walletMax"
                
                // Pre-fill memory case-insensitively
                val matchedCategory = if (savedAlloc != null) {
                    categoriesList.find { it.equals(savedAlloc, ignoreCase = true) }
                } else null

                if (matchedCategory != null) {
                    if (matchedCategory != savedAlloc && currentAppName.isNotBlank()) {
                        getSharedPreferences("AppAllocationPrefs", Context.MODE_PRIVATE)
                            .edit().putString("ALLOC_$currentAppName", matchedCategory).apply()
                        FirestoreSyncManager.pushAllDataToCloud(this@FloatingWidgetService)
                    }
                }
                val initialAlloc = matchedCategory
                
                selectedAllocation = initialAlloc
                tvSelectedAllocation?.text = if (initialAlloc != null) {
                    val spent = getSpentForCategory(db, initialAlloc).toInt()
                    val limit = prefsCat.all["LIMIT_$initialAlloc"]?.toString()?.toFloatOrNull()?.toInt() ?: -1
                    if (limit > 0) "$initialAlloc - ₹$spent / ₹$limit" else initialAlloc
                } else "Select Allocation"
                
                layoutAllocationExpandable?.removeAllViews()
                
                val density = resources.displayMetrics.density

                val updateDropdownTicks = {
                    val count = layoutAllocationExpandable?.childCount ?: 0
                    val tick = androidx.core.content.ContextCompat.getDrawable(themedContext, R.drawable.ic_check_green)
                    for (i in 0 until count) {
                        val child = layoutAllocationExpandable?.getChildAt(i) as? TextView
                        if (child != null) {
                            val rawCat = child.tag as? String
                            val showTick = rawCat != null && rawCat == selectedAllocation && rawCat != "Create new allocation"
                            child.setCompoundDrawablesWithIntrinsicBounds(null, null, if (showTick) tick else null, null)
                        }
                    }
                }

                for (catInfo in displayList) {
                    val rawCat = categoriesList[displayList.indexOf(catInfo)]
                    val tv = TextView(themedContext).apply {
                        text = catInfo
                        tag = rawCat
                        setTextColor(ThemeHelper.resolveColorAttr(themedContext, R.attr.textPrimaryColor))
                        textSize = 14f
                        setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                        isClickable = true
                        isFocusable = true
                        
                        setOnClickListener {
                            selectedAllocation = rawCat
                            tvSelectedAllocation?.text = catInfo
                            isDropdownExpanded = false
                            layoutAllocationExpandable?.visibility = View.GONE
                            imgAllocationArrow?.rotation = 0f
                            
                            if (rawCat != "Create new allocation") {
                                if (currentAppName.isNotBlank()) {
                                    getSharedPreferences("AppAllocationPrefs", Context.MODE_PRIVATE)
                                        .edit().putString("ALLOC_$currentAppName", rawCat).apply()
                                    FirestoreSyncManager.pushAllDataToCloud(this@FloatingWidgetService)
                                }
                            } else {
                                val amountStr = edtAmount?.text.toString().trim()
                                val amount = amountStr.toFloatOrNull() ?: 0f
                                showCreateAllocationDialog(edtTitle?.text.toString().trim(), amount)
                            }
                            updateDropdownTicks()
                        }
                    }
                    layoutAllocationExpandable?.addView(tv)
                }

                updateDropdownTicks()

                layoutAllocationBtn?.setOnClickListener {
                    isDropdownExpanded = !isDropdownExpanded
                    if (isDropdownExpanded) {
                        layoutAllocationExpandable?.visibility = View.VISIBLE
                        imgAllocationArrow?.rotation = 180f
                        // Remove bottom margin of button when expanded
                        val params = layoutAllocationBtn.layoutParams as ViewGroup.MarginLayoutParams
                        params.bottomMargin = 0
                        layoutAllocationBtn.layoutParams = params
                    } else {
                        layoutAllocationExpandable?.visibility = View.GONE
                        imgAllocationArrow?.rotation = 0f
                        // Restore margin if any, though our layout has 0dp bottom margin originally on the btn
                    }
                }

                btnSave?.setOnClickListener {
                    val amountStr = edtAmount?.text.toString().trim()
                    if (amountStr.isEmpty()) {
                        Toast.makeText(this@FloatingWidgetService, "Enter amount", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val amount = amountStr.toFloatOrNull() ?: 0f
                    val rawCategory = selectedAllocation ?: "Create new allocation"

                    if (rawCategory == "Create new allocation") {
                        showCreateAllocationDialog(edtTitle?.text.toString().trim(), amount)
                    } else {
                        // Store memory
                        if (currentAppName.isNotBlank()) {
                            getSharedPreferences("AppAllocationPrefs", Context.MODE_PRIVATE)
                                .edit().putString("ALLOC_$currentAppName", rawCategory).apply()
                            FirestoreSyncManager.pushAllDataToCloud(this@FloatingWidgetService)
                        }
                        
                        saveExpense(edtTitle?.text.toString().trim(), amount, rawCategory)
                    }
                }
            }
        }
    }

    private fun showCreateAllocationDialog(expenseTitle: String, expenseAmount: Float) {
        val density = resources.displayMetrics.density
        val themedContext = getThemedContext()
        val inputContainer = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundResource(R.drawable.bg_transaction)
        }

        val titleView = TextView(themedContext).apply {
            text = "Create New Allocation"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
            setTextColor(ThemeHelper.resolveColorAttr(themedContext, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        inputContainer.addView(titleView)

        val edtName = EditText(themedContext).apply {
            hint = "Allocation Name (e.g. Shopping)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHintTextColor(ThemeHelper.resolveColorAttr(themedContext, R.attr.textMutedColor))
            setTextColor(ThemeHelper.resolveColorAttr(themedContext, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(themedContext, android.util.TypedValue().apply { themedContext.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }
        inputContainer.addView(edtName)

        val btnContainer = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnCancel = Button(themedContext).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(themedContext, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(themedContext, android.util.TypedValue().apply { themedContext.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        btnContainer.addView(btnCancel)

        val btnCreate = Button(themedContext).apply {
            text = "Create"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(themedContext, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(themedContext, android.util.TypedValue().apply { themedContext.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnContainer.addView(btnCreate)
        inputContainer.addView(btnContainer)

        val allocDialog = android.app.AlertDialog.Builder(themedContext)
            .setView(inputContainer)
            .setCancelable(true)
            .create()

        allocDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            allocDialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            allocDialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }

        btnCancel.setOnClickListener {
            allocDialog.dismiss()
        }

        btnCreate.setOnClickListener {
            val name = edtName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this@FloatingWidgetService, "Please enter a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.equals("Overall", ignoreCase = true)) {
                Toast.makeText(this@FloatingWidgetService, "'Overall' is a reserved name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
            val savedList = prefs.getStringSet("categories", emptySet()) ?: emptySet()
            val newSet = savedList.toMutableSet()
            newSet.add(name)
            prefs.edit().putStringSet("categories", newSet).apply()

            FirestoreSyncManager.pushAllDataToCloud(this@FloatingWidgetService)

            allocDialog.dismiss()
            Toast.makeText(this@FloatingWidgetService, "Allocation created!", Toast.LENGTH_SHORT).show()

            populateTrackerData()
            saveExpense(expenseTitle, expenseAmount, name)
        }

        allocDialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        allocDialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            android.util.Log.e("FloatingWidgetService", "Error removing widget view", e)
        }
        isWidgetShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        hideAll()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Aggressive swipe-to-close on Samsung devices
        CashDashApplication.setOfflineImmediate(this)
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
