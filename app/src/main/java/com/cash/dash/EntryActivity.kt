package com.cash.dash
import android.content.Intent
import android.graphics.Color            // <-- added
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.autofill.AutofillManager

class EntryActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private val PREFS = "AppPrefs"
    private val KEY_FIRST = "isFirstLaunch"
    private val KEY_NAME = "user_name"
    private val KEY_EMAIL = "user_email"
    private val KEY_PHONE = "user_phone"

    private var isLoginFlow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // 🔥 If already completed once → skip form forever
        if (!prefs.getBoolean(KEY_FIRST, true)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_entry)
        // 🟦 Fullscreen like other activities
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        auth = FirebaseAuth.getInstance()

        val layoutSelection = findViewById<View>(R.id.layoutSelection)
        val layoutAuthForm = findViewById<View>(R.id.layoutAuthForm)
        
        val btnSelectLogin = findViewById<Button>(R.id.btnSelectLogin)
        val btnSelectRegister = findViewById<Button>(R.id.btnSelectRegister)
        
        val edtName = findViewById<EditText>(R.id.edtName)
        val edtPhone = findViewById<EditText>(R.id.edtPhone)
        val edtEmail = findViewById<EditText>(R.id.edtEmail)
        val edtPassword = findViewById<EditText>(R.id.edtPassword)
        
        val btnAction = findViewById<Button>(R.id.btnAction)
        val tvBack = findViewById<TextView>(R.id.tvBackToSelection)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnSelectLogin.setOnClickListener {
            isLoginFlow = true
            showAuthForm(true, layoutSelection, layoutAuthForm, edtName, edtPhone, edtEmail, edtPassword, btnAction, tvForgotPassword)
        }

        btnSelectRegister.setOnClickListener {
            isLoginFlow = false
            showAuthForm(false, layoutSelection, layoutAuthForm, edtName, edtPhone, edtEmail, edtPassword, btnAction, tvForgotPassword)
        }

        tvBack.setOnClickListener {
            layoutSelection.visibility = View.VISIBLE
            layoutAuthForm.visibility = View.GONE
            tvForgotPassword.visibility = View.GONE
            tvStatus.text = ""
            edtName.text.clear()
            edtPhone.text.clear()
            edtEmail.text.clear()
            edtPassword.text.clear()
            edtName.clearFocus()
            edtPhone.clearFocus()
            edtEmail.clearFocus()
            edtPassword.clearFocus()
        }

        btnAction.setOnClickListener {
            handleAuth(isLoginFlow, edtName, edtPhone, edtEmail, edtPassword, btnAction, tvForgotPassword, progressBar, tvStatus, prefs)
        }

        tvForgotPassword.setOnClickListener {
            val email = edtEmail.text.toString().trim()
            if (email.isEmpty()) {
                tvStatus.text = "Please enter your Email to reset password"
                return@setOnClickListener
            }
            progressBar.visibility = View.VISIBLE
            tvStatus.text = ""
            
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    progressBar.visibility = View.GONE
                    if (task.isSuccessful) {
                        tvStatus.setTextColor(Color.parseColor("#8BF7E6"))
                        tvStatus.text = "Reset link sent to $email. Please check your inbox."
                    } else {
                        tvStatus.setTextColor(Color.parseColor("#FF6B6B"))
                        tvStatus.text = "Failed: ${task.exception?.message}"
                    }
                }
        }
        checkAdminDeletionReason()
    }

    private fun showAuthForm(isLogin: Boolean, selection: View, form: View, edtName: EditText, edtPhone: EditText, edtEmail: EditText, edtPassword: EditText, btnAction: Button, tvForgot: View) {
        edtName.text.clear()
        edtPhone.text.clear()
        edtEmail.text.clear()
        edtPassword.text.clear()
        edtName.clearFocus()
        edtPhone.clearFocus()
        edtEmail.clearFocus()
        edtPassword.clearFocus()

        selection.visibility = View.GONE
        form.visibility = View.VISIBLE
        if (isLogin) {
            edtName.visibility = View.GONE
            edtPhone.visibility = View.GONE
            btnAction.text = "Login"
            tvForgot.visibility = View.VISIBLE

            // 🟢 Restore working Login Autofill
            edtEmail.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            edtPassword.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            edtEmail.setAutofillHints(View.AUTOFILL_HINT_USERNAME) // Matches original XML
            edtPassword.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        } else {
            edtName.visibility = View.VISIBLE
            edtPhone.visibility = View.VISIBLE
            btnAction.text = "Register"
            tvForgot.visibility = View.GONE

            // 🔴 Disable for Register
            edtEmail.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            edtPassword.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            edtEmail.setAutofillHints(null)
            edtPassword.setAutofillHints(null)
        }
    }

    private fun checkAdminDeletionReason() {
        val reason = intent.getStringExtra("reason")
        if (reason == "admin_deleted") {
            showAdminDeletionDialog()
        }
    }

    private fun showAdminDeletionDialog() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            try {
                val dialog = Dialog(this)
                dialog.setContentView(R.layout.dialog_confirm_action)
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dialog.window?.setLayout(
                    resources.displayMetrics.widthPixels - 100,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val tvTitle = dialog.findViewById<TextView>(R.id.tvConfirmTitle)
                val tvMessage = dialog.findViewById<TextView>(R.id.tvConfirmMessage)
                val btnAction = dialog.findViewById<Button>(R.id.btnConfirmAction)
                tvTitle?.text = "Security Notice"
                tvMessage?.text = "Admin has deleted your account due to privacy concerns."
                btnAction?.text = "Understood"
                dialog.findViewById<View>(R.id.btnConfirmCancel)?.visibility = View.GONE
                btnAction?.setOnClickListener {
                    dialog.dismiss()
                }
                if (!isFinishing && !isDestroyed) {
                    dialog.show()
                }
            } catch (e: Exception) {
                // e.printStackTrace()
            }
        }
    }

    private fun handleAuth(
        isLogin: Boolean,
        edtName: EditText,
        edtPhone: EditText,
        edtEmail: EditText,
        edtPassword: EditText,
        btnAction: Button,
        tvForgotPassword: TextView,
        progressBar: ProgressBar,
        tvStatus: TextView,
        prefs: android.content.SharedPreferences
    ) {
        val email = edtEmail.text.toString().trim()
        val pass = edtPassword.text.toString().trim()
        val name = edtName.text.toString().trim()
        val phone = edtPhone.text.toString().trim()

        if (email.isEmpty() || pass.isEmpty()) {
            tvStatus.text = "Please fill in Email and Password"
            return
        }

        if (!isLogin && (name.isEmpty() || phone.isEmpty())) {
            tvStatus.text = "Please fill in all 4 details"
            return
        }

        if (pass.length < 6) {
            tvStatus.text = "Password must be at least 6 characters"
            return
        }

        btnAction.isEnabled = false
        tvForgotPassword.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.setTextColor(Color.parseColor("#FF6B6B"))
        tvStatus.text = ""

        if (isLogin) {
            btnAction.text = "Logging in..."
            auth.signInWithEmailAndPassword(email, pass)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        savePrefsAndContinue(prefs, name, phone, email, pass, isLogin = true, btnAction, tvForgotPassword, progressBar, tvStatus)
                    } else {
                        resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: ${task.exception?.message}", "Login")
                    }
                }
        } else {
            btnAction.text = "Registering..."
            auth.createUserWithEmailAndPassword(email, pass)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        savePrefsAndContinue(prefs, name, phone, email, pass, isLogin = false, btnAction, tvForgotPassword, progressBar, tvStatus)
                    } else {
                        resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Registration Failed: ${task.exception?.message}", "Register")
                    }
                }
        }
    }

    private fun resetUIAfterFailure(btn: Button, tvForgot: TextView, pb: ProgressBar, tvStatus: TextView, message: String, btnText: String) {
        btn.isEnabled = true
        tvForgot.isEnabled = true
        btn.text = btnText
        pb.visibility = View.GONE
        tvStatus.text = message
    }

    private fun savePrefsAndContinue(
        prefs: android.content.SharedPreferences, 
        name: String, phone: String, email: String, pass: String,
        isLogin: Boolean,
        btnAction: Button,
        tvForgotPassword: TextView,
        progressBar: ProgressBar,
        tvStatus: TextView
    ) {
        val editor = prefs.edit()
            .putString(KEY_EMAIL, email)

        if (!isLogin) {
            editor.putBoolean(KEY_FIRST, false)
            editor.putString(KEY_NAME, name)
            editor.putString(KEY_PHONE, phone)
            if (!prefs.contains("account_creation_time")) {
                editor.putLong("account_creation_time", System.currentTimeMillis())
            }
        }
        editor.apply()

        // Tell Android Autofill that the form was submitted successfully
        window.decorView.clearFocus()
        val autofillManager = getSystemService(AutofillManager::class.java)
        autofillManager?.commit()

        if (isLogin) {
            FirestoreSyncManager.pullDataFromCloud(this) { success, _, isAdminDeleted, profileData ->
                if (success && isAdminDeleted) {
                    auth.signOut()
                    prefs.edit().clear().apply()
                    showAdminDeletionDialog()
                    resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "", "Login")
                } else if (success) {
                    // Update local prefs with cloud data (if available) and mark as NOT first launch
                    prefs.edit()
                        .putString(KEY_NAME, profileData?.get("name") as? String ?: "User")
                        .putString(KEY_PHONE, profileData?.get("phone") as? String ?: "")
                        .putBoolean(KEY_FIRST, false)
                        .commit() // 🚀 Mandatory commit for SplashActivity immediate read
                        
                    startActivity(Intent(this, SplashActivity::class.java))
                    finish()
                } else {
                    resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Error: Could not sync with cloud. Check internet.", "Login")
                }
            }
        } else {
            FirestoreSyncManager.pushAllDataToCloud(this)
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        }
    }
}

