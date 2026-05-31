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
    private val PREFS_WALLET = "WalletPrefs"
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
    
    private val walletListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_BALANCE || key == "initial_balance" || key == "balance_bar_mode" || key == "balance_bar_type") {
            view?.let { loadBalance(it) }
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
        view.findViewById<ImageView>(R.id.btnProfile)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_profile))

        view.findViewById<LinearLayout>(R.id.cardScanner).setOnClickListener {
            animateAndStart(view.findViewById<ImageView>(R.id.iconScanner)) {
                startActivity(Intent(requireContext(), ScannerActivity::class.java))
            }
        }

        view.findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            startActivity(Intent(requireContext(), MenuActivity::class.java))
        }

        view.findViewById<ImageView>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        val walletContainer = view.findViewById<View>(R.id.walletContainer)
        walletContainer?.setOnClickListener {
            startActivity(Intent(requireContext(), BalanceSetupActivity::class.java))
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
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (touchDownInside && distance <= radius) {
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }

        view.findViewById<LinearLayout>(R.id.cardRigor).setOnClickListener {
            animateAndStart(view.findViewById<ImageView>(R.id.iconRigorTracker)) {
                startActivity(Intent(requireContext(), RigorActivity::class.java))
            }
        }


        // Start the postponed transition from MainActivity once this view is ready
        view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                activity?.supportStartPostponedEnterTransition()
                return true
            }
        })
        
        val wPrefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
        wPrefs.registerOnSharedPreferenceChangeListener(walletListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val wPrefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
        wPrefs.unregisterOnSharedPreferenceChangeListener(walletListener)
    }

    override fun onResume() {
        super.onResume()
        refreshUI()

        // Show setup dialog quickly after transition (800ms)
        view?.postDelayed({
            if (isAdded) checkInitialSetup()
        }, 800)
    }

    override fun onPause() {
        super.onPause()
        reminderRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun checkInitialSetup() {
        val density = resources.displayMetrics.density
        val prefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
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
        }
    }

    private fun loadUserName(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, "User") ?: "User"
        if (name != lastLoadedName) {
            view.findViewById<TextView>(R.id.tvUsernameHome)?.text = name
            lastLoadedName = name
        }
    }

    private fun loadBalance(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
        val bal = prefs.getInt(KEY_BALANCE, 0)
        val initialRaw = prefs.getInt("initial_balance", -1)

        val initial = if (initialRaw == -1) 0 else initialRaw
        val displayInitial = if (initial == 0 && bal == 0) 0 else initial.coerceAtLeast(1)
        
        val mode = prefs.getString("balance_bar_mode", "gradient") ?: "gradient"
        val type = prefs.getString("balance_bar_type", "gradient1") ?: "gradient1"
        
        val balanceStr = "₹$bal/$displayInitial"
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
            textView.text = "Next money: schedule not set"
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
                tvResetPending.text = "Reminder set at $formattedTime. Tap here to configure"
                
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
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(context).apply {
            text = "Cycle Reset Due"
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

        val content = TextView(context).apply {
            text = "Your scheduled cycle reset date was on $dateStr. Have you received your money?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, (28 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        box.addView(content)

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

        val btnRemindLater = Button(context).apply {
            text = "Remind Me Later"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, ThemeHelper.getDrawable(context, R.drawable.bg_3d_card))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
        }
        box.addView(btnRemindLater)

        val dialog = AlertDialog.Builder(context)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activeResetDialog = dialog

        btnReset.setOnClickListener {
            dialog.dismiss()
            performCycleReset(nextDate, freq)
        }

        btnRemindLater.setOnClickListener { btnView ->
            showPostponeDropdown(btnView, dialog)
        }

        dialog.show()
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

            val wPrefs = context.getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
            val initialBal = wPrefs.getInt("initial_balance", 0)
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
}
