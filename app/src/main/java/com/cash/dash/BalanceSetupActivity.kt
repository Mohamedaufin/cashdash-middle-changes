@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity

class BalanceSetupActivity : AppCompatActivity() {

    private val PREFS = "WalletPrefs"
    private val KEY_BALANCE = "wallet_balance"

    private lateinit var tvAmount: TextView
    private var currentAmount: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_balance_setup)

        // Fullscreen setup
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        tvAmount = findViewById(R.id.tvAmount)
        val tvCurrentBalance = findViewById<TextView>(R.id.tvCurrentBalance)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentBal = prefs.getInt(KEY_BALANCE, 0)
        tvCurrentBalance.text = "Current balance: ₹$currentBal"

        // Check if this is the first time setting up
        val initialBalRaw = prefs.getInt("initial_balance", -1)
        val isFirstTime = initialBalRaw <= 0

        val ivEdit = findViewById<View>(R.id.ivEditBalance)
        ivEdit.setOnClickListener {
            showEditBalanceDialog(tvCurrentBalance)
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBackPressed() }

        setupNumpad()

        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val btnReplace = findViewById<Button>(R.id.btnReplace)

        if (isFirstTime) {
            btnReplace.visibility = View.GONE
            findViewById<TextView>(R.id.tvChooseLabel).visibility = View.GONE
            
            btnAdd.text = "Add amount to wallet balance"
            val params = btnAdd.layoutParams as android.widget.LinearLayout.LayoutParams
            params.weight = 2f
            params.marginEnd = 0
            btnAdd.layoutParams = params
        }

        btnAdd.setOnClickListener {
            handleBalanceUpdate(isReplace = false, isFirstTime = isFirstTime)
        }

        btnReplace.setOnClickListener {
            handleBalanceUpdate(isReplace = true, isFirstTime = false)
        }
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
    }

    private fun handleBalanceUpdate(isReplace: Boolean, isFirstTime: Boolean = false) {
        val amount = currentAmount.toIntOrNull() ?: 0
        if (amount <= 0) {
            ToastHelper.showToast(this, "Please enter an amount")
            return
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldBalance = prefs.getInt(KEY_BALANCE, 0)
        val oldInitial = prefs.getInt("initial_balance", oldBalance)
        
        val newBalance = if (isReplace) amount else (oldBalance + amount)
        val newInitial = if (isReplace) amount else (oldInitial + amount)

        prefs.edit()
            .putInt(KEY_BALANCE, newBalance)
            .putInt("initial_balance", newInitial)
            .apply()

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
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isFirstTime = prefs.getInt("initial_balance", -1) <= 0
        
        if (isFirstTime) {
            ToastHelper.showToast(this, "Please set up your wallet balance first")
        } else {
            super.onBackPressed()
        }
    }
}
