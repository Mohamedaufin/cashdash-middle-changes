package com.cash.dash

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.util.TypedValue
import java.text.SimpleDateFormat

class MoneyScheduleActivity : ThemedActivity() {

    private val PREFS = "MoneySchedulePrefs"
    private val KEY_FREQUENCY = "frequency"
    private val KEY_NEXT_DATE = "next_date"

    private var selectedDateMillis: Long = -1
    private lateinit var tvCyclePreview: TextView
    private lateinit var etCustomDays: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_moneyschedule)



            val rgFrequency = findViewById<RadioGroup>(R.id.rgFrequency)
            val calendarView = findViewById<CalendarView>(R.id.calendarView)
            val btnSave = findViewById<Button>(R.id.btnSaveSchedule)
            tvCyclePreview = findViewById(R.id.tvCyclePreview)
            etCustomDays = findViewById(R.id.etCustomDays)
            
            val tvCycleSubTitle = findViewById<TextView>(R.id.tvCycleSubTitle)
            val wPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
            val budget = wPrefs.getInt("initial_balance", 0)
            tvCycleSubTitle.text = "Select when your current budget (₹$budget) should expire"

            val tvResetFrequencySubTitle = findViewById<TextView>(R.id.tvResetFrequencySubTitle)
            tvResetFrequencySubTitle.text = "How often your wallet automatically refill to ₹$budget / ₹$budget"

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

            // -------------------------------------------
            // 1️⃣ Load existing saved frequency + date
            // -------------------------------------------
            val savedFrequency = prefs.getInt(KEY_FREQUENCY, -1)
            val savedDate = prefs.getLong(KEY_NEXT_DATE, -1)

            // Pre-select frequency radio button
            when (savedFrequency) {
                30 -> rgFrequency.check(R.id.rbMonthly)
                7 -> rgFrequency.check(R.id.rbWeekly)
                else -> if (savedFrequency > 0) {
                    rgFrequency.check(R.id.rbCustom)
                    etCustomDays.visibility = View.VISIBLE
                    etCustomDays.setText(savedFrequency.toString())
                }
            }

            // 🔧 FIX: Handle 0 (Epoch) explicitly which leaked randomly as Jan 1970
            if (savedDate > 0) {
                calendarView.date = savedDate
                selectedDateMillis = savedDate
            } else {
                selectedDateMillis = Calendar.getInstance().timeInMillis
            }
            
            // Disable past dates
            val todayMidnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            calendarView.minDate = todayMidnight.timeInMillis

            // Handle custom radio
            rgFrequency.setOnCheckedChangeListener { _, checkedId ->
                if (checkedId == R.id.rbCustom) {
                    etCustomDays.visibility = View.VISIBLE
                } else {
                    etCustomDays.visibility = View.GONE
                }
                updateCyclePreview(rgFrequency)
            }

            etCustomDays.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateCyclePreview(rgFrequency) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })


            // -------------------------------------------
            // 2️⃣ Capture new date when user selects a new one
            // -------------------------------------------
            calendarView.setOnDateChangeListener { _, year, month, day ->
                val cal = Calendar.getInstance()
                cal.set(year, month, day)
                selectedDateMillis = cal.timeInMillis
                updateCyclePreview(rgFrequency)
            }

            // Initial preview
            updateCyclePreview(rgFrequency)

            val btnResetNow = findViewById<Button>(R.id.btnResetNow)

            btnSave.setOnClickListener {
                val freqId = rgFrequency.checkedRadioButtonId
                if (freqId == -1) {
                    ToastHelper.showToast(this, "Select a frequency")
                    return@setOnClickListener
                }
                if (selectedDateMillis <= 0) {
                    ToastHelper.showToast(this, "Select next receiving date")
                    return@setOnClickListener
                }

                val frequencyDays = when (freqId) {
                    R.id.rbMonthly -> 30
                    R.id.rbWeekly -> 7
                    R.id.rbCustom -> {
                        val customVal = etCustomDays.text.toString().toIntOrNull() ?: 0
                        if (customVal <= 0) {
                            ToastHelper.showToast(this@MoneyScheduleActivity, "Enter valid custom days")
                            return@setOnClickListener
                        }
                        customVal
                    }
                    else -> 30
                }

                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val next = Calendar.getInstance().apply {
                    timeInMillis = selectedDateMillis
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }

                if (next.before(today)) {
                    while (next.before(today)) {
                        next.add(Calendar.DAY_OF_YEAR, frequencyDays)
                    }
                    selectedDateMillis = next.timeInMillis
                }

                prefs.edit()
                    .putInt(KEY_FREQUENCY, frequencyDays)
                    .putLong(KEY_NEXT_DATE, selectedDateMillis)
                    .putBoolean("cycle_initialized", true)
                    .apply()

                ToastHelper.showToast(this, "Schedule updated")
                finish()
            }

            btnResetNow.setOnClickListener {
                showResetConfirmationDialog()
            }

            // Apply WindowInsets for edge-to-edge support
            val rootGroup = findViewById<View>(android.R.id.content) as? android.view.ViewGroup
            val root = rootGroup?.getChildAt(0)
            if (root != null) {
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
                    insets
                }
            }
        } catch (e: Exception) {
            ToastHelper.showToast(this, "Error starting Schedule: ${e.message}")
            android.util.Log.e("MoneyScheduleActivity", "Error during onCreate", e)
            finish()
        }
    }

    private fun executeManualReset() {
        // Reset ALL category spent amounts for the new cycle (Fresh Start)
        val categoryPrefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val categories = categoryPrefs.getStringSet("categories", emptySet()) ?: emptySet()
        val graphPrefs = getSharedPreferences("GraphData", MODE_PRIVATE)
        val graphEditor = graphPrefs.edit()
        for (cat in categories) {
            graphEditor.putFloat("SPENT_$cat", 0f)
        }
        graphEditor.putFloat("SPENT_no choice", 0f)
        graphEditor.apply()

        // Replenish wallet balance to initial limit
        val wPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
        val nextCycleBal = wPrefs.getInt("next_cycle_initial_balance", -1)
        val initialBal = if (nextCycleBal != -1) {
            wPrefs.edit().putInt("initial_balance", nextCycleBal).remove("next_cycle_initial_balance").apply()
            nextCycleBal
        } else {
            wPrefs.getInt("initial_balance", 0)
        }
        wPrefs.edit().putInt("wallet_balance", initialBal).apply()

        // Sync to Firestore
        FirestoreSyncManager.pushAllDataToCloud(this)
    }

    private fun updateCyclePreview(rg: RadioGroup) {
        val freqId = rg.checkedRadioButtonId
        // 🔧 FIX: Guard against negative and epoch zeroes
        if (freqId == -1 || selectedDateMillis <= 0) {
            tvCyclePreview.visibility = View.GONE
            return
        }

        tvCyclePreview.post {
            val days = when (freqId) {
                R.id.rbMonthly -> 30
                R.id.rbWeekly -> 7
                R.id.rbCustom -> etCustomDays.text.toString().toIntOrNull() ?: 0
                else -> 0
            }

            if (days <= 0) {
                tvCyclePreview.visibility = View.GONE
                return@post
            }

            val sdf = java.text.SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            
            val startCal1 = Calendar.getInstance().apply { 
                timeInMillis = selectedDateMillis
                add(Calendar.DAY_OF_YEAR, 1) 
            }
            val endCal1 = Calendar.getInstance().apply {
                timeInMillis = startCal1.timeInMillis
                add(Calendar.DAY_OF_YEAR, days - 1)
            }

            val startCal2 = Calendar.getInstance().apply {
                timeInMillis = endCal1.timeInMillis
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val endCal2 = Calendar.getInstance().apply {
                timeInMillis = startCal2.timeInMillis
                add(Calendar.DAY_OF_YEAR, days - 1)
            }

            val startStr1 = sdf.format(startCal1.time)
            val endStr1 = sdf.format(endCal1.time)
            val startStr2 = sdf.format(startCal2.time)
            val endStr2 = sdf.format(endCal2.time)
            
            tvCyclePreview.text = "Next 2 cycles:\n• $startStr1 — $endStr1\n• $startStr2 — $endStr2"
            tvCyclePreview.setLineSpacing(6 * resources.displayMetrics.density, 1f)
            tvCyclePreview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            tvCyclePreview.visibility = View.VISIBLE
        }
    }

    private fun showResetConfirmationDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Reset Cycle Now?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val content = TextView(this).apply {
            text = "This will instantly refill your wallet and set all allocation spending bars to ₹0."
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, (28 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        box.addView(content)

        val btnReset = android.widget.Button(this).apply {
            text = "Reset Now"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_3d_card))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
        }
        box.addView(btnReset)

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 30) }
        box.addView(spacer)

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_3d_card))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
        }
        box.addView(btnCancel)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnReset.setOnClickListener {
            val frequencyDays = prefs.getInt(KEY_FREQUENCY, 30)
            val next = Calendar.getInstance()
            next.add(Calendar.DAY_OF_YEAR, frequencyDays)
            
            prefs.edit()
                .putLong(KEY_NEXT_DATE, next.timeInMillis)
                .putBoolean("cycle_initialized", true)
                .apply()

            executeManualReset()
            ToastHelper.showToast(this, "Cycle reset successfully!")
            dialog.dismiss()
            finish()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showResetInfoDialog() {
        val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
        val initialBal = walletPrefs.getInt("initial_balance", 0)

        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Cycle Reset Info"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val content = TextView(this).apply {
            text = "On a cycle reset:\n\n" +
                   "• All allocation spent bar are turned to ₹0\n" +
                   "• Wallet balance will be reset to ₹$initialBal/$initialBal"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, (32 * density).toInt())
        }
        box.addView(content)

        val btnOk = android.widget.Button(this).apply {
            text = "Got it"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
            setOnClickListener { } // Dismissed by builder
        }
        box.addView(btnOk)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

}
