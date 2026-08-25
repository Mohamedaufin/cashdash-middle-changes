@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity

class BalanceSetupActivity : ThemedActivity() {

    private val KEY_BALANCE = "wallet_balance"

    private lateinit var tvAmount: TextView
    private lateinit var tvAddHint: TextView
    private lateinit var tvReplaceHint: TextView
    private var currentAmount: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_balance_setup)

        tvAmount = findViewById(R.id.tvAmount)
        tvAddHint = findViewById(R.id.tvAddHint)
        tvReplaceHint = findViewById(R.id.tvReplaceHint)
        val tvCurrentBalance = findViewById<TextView>(R.id.tvCurrentBalance)

        val prefs = WalletStore.get(this)
        val currentBal = prefs.getInt(KEY_BALANCE, 0)
        tvCurrentBalance.text = "Current balance: ₹$currentBal"

        // Check if this is the first time setting up
        val initialBalRaw = prefs.getInt("initial_balance", -1)
        val isFirstTime = initialBalRaw <= 0

        val ivEdit = findViewById<View>(R.id.ivEditBalance)
        ivEdit.setOnClickListener {
            showEditBalanceDialog(tvCurrentBalance)
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { onBackPressed() }

        setupNumpad()

        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val btnReplace = findViewById<Button>(R.id.btnReplace)

        if (isFirstTime) {
            tvCurrentBalance.visibility = View.GONE
            ivEdit.visibility = View.GONE
            btnReplace.visibility = View.GONE
            findViewById<TextView>(R.id.tvChooseLabel).visibility = View.GONE
            findViewById<View>(R.id.hintRow).visibility = View.GONE
            
            btnAdd.text = "Add amount to wallet balance"
            val params = btnAdd.layoutParams as android.widget.LinearLayout.LayoutParams
            params.weight = 2f
            params.marginEnd = 0
            btnAdd.layoutParams = params
        }

        btnAdd.setOnClickListener {
            if (isFirstTime) {
                handleBalanceUpdate(isReplace = false, isPermanent = true, isFirstTime = true)
            } else {
                val schedulePrefs = getSharedPreferences("MoneySchedulePrefs", Context.MODE_PRIVATE)
                val isScheduleSet = schedulePrefs.getInt("schedule_days", -1) > 0

                if (!isScheduleSet) {
                    // No money schedule set, just add permanently without asking about cycles
                    handleBalanceUpdate(isReplace = false, isPermanent = true, isFirstTime = false)
                } else {
                    showAddTypeDialog()
                }
            }
        }

        btnReplace.setOnClickListener {
            handleBalanceUpdate(isReplace = true, isPermanent = true, isFirstTime = false)
        }

        // Apply WindowInsets for edge-to-edge support
        val root = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val numpadContainer = findViewById<View>(R.id.numpadContainer)
            numpadContainer.setPadding(numpadContainer.paddingLeft, numpadContainer.paddingTop, numpadContainer.paddingRight, systemBars.bottom)
            insets
        }
    }

    private fun showAddTypeDialog() {
        val amount = currentAmount.toIntOrNull() ?: 0
        if (amount <= 0) {
            ToastHelper.showToast(this, "Please enter an amount")
            return
        }

        val prefs = WalletStore.get(this)
        val oldBalance = prefs.getInt("initial_balance", 0)
        val newBalance = oldBalance + amount
        
        val actualNextCycle = prefs.getInt("next_cycle_initial_balance", oldBalance)

        val density = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val titleView = android.widget.TextView(this).apply {
            text = "Update Budget?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(ThemeHelper.resolveColorAttr(this@BalanceSetupActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val introView = android.widget.TextView(this).apply {
            text = "Your total wallet balance will be upgraded from $oldBalance to ₹$newBalance.\n\nShould this upgrade apply to all future money reset schedules?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(ThemeHelper.resolveColorAttr(this@BalanceSetupActivity, R.attr.textPrimaryColor))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (32 * density).toInt())
            setLineSpacing(8f, 1f)
        }
        box.addView(introView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun createButton(textStr: String, colorStr: String, action: () -> Unit): android.widget.Button {
            return android.widget.Button(this).apply {
                text = textStr
                isAllCaps = false
                setTextColor(if (colorStr == "primary") ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor) else android.graphics.Color.parseColor(colorStr))
                val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
                background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
                stateListAnimator = null
                elevation = 0f
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, (12 * density).toInt()) }
                minHeight = 150
                setPadding(30, 30, 30, 30)
                setOnClickListener {
                    action()
                    dialog.dismiss()
                }
            }
        }

        box.addView(createButton("Yes (Next cycle total wallet balance - $newBalance)", "primary") { handleBalanceUpdate(isReplace = false, isPermanent = true, isFirstTime = false) })
        box.addView(createButton("No (Next cycle total wallet balance - $actualNextCycle)", "primary") { handleBalanceUpdate(isReplace = false, isPermanent = false, isFirstTime = false) })
        box.addView(createButton("Cancel", "primary") { })

        dialog.show()
    }

    private fun setupNumpad() {
        val clickListener = View.OnClickListener { v ->
            val b = v as Button
            val text = b.text.toString()
            
            if (currentAmount.length < 9) { // Prevent too many digits
                if (currentAmount == "0") currentAmount = text
                else currentAmount += text
                updateAmountDisplay()
            }
        }

        val grid = findViewById<GridLayout>(R.id.numberPad)
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child is Button) {
                when (child.id) {
                    R.id.btnBackspace -> {
                        child.setOnClickListener {
                            if (currentAmount.isNotEmpty()) {
                                currentAmount = currentAmount.dropLast(1)
                                updateAmountDisplay()
                            }
                        }
                    }
                    R.id.btnCancel -> {
                        child.setOnClickListener {
                            currentAmount = ""
                            updateAmountDisplay()
                        }
                    }
                    else -> child.setOnClickListener(clickListener)
                }
            }
        }
    }

    private fun updateAmountDisplay() {
        tvAmount.text = if (currentAmount.isEmpty()) "0" else currentAmount
        
        val amount = currentAmount.toIntOrNull() ?: 0
        if (amount > 0) {
            val prefs = WalletStore.get(this)
            val oldBalance = prefs.getInt(KEY_BALANCE, 0)
            val oldInitial = prefs.getInt("initial_balance", oldBalance)
            
            val newAddBal = oldBalance + amount
            val newAddInit = oldInitial + amount
            tvAddHint.text = "₹$newAddBal / ₹$newAddInit"
            tvReplaceHint.text = "₹$amount / ₹$amount"
            
            tvAddHint.visibility = View.VISIBLE
            tvReplaceHint.visibility = View.VISIBLE
        } else {
            tvAddHint.visibility = View.INVISIBLE
            tvReplaceHint.visibility = View.INVISIBLE
        }
    }

    private fun handleBalanceUpdate(isReplace: Boolean, isPermanent: Boolean, isFirstTime: Boolean = false) {
        val amount = currentAmount.toIntOrNull() ?: 0
        if (amount <= 0) {
            ToastHelper.showToast(this, "Please enter an amount")
            return
        }

        val prefs = WalletStore.get(this)
        val oldBalance = prefs.getInt(KEY_BALANCE, 0)
        val oldInitial = prefs.getInt("initial_balance", oldBalance)
        
        val newBalance = if (isReplace) amount else (oldBalance + amount)
        val newInitial = if (isReplace) amount else (oldInitial + amount)

        val editor = prefs.edit().putInt(KEY_BALANCE, newBalance)
        
        editor.putInt("initial_balance", newInitial)
        if (isPermanent) {
            editor.remove("next_cycle_initial_balance")
        } else {
            val existingNextCycle = prefs.getInt("next_cycle_initial_balance", -1)
            if (existingNextCycle == -1) {
                editor.putInt("next_cycle_initial_balance", oldInitial)
            }
        }
        editor.apply()

        // Sync to Firestore
        FirestoreSyncManager.pushAllDataToCloud(this)

        // Notify UI to refresh (e.g. HomeFragment wallet bar)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(android.content.Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))

        if (isFirstTime) {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("toast_msg", "Wallet Setup Complete ✓")
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        } else {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("toast_msg", if (isReplace) "Balance Replaced ✓" else "Balance Added ✓")
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
        finish()
    }

    private fun showEditBalanceDialog(tvDisplay: TextView) {
        val prefs = WalletStore.get(this)
        val current = prefs.getInt(KEY_BALANCE, 0)

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_balance, null)
        val edt = dialogView.findViewById<EditText>(R.id.edtNewBalance)
        edt.setText(current.toString())
        edt.setSelection(edt.text.length)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.GlassDialogTheme)
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val newVal = edt.text.toString().toIntOrNull() ?: current
            
            prefs.edit()
                .putInt(KEY_BALANCE, newVal)
                .apply()
            
            tvDisplay.text = "Current balance: ₹$newVal"
            FirestoreSyncManager.pushAllDataToCloud(this)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .sendBroadcast(android.content.Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))
            
            ToastHelper.showToast(this, "Balance updated ✓")
            dialog.dismiss()
        }

        dialog.show()
        
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - 100,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val prefs = WalletStore.get(this)
        val isFirstTime = prefs.getInt("initial_balance", -1) <= 0
        
        if (isFirstTime) {
            ToastHelper.showToast(this, "Please set up your wallet balance first")
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentAmount", currentAmount)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentAmount = savedInstanceState.getString("currentAmount", "")
        updateAmountDisplay()
    }
}
