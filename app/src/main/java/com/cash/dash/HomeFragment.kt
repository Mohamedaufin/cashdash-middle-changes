@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import java.util.*
import kotlinx.coroutines.*
import android.widget.Button
import android.widget.PopupMenu
import android.app.DatePickerDialog
import android.app.TimePickerDialog

class HomeFragment : Fragment() {

    private val PREFS = "AppPrefs"
    private val KEY_NAME = "user_name"
    private val KEY_BALANCE = "wallet_balance"
    private val PREFS_SCHEDULE = "MoneySchedulePrefs"
    private val KEY_NEXT_DATE = "next_date"
    private val KEY_FREQUENCY = "frequency"

    // Cache for optimization
    private var lastLoadedName: String? = null
    private var lastLoadedBalance: String? = null
    private var lastLoadedBarMode: String? = null
    private var lastLoadedBarType: String? = null
    private var lastLoadedDateStr: String? = null
    private var activeResetDialog: AlertDialog? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reminderRunnable: Runnable? = null
    private var dobLockDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    /** Gender chosen in the lock sheet. Held until Save so dismissing cannot half-apply it. */
    private var lockSelectedGender: String = ""

    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            refreshUI()
        }
    }

    private val appPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "user_dob") {
            checkDobLock()
        }
    }

    private val walletListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_BALANCE || key == "initial_balance" || key == "balance_bar_mode" || key == "balance_bar_type") {
            view?.post { view?.let { loadBalance(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadUserName(view)

        // Ensure username is visible for the shared element transition
        view.findViewById<TextView>(R.id.tvUsernameHome)?.apply {
            visibility = View.VISIBLE
            alpha = 1f
        }

        loadBalance(view)
        updateNextMoneyDays(view)



        // Set theme-aware icons
        view.findViewById<ImageView>(R.id.btnMenu)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_glass_menu_vector))
        view.findViewById<ImageView>(R.id.iconScanner)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_scanner))
        view.findViewById<ImageView>(R.id.iconRigorTracker)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_rigor_tracker))

        view.findViewById<LinearLayout>(R.id.cardScanner).setOnClickListener {
            animateAndStart(view.findViewById<ImageView>(R.id.iconScanner)) {
                startActivity(Intent(requireContext(), ScannerActivity::class.java))
            }
        }

        val btnMenu = view.findViewById<ImageView>(R.id.btnMenu)
        btnMenu.setOnClickListener {
            animateAndStart(btnMenu) {
                startActivity(Intent(requireContext(), MenuActivity::class.java))
            }
        }

        val btnSmartAssistant = view.findViewById<ImageView>(R.id.btnSmartAssistant)
        btnSmartAssistant?.setOnClickListener {
            animateAndStart(btnSmartAssistant) {
                startActivity(Intent(requireContext(), FinminderActivity::class.java))
            }
        }

        val btnTaptrack = view.findViewById<ImageView>(R.id.btnTaptrack)
        btnTaptrack?.setOnClickListener {
            animateAndStart(btnTaptrack) {
                startActivity(Intent(requireContext(), TaptrackActivity::class.java))
            }
        }

        view.findViewById<ImageView>(R.id.btnTaptrack)?.setOnLongClickListener {
            val smartPrefs = requireContext().getSharedPreferences("SmartAssistantPrefs", android.content.Context.MODE_PRIVATE)
            val isTrackingOn = smartPrefs.getBoolean("tracking_enabled", false)
            if (isTrackingOn) {
                smartPrefs.edit().putBoolean("tracking_enabled", false).apply()
                requireContext().stopService(Intent(requireContext(), AppUsageTrackerService::class.java))
                android.widget.Toast.makeText(requireContext(), "TapTrack is off", android.widget.Toast.LENGTH_SHORT).show()
                TaptrackWidget.refreshAllWidgets(requireContext())
                refreshUI()
                true
            } else {
                if (!hasUsageStatsPermission() || !android.provider.Settings.canDrawOverlays(requireContext())) {
                    android.widget.Toast.makeText(requireContext(), "Please finish setup in TapTrack first", android.widget.Toast.LENGTH_SHORT).show()
                    startActivity(Intent(requireContext(), TaptrackActivity::class.java))
                } else {
                    smartPrefs.edit().putBoolean("tracking_enabled", true).apply()
                    val serviceIntent = Intent(requireContext(), AppUsageTrackerService::class.java)
                    androidx.core.content.ContextCompat.startForegroundService(requireContext(), serviceIntent)
                    android.widget.Toast.makeText(requireContext(), "TapTrack is on", android.widget.Toast.LENGTH_SHORT).show()
                    TaptrackWidget.refreshAllWidgets(requireContext())
                    refreshUI()
                }
                true
            }
        }

        val walletContainer = view.findViewById<View>(R.id.walletContainer)
        walletContainer?.setOnClickListener {
            startActivity(Intent(requireContext(), BalanceSetupActivity::class.java))
        }
        walletContainer?.setOnLongClickListener {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val email = user?.email?.lowercase() ?: ""
            if (AdminManager.isCurrentUserAdmin()) {
                startActivity(Intent(requireContext(), AdminActivity::class.java))
                activity?.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                true
            } else {
                false
            }
        }
        var touchDownInside = false
        walletContainer?.setOnTouchListener { v, event ->
            val cx = v.width / 2f
            val cy = v.height / 2f
            val dx = event.x - cx
            val dy = event.y - cy
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
            val radius = v.width / 2f

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownInside = distance <= radius
                    if (!touchDownInside) return@setOnTouchListener true // Absorb touches outside circle
                    false // Let system handle long-press
                }
                MotionEvent.ACTION_UP -> {
                    if (!touchDownInside || distance > radius) return@setOnTouchListener true
                    false
                }
                else -> false
            }
        }

        view.findViewById<LinearLayout>(R.id.cardRigor).setOnClickListener {
            animateAndStart(view.findViewById<ImageView>(R.id.iconRigorTracker)) {
                startActivity(Intent(requireContext(), RigorActivity::class.java))
            }
        }

        view.findViewById<TextView>(R.id.tvNextMoney)?.setOnClickListener {
            startActivity(Intent(requireContext(), MoneyScheduleActivity::class.java))
        }


        // Start the postponed transition from MainActivity once this view is ready
        view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                activity?.supportStartPostponedEnterTransition()
                return true
            }
        })

        val wPrefs = WalletStore.get(requireContext())
        wPrefs.registerOnSharedPreferenceChangeListener(walletListener)
        val appPrefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        appPrefs.registerOnSharedPreferenceChangeListener(appPrefsListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val wPrefs = WalletStore.get(requireContext())
        wPrefs.unregisterOnSharedPreferenceChangeListener(walletListener)
        val appPrefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        appPrefs.unregisterOnSharedPreferenceChangeListener(appPrefsListener)
        dobLockDialog?.dismiss()
        dobLockDialog = null
    }

    override fun onResume() {
        super.onResume()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            syncReceiver, android.content.IntentFilter(FirestoreSyncManager.ACTION_SYNC_UPDATE)
        )
        checkPendingTransactions()
        refreshUI()

        // Only check DOB after the initial cloud pull is done.
        // isInitialSyncCompleted = false until pullDataFromCloud fully completes.
        // appPrefsListener will auto-trigger checkDobLock() when user_dob arrives from cloud.
        view?.postDelayed({
            if (isAdded && FirestoreSyncManager.isInitialSyncCompleted) {
                checkDobLock()
                checkInitialSetup()
            } else if (isAdded) {
                // Pull still in progress - wait up to 4 more seconds then check
                view?.postDelayed({
                    if (isAdded) {
                        checkDobLock()
                        checkInitialSetup()
                    }
                }, 4000)
            }
        }, 800)
    }

    override fun onPause() {
        super.onPause()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(syncReceiver)
        reminderRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun checkDobLock() {
        val prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val dob = prefs.getString("user_dob", "") ?: ""
        // Gender counts as missing too. Accounts created before the field existed have a
        // date but no gender, and nothing else in the app ever asks for one -- so without
        // this they could never have it, and Profile had nothing to show. The same sheet
        // collects both, so one prompt fills whichever is absent.
        val gender = prefs.getString("user_gender", "") ?: ""
        if (dob.isEmpty() || gender.isEmpty()) {
            if (dobLockDialog == null || dobLockDialog?.isShowing == false) {
                showDobLockDialog()
            }
        } else {
            dobLockDialog?.dismiss()
            dobLockDialog = null
        }
    }

    private fun showDobLockDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), ThemeHelper.getBottomSheetTheme(requireContext()))
        dialog.setCancelable(false)
        val sheetView = layoutInflater.inflate(R.layout.layout_dob_bottom_sheet, null)
        dialog.setContentView(sheetView)

        val pickerYear = sheetView.findViewById<android.widget.NumberPicker>(R.id.pickerYear)
        val pickerMonth = sheetView.findViewById<android.widget.NumberPicker>(R.id.pickerMonth)
        val pickerDay = sheetView.findViewById<android.widget.NumberPicker>(R.id.pickerDay)
        val btnSave = sheetView.findViewById<android.widget.Button>(R.id.btnSaveDate)
        
        sheetView.findViewById<View>(R.id.dragHandle)?.visibility = View.GONE
        // Ask only for what is actually missing. An existing user with a date but no
        // gender should not be made to re-pick a date they already gave, and vice versa.
        val lockPrefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val needDob = (lockPrefs.getString("user_dob", "") ?: "").isEmpty()
        val needGender = (lockPrefs.getString("user_gender", "") ?: "").isEmpty()

        sheetView.findViewById<View>(R.id.tvDobLabel)?.visibility = if (needDob) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.layoutDobPickers)?.visibility = if (needDob) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.tvGenderLabel)?.visibility = if (needGender) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.layoutGenderChips)?.visibility = if (needGender) View.VISIBLE else View.GONE

        sheetView.findViewById<TextView>(R.id.tvSheetTitle)?.text = when {
            needDob && needGender -> "Select Date of Birth and Gender to continue"
            needDob -> "Select Date of Birth to continue"
            else -> "Select Gender to continue"
        }
        sheetView.findViewById<View>(R.id.btnSaveDate)?.setOnClickListener(null) // Reset default listener if any

        // Setup Pickers
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        pickerYear.minValue = 1900
        pickerYear.maxValue = currentYear

        pickerMonth.minValue = 1
        pickerMonth.maxValue = 12
        val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        pickerMonth.displayedValues = monthNames

        fun updateMaxDay(y: Int, m: Int) {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.YEAR, y)
            cal.set(java.util.Calendar.MONTH, m - 1)
            val maxDay = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            pickerDay.minValue = 1
            pickerDay.maxValue = maxDay
        }

        pickerYear.value = 2000
        pickerMonth.value = 6
        updateMaxDay(2000, 6)
        pickerDay.value = 15

        pickerYear.setOnValueChangedListener { _, _, newVal -> updateMaxDay(newVal, pickerMonth.value) }
        pickerMonth.setOnValueChangedListener { _, _, newVal -> updateMaxDay(pickerYear.value, newVal) }

        val textColor = ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor)
        fun customizePicker(picker: android.widget.NumberPicker) {
            for (i in 0 until picker.childCount) {
                val child = picker.getChildAt(i)
                if (child is android.widget.EditText) {
                    try { child.setTextColor(textColor); child.invalidate() } catch (e: Exception) {}
                }
            }
        }
        customizePicker(pickerYear)
        customizePicker(pickerMonth)
        customizePicker(pickerDay)

        // Same chips and the same keys the register sheet writes, so a gender captured
        // here is indistinguishable from one captured at sign-up.
        val chipMale = sheetView.findViewById<TextView>(R.id.chipGenderMale)
        val chipFemale = sheetView.findViewById<TextView>(R.id.chipGenderFemale)
        val chipUnspecified = sheetView.findViewById<TextView>(R.id.chipGenderUnspecified)
        val genderChips = listOfNotNull(chipMale, chipFemale, chipUnspecified)
        val genderKeys = mapOf(chipMale to "male", chipFemale to "female", chipUnspecified to "undisclosed")

        // Pre-select whatever is already stored, so a user prompted only for a missing
        // date does not have to re-pick a gender they already gave.
        lockSelectedGender = requireContext()
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("user_gender", "") ?: ""

        fun paintGenderChips() {
            genderChips.forEach { chip ->
                val isChosen = genderKeys[chip] == lockSelectedGender
                chip.isSelected = isChosen
                chip.setTypeface(null, if (isChosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        genderChips.forEach { chip ->
            chip.setOnClickListener {
                lockSelectedGender = genderKeys[chip] ?: ""
                paintGenderChips()
            }
        }
        paintGenderChips()

        btnSave.setOnClickListener {
            // Only require a choice for the part that was actually asked for.
            if (needGender && lockSelectedGender.isEmpty()) {
                ToastHelper.showToast(requireContext(), "Please select your gender.")
                return@setOnClickListener
            }
            val prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // Write only what was asked. Writing the hidden field back would push a value
            // read off pickers the user never saw.
            if (needDob) {
                val formattedDate = String.format("%02d %s %d", pickerDay.value, monthNames[pickerMonth.value - 1].substring(0, 3), pickerYear.value)
                editor.putString("user_dob", formattedDate)
            }
            if (needGender) {
                editor.putString("user_gender", lockSelectedGender)
            }
            editor.apply()

            // Sync up immediately. FirestoreSyncManager carries gender alongside dob, so
            // this is what actually creates the field on the cloud profile for accounts
            // that predate it.
            FirestoreSyncManager.pushAllDataToCloud(requireContext())

            dialog.dismiss()
            dobLockDialog = null
        }

        dobLockDialog = dialog
        dialog.show()
    }

    private fun checkInitialSetup() {
        val density = resources.displayMetrics.density
        val prefs = WalletStore.get(requireContext())
        val initialBalance = prefs.getInt("initial_balance", -1)

        // 🔧 FIX: Explicitly aligns with BalanceSetupActivity logic where <= 0 is treated as unset
        if (initialBalance <= 0) {
            val box = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val p = (28 * density).toInt()
                setPadding(p, p, p, (24 * density).toInt())
                setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
            }

            val titleView = TextView(requireContext()).apply {
                text = "Welcome to CashDash! ⚡"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (20 * density).toInt())
            }
            box.addView(titleView)

            val messageView = TextView(requireContext()).apply {
                text = "To start tracking your money, please set up your initial wallet balance."
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textMutedColor))
                gravity = android.view.Gravity.CENTER
                setLineSpacing(8f, 1f)
                setPadding(0, 0, 0, (40 * density).toInt())
            }
            box.addView(messageView)

            val dialog = AlertDialog.Builder(requireContext())
                .setView(box)
                .setCancelable(false)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val btnSetup = android.widget.Button(requireContext()).apply {
                text = "Set Up Wallet"
                isAllCaps = false
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
                setOnClickListener {
                    startActivity(Intent(requireContext(), BalanceSetupActivity::class.java))
                    dialog.dismiss()
                }
            }
            box.addView(btnSetup)
            dialog.show()
        }
    }

    fun refreshUI() {
        view?.let {
            loadUserName(it)
            loadBalance(it)
            updateNextMoneyDays(it)

            // Update smart assistant icon tint based on tracking status
            val smartPrefs = requireContext().getSharedPreferences("SmartAssistantPrefs", android.content.Context.MODE_PRIVATE)
            var isTrackingOn = smartPrefs.getBoolean("tracking_enabled", false)

            if (isTrackingOn) {
                if (!hasUsageStatsPermission() || !android.provider.Settings.canDrawOverlays(requireContext())) {
                    isTrackingOn = false
                    smartPrefs.edit().putBoolean("tracking_enabled", false).apply()
                    requireContext().stopService(Intent(requireContext(), AppUsageTrackerService::class.java))
                }
            }

            val iconTaptrack = it.findViewById<ImageView>(R.id.btnTaptrack)
            if (isTrackingOn) {
                val activeColor = if (ThemeHelper.isWhiteTheme(requireContext())) "#008000" else "#1DD15D"
                iconTaptrack?.setColorFilter(android.graphics.Color.parseColor(activeColor))
            } else {
                iconTaptrack?.setColorFilter(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor)) // Original Color
            }
        }
    }

    private fun loadUserName(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, "User") ?: "User"
        if (name != lastLoadedName) {
            view.findViewById<TextView>(R.id.tvUsernameHome)?.text = "Hello $name,"
            lastLoadedName = name
        }
    }

    private fun loadBalance(view: View) {
        val prefs = WalletStore.get(requireContext())
        val bal = prefs.getInt(KEY_BALANCE, 0)
        val initialRaw = prefs.getInt("initial_balance", -1)

        val initial = if (initialRaw == -1) 0 else initialRaw
        val displayInitial = if (initial == 0 && bal == 0) 0 else initial.coerceAtLeast(1)

        val mode = prefs.getString("balance_bar_mode", "gradient") ?: "gradient"
        val type = prefs.getString("balance_bar_type", "gradient1") ?: "gradient1"

        val balanceStr = "₹$bal/₹$displayInitial"
        if (balanceStr != lastLoadedBalance || mode != lastLoadedBarMode || type != lastLoadedBarType) {
            view.findViewById<TextView>(R.id.tvBalance)?.text = balanceStr
            val progressPercent = if (displayInitial > 0) ((bal.toFloat() / displayInitial.toFloat()) * 100).toInt().coerceIn(0, 100) else 0

            val pBar = view.findViewById<com.cash.dash.GradientCircularProgressView>(R.id.walletProgress)
            pBar?.setColorConfig(mode, type)
            pBar?.setProgressCompat(progressPercent, true)

            lastLoadedBalance = balanceStr
            lastLoadedBarMode = mode
            lastLoadedBarType = type
        }
    }

    private fun updateNextMoneyDays(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS_SCHEDULE, android.content.Context.MODE_PRIVATE)
        val nextDate = prefs.getLong(KEY_NEXT_DATE, -1)
        val freq = prefs.getInt(KEY_FREQUENCY, -1)
        val textView = view.findViewById<TextView>(R.id.tvNextMoney) ?: return
        val tvResetPending = view.findViewById<TextView>(R.id.tvResetPending) ?: return

        if (nextDate <= 0L || freq == -1) {
            textView.text = "Money schedule not set. Tap text to set"
            tvResetPending.visibility = View.GONE
            return
        }

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val next = Calendar.getInstance().apply {
            timeInMillis = nextDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val isDue = today.after(next)
        if (isDue) {
            val postponeUntil = prefs.getLong("postpone_until", 0L)
            val now = System.currentTimeMillis()

            if (now >= postponeUntil) {
                reminderRunnable?.let { handler.removeCallbacks(it) }
                tvResetPending.visibility = View.GONE
                showResetConfirmationDialog(nextDate, freq)
            } else {
                tvResetPending.visibility = View.VISIBLE
                val reminderCal = Calendar.getInstance().apply { timeInMillis = postponeUntil }
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
                val formattedTime = sdf.format(reminderCal.time).lowercase(java.util.Locale.US)
                tvResetPending.text = "Reminder set at $formattedTime. Tap text to configure"

                tvResetPending.setOnClickListener {
                    showResetConfirmationDialog(nextDate, freq)
                }

                // Schedule reminder popup when time comes
                reminderRunnable?.let { handler.removeCallbacks(it) }
                val delay = postponeUntil - now
                val runnable = Runnable {
                    if (isAdded) {
                        view.findViewById<TextView>(R.id.tvResetPending)?.visibility = View.GONE
                        showResetConfirmationDialog(nextDate, freq)
                    }
                }
                reminderRunnable = runnable
                handler.postDelayed(runnable, delay)
            }

            val nextDateStr = "%02d/%02d/%04d".format(
                next.get(Calendar.DAY_OF_MONTH),
                next.get(Calendar.MONTH) + 1,
                next.get(Calendar.YEAR)
            )
            if (nextDateStr != lastLoadedDateStr) {
                textView.text = "This money was tentatively till $nextDateStr"
                lastLoadedDateStr = nextDateStr
            }
        } else {
            reminderRunnable?.let { handler.removeCallbacks(it) }
            tvResetPending.visibility = View.GONE
            val nextDateStr = "%02d/%02d/%04d".format(
                next.get(Calendar.DAY_OF_MONTH),
                next.get(Calendar.MONTH) + 1,
                next.get(Calendar.YEAR)
            )
            if (nextDateStr != lastLoadedDateStr) {
                textView.text = "This money is tentatively till $nextDateStr"
                lastLoadedDateStr = nextDateStr
            }
        }
    }



    private fun showResetConfirmationDialog(nextDate: Long, freq: Int) {
        if (activeResetDialog?.isShowing == true) return

        val context = requireContext()
        val density = resources.displayMetrics.density
        var resetDemoAnim: android.animation.ValueAnimator? = null
        
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(context).apply {
            text = "Cycle Ended"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val cal = Calendar.getInstance().apply { timeInMillis = nextDate }
        val dateStr = "%02d/%02d/%04d".format(
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR)
        )

        val wPrefs = WalletStore.get(context)
        val nextCycleBal = wPrefs.getInt("next_cycle_initial_balance", -1)
        val initialBal = if (nextCycleBal > 0) nextCycleBal else wPrefs.getInt("initial_balance", 0)

        val content = TextView(context).apply {
            text = "Your cycle ended on $dateStr. Are you ready to refill your wallet to ₹$initialBal/₹$initialBal?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, (16 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        box.addView(content)

        // 📊 Live demo of allocation spent bar resetting to 0% in reverse
        val demoRow = layoutInflater.inflate(R.layout.item_rigor_category, box, false)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (8 * density).toInt()
        }
        demoRow.layoutParams = lp

        val txtName = demoRow.findViewById<TextView>(R.id.categoryName)
        val spentBar = demoRow.findViewById<View>(R.id.spentBar)
        val progressOuter = demoRow.findViewById<View>(R.id.progressOuter)
        val txtSpent = demoRow.findViewById<TextView>(R.id.txtSpent)
        val txtLimit = demoRow.findViewById<TextView>(R.id.txtLimit)
        val iconView = demoRow.findViewById<ImageView>(R.id.categoryIcon)

        txtName.text = "Food"
        iconView.setImageResource(R.drawable.ic_category_food)
        txtLimit.text = "Limit: ₹850"

        box.addView(demoRow)

        val hintContent = TextView(context).apply {
            text = "Your spending limits will also reset to ₹0."
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f)
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textSecondaryColor))
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, (24 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        box.addView(hintContent)

        val anim = android.animation.ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            duration = 3000 // 3 seconds loop
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        anim.addUpdateListener { valueAnimator ->
            val fraction = valueAnimator.animatedValue as Float
            val progressVal = when {
                fraction < 0.2f -> 1.0f
                fraction > 0.8f -> 0.0f
                else -> 1.0f - ((fraction - 0.2f) / 0.6f)
            }
            
            val currentSpent = (850 * progressVal).toInt()
            txtSpent.text = "Spent: ₹$currentSpent"
            
            val maxWidth = progressOuter.width
            spentBar.layoutParams.width = (maxWidth * progressVal).toInt()
            spentBar.requestLayout()

            if (progressVal >= 1.0f) {
                spentBar.setBackgroundResource(R.drawable.bg_glass_progress_fill_red)
            } else {
                spentBar.setBackgroundResource(R.drawable.bg_glass_progress_fill)
            }
        }
        demoRow.post { anim.start() }
        resetDemoAnim = anim

        val btnReset = Button(context).apply {
            text = "Yes, Reset cycle now"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, ThemeHelper.getDrawable(context, R.drawable.bg_3d_card))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
        }
        box.addView(btnReset)

        val spacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, (16 * density).toInt()) }
        box.addView(spacer)

        val remindLaterContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutTransition = android.animation.LayoutTransition()
        }

        val btnRemindLater = Button(context).apply {
            text = "Remind Me Later"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, ThemeHelper.getDrawable(context, R.drawable.bg_3d_card))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
        }
        remindLaterContainer.addView(btnRemindLater)

        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val marginParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            marginParams.topMargin = (8 * density).toInt()
            layoutParams = marginParams
            
            val tvTypedValue = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.inputBackground, tvTypedValue, true)
            if (tvTypedValue.resourceId != 0) {
                setBackgroundResource(tvTypedValue.resourceId)
            } else if (tvTypedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && tvTypedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                setBackgroundColor(tvTypedValue.data)
            }
        }

        var dialog: AlertDialog? = null
        val items = listOf("30 minutes", "1 hour", "3 hours", "Custom")

        items.forEachIndexed { index, itemText ->
            if (index > 0) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                }
                optionsContainer.addView(divider)
            }
            
            val optionView = TextView(context).apply {
                text = itemText
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (50 * density).toInt())
                
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                setBackgroundResource(typedValue.resourceId)
                
                isClickable = true
                isFocusable = true
            }
            optionsContainer.addView(optionView)
            
            optionView.setOnClickListener {
                optionsContainer.visibility = View.GONE
                val durationMs = when (index) {
                    0 -> 30L * 60 * 1000
                    1 -> 60L * 60 * 1000
                    2 -> 3L * 60 * 60 * 1000
                    3 -> {
                        dialog?.let { d -> showCustomDatePicker(d) }
                        return@setOnClickListener
                    }
                    else -> 0L
                }

                if (durationMs > 0L) {
                    val newPostponeUntil = System.currentTimeMillis() + durationMs
                    savePostponeTime(newPostponeUntil)
                    dialog?.dismiss()
                    val minutes = durationMs / (60 * 1000)
                    ToastHelper.showCustomToast(context, "Rescheduled. Reminding in ${if (minutes >= 60) "${minutes / 60} hour(s)" else "$minutes minutes"}", 1200L)
                    refreshUI()
                }
            }
        }
        
        remindLaterContainer.addView(optionsContainer)
        box.addView(remindLaterContainer)

        dialog = AlertDialog.Builder(context)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.setOnDismissListener { resetDemoAnim?.cancel() }
        activeResetDialog = dialog

        btnReset.setOnClickListener {
            dialog?.dismiss()
            performCycleReset(nextDate, freq)
        }

        btnRemindLater.setOnClickListener {
            optionsContainer.visibility = if (optionsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        dialog?.show()
        
        dialog?.window?.apply {
            setGravity(android.view.Gravity.CENTER)
            val lp = attributes
            lp.verticalMargin = 0f
            lp.y = 0
            attributes = lp
        }
    }

    private fun showPostponeDropdown(anchorView: View, parentDialog: AlertDialog) {
        val context = requireContext()
        val items = listOf("30 minutes", "1 hour", "3 hours", "Custom")
        val density = resources.displayMetrics.density
        val btnWidthDp = (anchorView.width / density).toInt()

        DropdownHelper.showBlinkingDropdown(
            context,
            anchorView,
            items,
            fixedWidthDp = btnWidthDp,
            horizontalOffsetDp = 0,
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        ) { position, _ ->
            val durationMs = when (position) {
                0 -> 30L * 60 * 1000        // 30 min
                1 -> 60L * 60 * 1000        // 1 hour
                2 -> 3L * 60 * 60 * 1000     // 3 hours
                3 -> {
                    showCustomDatePicker(parentDialog)
                    return@showBlinkingDropdown
                }
                else -> 0L
            }

            if (durationMs > 0L) {
                val newPostponeUntil = System.currentTimeMillis() + durationMs
                savePostponeTime(newPostponeUntil)
                parentDialog.dismiss()
                val minutes = durationMs / (60 * 1000)
                ToastHelper.showCustomToast(context, "Rescheduled. Reminding in ${if (minutes >= 60) "${minutes / 60} hour(s)" else "$minutes minutes"}", 1200L)
                refreshUI()
            }
        }
    }

    private fun showCustomDatePicker(parentDialog: AlertDialog) {
        val context = requireContext()
        val currentCal = Calendar.getInstance()

        val datePicker = DatePickerDialog(
            context,
            ThemeHelper.getDatePickerTheme(context),
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(Calendar.YEAR, year)
                selectedCal.set(Calendar.MONTH, month)
                selectedCal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                showCustomTimePicker(selectedCal, parentDialog)
            },
            currentCal.get(Calendar.YEAR),
            currentCal.get(Calendar.MONTH),
            currentCal.get(Calendar.DAY_OF_MONTH)
        )

        datePicker.datePicker.minDate = System.currentTimeMillis() - 1000
        datePicker.show()
    }

    private fun showCustomTimePicker(selectedCal: Calendar, parentDialog: AlertDialog) {
        val context = requireContext()
        val currentCal = Calendar.getInstance()

        val timePicker = android.app.TimePickerDialog(
            context,
            ThemeHelper.getDatePickerTheme(context),
            { _, hourOfDay, minute ->
                selectedCal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                selectedCal.set(Calendar.MINUTE, minute)
                selectedCal.set(Calendar.SECOND, 0)
                selectedCal.set(Calendar.MILLISECOND, 0)

                val selectedMs = selectedCal.timeInMillis
                if (selectedMs <= System.currentTimeMillis()) {
                    ToastHelper.showCustomToast(context, "Please choose a future time", 1000L)
                } else {
                    savePostponeTime(selectedMs)
                    parentDialog.dismiss()

                    val sdf = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.US)
                    val formatted = sdf.format(selectedCal.time).lowercase(java.util.Locale.US)
                    ToastHelper.showCustomToast(context, "Rescheduled. Reminding on $formatted", 1500L)
                    refreshUI()
                }
            },
            currentCal.get(Calendar.HOUR_OF_DAY),
            currentCal.get(Calendar.MINUTE),
            false
        )
        timePicker.show()
    }

    private fun savePostponeTime(timestamp: Long) {
        val prefs = requireContext().getSharedPreferences(PREFS_SCHEDULE, android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("postpone_until", timestamp).apply()
    }

    private fun performCycleReset(nextDate: Long, freq: Int) {
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS_SCHEDULE, android.content.Context.MODE_PRIVATE)
        val textView = view?.findViewById<TextView>(R.id.tvNextMoney) ?: return

        prefs.edit().remove("postpone_until").apply()

        CoroutineScope(Dispatchers.IO).launch {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val next = Calendar.getInstance().apply {
                timeInMillis = nextDate
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            while (next.before(today)) {
                next.add(Calendar.DAY_OF_YEAR, freq)
            }

            val newNextDateMs = next.timeInMillis
            prefs.edit().putLong(KEY_NEXT_DATE, newNextDateMs).apply()

            val categoryPrefs = context.getSharedPreferences("CategoryPrefs", android.content.Context.MODE_PRIVATE)
            val categories = categoryPrefs.getStringSet("categories", emptySet()) ?: emptySet()
            val graphPrefs = context.getSharedPreferences("GraphData", android.content.Context.MODE_PRIVATE)
            val graphEditor = graphPrefs.edit()
            for (cat in categories) {
                graphEditor.putFloat("SPENT_$cat", 0f)
            }
            graphEditor.putFloat("SPENT_no choice", 0f)
            graphEditor.apply()

            val wPrefs = WalletStore.get(context)
            val nextCycleBal = wPrefs.getInt("next_cycle_initial_balance", -1)
            val initialBal = if (nextCycleBal != -1) {
                wPrefs.edit().putInt("initial_balance", nextCycleBal).remove("next_cycle_initial_balance").apply()
                nextCycleBal
            } else {
                wPrefs.getInt("initial_balance", 0)
            }
            wPrefs.edit().putInt("wallet_balance", initialBal).apply()

            FirestoreSyncManager.pushAllDataToCloud(context)

            val newNextDateStr = "%02d/%02d/%04d".format(
                next.get(Calendar.DAY_OF_MONTH),
                next.get(Calendar.MONTH) + 1,
                next.get(Calendar.YEAR)
            )

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    textView.text = "This money is tentatively till $newNextDateStr"
                    lastLoadedDateStr = newNextDateStr
                    loadBalance(requireView())
                    view?.findViewById<TextView>(R.id.tvResetPending)?.visibility = View.GONE
                    ToastHelper.showCustomToast(context, "Cycle Reset Successfully!", 1000L)
                }
            }
        }
    }

    private fun animateAndStart(view: View, action: () -> Unit) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        view.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(7)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(8)
                    .withEndAction {
                        action()
                    }
                    .start()
            }
            .start()
    }

    private fun showDisclosureDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_disclosure, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.btnCancelDisclosure).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnAgreeDisclosure).setOnClickListener {
            dialog.dismiss()
            requestPermissionsForAssistant()
        }

        dialog.show()
    }

    private fun requestPermissionsForAssistant() {
        if (!android.provider.Settings.canDrawOverlays(requireContext())) {
            ToastHelper.showCustomToast(requireContext(), "Please enable 'Draw over other apps'", 1500L)
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${requireContext().packageName}")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                ToastHelper.showCustomToast(requireContext(), "Unable to open settings. Please enable manually.", 2000L)
            }
            return
        }

        // Check usage stats permission
        if (hasUsageStatsPermission()) {
            val prefs = requireContext().getSharedPreferences("SmartAssistantPrefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("tracking_enabled", true).apply()

            // Start the foreground service
            val serviceIntent = Intent(requireContext(), AppUsageTrackerService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(requireContext(), serviceIntent)

            ToastHelper.showCustomToast(requireContext(), "Smart Shopping Assistant is Active!", 1500L)
            refreshUI()
            return
        }

        // Show Prominent Disclosure Dialog
        AlertDialogHelper.createFlatDialogBuilder(requireContext())
            .setTitle("Data Collection Disclosure")
            .setMessage("CashDash collects data about which apps you open to detect when you are using supported shopping applications. This enables the TapTrack widget to appear automatically while you shop. This data is kept strictly on your device and never shared.")
            .setTitleGravity(android.view.Gravity.CENTER)
            .setMessageGravity(android.view.Gravity.START)
            .setTitleTextSize(16f)
            .setMessageTextSize(14f)
            .setTitleTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            .setPositiveTextColor(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            .setPositiveButton("I Agree") {
                val prefs = requireContext().getSharedPreferences("SmartAssistantPrefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putBoolean("tracking_enabled", true).apply()

                val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    ToastHelper.showCustomToast(requireContext(), "Unable to open usage settings.", 2000L)
                }
            }
            .setNegativeButton("Cancel") {
                // Dimissed automatically
            }
            .setCancelable(false)
            .show()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = requireContext().getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), requireContext().packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), requireContext().packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private var isPendingDialogShowing = false

    private fun checkPendingTransactions() {
        if (isPendingDialogShowing) return
        val prefs = requireContext().getSharedPreferences("PendingTransactionPrefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("has_pending", false)) {
            androidx.core.app.NotificationManagerCompat.from(requireContext()).cancel(999)
            androidx.work.WorkManager.getInstance(requireContext()).cancelUniqueWork("RecoveryNotification")

            val amountStr = prefs.getString("pending_amount", "0") ?: "0"
            val amount = amountStr.toDoubleOrNull()?.toInt() ?: 0
            val category = prefs.getString("pending_category", "no choice") ?: "no choice"
            val title = prefs.getString("pending_title", "") ?: ""
            val upiUri = prefs.getString("pending_upi_uri", "") ?: ""
            val paymentApp = prefs.getString("pending_app", "CRED") ?: "CRED"
            
            val density = resources.displayMetrics.density
            val box = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val p = (28 * density).toInt()
                setPadding(p, p, p, (24 * density).toInt())
                setBackgroundResource(ThemeHelper.getDrawable(requireContext(), R.drawable.bg_transaction))
            }

            val titleView = android.widget.TextView(requireContext()).apply {
                text = "Payment Interrupted?"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
                setTextColor(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (16 * density).toInt())
            }
            box.addView(titleView)

            val introView = android.widget.TextView(requireContext()).apply {
                text = "Please confirm the final status of the transaction mentioned below:"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                setTextColor(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (16 * density).toInt())
                setLineSpacing(8f, 1f)
            }
            box.addView(introView)

            val detailsBox = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (32 * density).toInt())
            }

            val amountView = android.widget.TextView(requireContext()).apply {
                val formattedTitle = title.replace("(?i)^To:\\s*".toRegex(), "").split(" ").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } }
                text = "₹$amount ➔ $formattedTitle"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
                setTextColor(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (4 * density).toInt())
            }
            detailsBox.addView(amountView)

            val categoryView = android.widget.TextView(requireContext()).apply {
                text = "Allocation: $category"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                setTextColor(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                gravity = android.view.Gravity.CENTER
            }
            detailsBox.addView(categoryView)
            box.addView(detailsBox)

            val buttonContainer = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }

            isPendingDialogShowing = true
            val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setView(box).setCancelable(false).create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val btnNo = android.widget.Button(requireContext()).apply {
                text = "Payment Failed"
                isAllCaps = false
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
                background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
                stateListAnimator = null
                elevation = 0f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
                minHeight = 150
                setPadding(30, 30, 30, 30)
                setOnClickListener {
                    isPendingDialogShowing = false
                    val currentPrefs = requireContext().getSharedPreferences("PendingTransactionPrefs", android.content.Context.MODE_PRIVATE)
                    if (!currentPrefs.getBoolean("has_pending", false)) {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    prefs.edit().clear().apply()
                    androidx.core.app.NotificationManagerCompat.from(requireContext()).cancel(999)
                    dialog.dismiss()
                }
            }

            val btnYes = android.widget.Button(requireContext()).apply {
                text = "Payment Success"
                isAllCaps = false
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
                background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
                stateListAnimator = null
                elevation = 0f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
                minHeight = 150
                setPadding(30, 30, 30, 30)
                setOnClickListener {
                    isPendingDialogShowing = false
                    val currentPrefs = requireContext().getSharedPreferences("PendingTransactionPrefs", android.content.Context.MODE_PRIVATE)
                    if (!currentPrefs.getBoolean("has_pending", false)) {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    if (amount > 0) {
                        val ts = System.currentTimeMillis()
                        HistoryDataManager.saveTransaction(requireContext(), title, amount.toFloat(), category, ts)
                        
                        requireContext().getSharedPreferences("ScannerMetadataPrefs", android.content.Context.MODE_PRIVATE).edit()
                            .putString("UPI_$ts", upiUri)
                            .putString("APP_$ts", paymentApp)
                            .apply()
                            
                        FirestoreSyncManager.pushAllDataToCloud(requireContext())
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(android.content.Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))
                    }
                    prefs.edit().clear().apply()
                    androidx.core.app.NotificationManagerCompat.from(requireContext()).cancel(999)
                    dialog.dismiss()
                    refreshUI()
                }
            }

            buttonContainer.addView(btnNo)
            buttonContainer.addView(btnYes)
            box.addView(buttonContainer)

            dialog.show()
        }
    }
}
