package com.cash.dash
import android.content.Intent
import android.graphics.Color            // <-- added
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.view.View
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.core.text.HtmlCompat
import android.widget.CheckBox
import android.widget.LinearLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.TextPaint
import com.google.android.material.bottomsheet.BottomSheetDialog

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.autofill.AutofillManager

class EntryActivity : ThemedActivity() {
    private lateinit var auth: FirebaseAuth
    private val PREFS = "AppPrefs"
    private val KEY_FIRST = "isFirstLaunch"
    private val KEY_NAME = "user_name"
    private val KEY_EMAIL = "user_email"
    private val KEY_PHONE = "user_phone"
    private var selectedDob = ""

    private var isLoginFlow = true

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isLoginFlow", isLoginFlow)
        val formVisible = findViewById<View>(R.id.layoutAuthForm).visibility == View.VISIBLE
        outState.putBoolean("isFormVisible", formVisible)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val formVisible = savedInstanceState.getBoolean("isFormVisible", false)
        if (formVisible) {
            isLoginFlow = savedInstanceState.getBoolean("isLoginFlow", true)
            val layoutSelection = findViewById<View>(R.id.layoutSelection)
            val layoutAuthForm = findViewById<View>(R.id.layoutAuthForm)
            val edtName = findViewById<EditText>(R.id.edtName)
            val edtPhone = findViewById<EditText>(R.id.edtPhone)
            val tvDob = findViewById<TextView>(R.id.tvDob)
            val edtEmail = findViewById<EditText>(R.id.edtEmail)
            val edtPassword = findViewById<EditText>(R.id.edtPassword)
            val btnAction = findViewById<Button>(R.id.btnAction)
            val tvForgot = findViewById<View>(R.id.tvForgotPassword)
            showAuthForm(isLoginFlow, layoutSelection, layoutAuthForm, edtName, edtPhone, tvDob, edtEmail, edtPassword, btnAction, tvForgot, restore = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CashDashApplication.isDeletingAccount = false
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // 🔥 If already logged in (isFirstLaunch=false AND Firebase session valid) → skip form forever
        val alreadyLoggedIn = !prefs.getBoolean(KEY_FIRST, true)
        val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (alreadyLoggedIn && firebaseUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // If isFirstLaunch=false but Firebase session is gone (ghost), clear stale flag and show login
        if (alreadyLoggedIn && firebaseUser == null) {
            prefs.edit().putBoolean(KEY_FIRST, true).apply()
        }
        setContentView(R.layout.activity_entry)

        auth = FirebaseAuth.getInstance()

        val layoutSelection = findViewById<View>(R.id.layoutSelection)
        val layoutAuthForm = findViewById<View>(R.id.layoutAuthForm)

        val btnSelectLogin = findViewById<Button>(R.id.btnSelectLogin)
        val btnSelectRegister = findViewById<Button>(R.id.btnSelectRegister)

        val edtName = findViewById<EditText>(R.id.edtName)
        val edtPhone = findViewById<EditText>(R.id.edtPhone)
        val tvDob = findViewById<TextView>(R.id.tvDob)
        val edtEmail = findViewById<EditText>(R.id.edtEmail)
        val edtPassword = findViewById<EditText>(R.id.edtPassword)
        val cbTerms = findViewById<CheckBox>(R.id.cbTerms)
        val tvTerms = findViewById<TextView>(R.id.tvTerms)
        val layoutTerms = findViewById<LinearLayout>(R.id.layoutTerms)

        val btnAction = findViewById<Button>(R.id.btnAction)
        val tvBack = findViewById<TextView>(R.id.tvBackToSelection)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val ivTogglePassword = findViewById<ImageView>(R.id.ivTogglePassword)

        var isPasswordVisible = false
        ivTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                ivTogglePassword.setImageResource(R.drawable.ic_eye)
                edtPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                ivTogglePassword.setImageResource(R.drawable.ic_eye_off)
                edtPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            edtPassword.typeface = android.graphics.Typeface.DEFAULT
            edtPassword.setSelection(edtPassword.text.length)
        }

        tvDob.setOnClickListener {
            showDobPickerDialog(tvDob)
        }

        setupTermsCheckbox(cbTerms, tvTerms, layoutTerms)

        btnSelectLogin.setOnClickListener {
            isLoginFlow = true
            showAuthForm(true, layoutSelection, layoutAuthForm, edtName, edtPhone, tvDob, edtEmail, edtPassword, btnAction, tvForgotPassword)
        }

        btnSelectRegister.setOnClickListener {
            isLoginFlow = false
            showAuthForm(false, layoutSelection, layoutAuthForm, edtName, edtPhone, tvDob, edtEmail, edtPassword, btnAction, tvForgotPassword)
        }

        tvBack.setOnClickListener {
            val transition = android.transition.AutoTransition()
            transition.duration = 150
            android.transition.TransitionManager.beginDelayedTransition(findViewById(android.R.id.content) as android.view.ViewGroup, transition)
            layoutSelection.visibility = View.VISIBLE
            layoutAuthForm.visibility = View.GONE
            tvForgotPassword.visibility = View.GONE
            layoutTerms.visibility = View.GONE
            tvStatus.text = ""
            edtName.text.clear()
            edtPhone.text.clear()
            edtEmail.text.clear()
            edtPassword.text.clear()
            tvDob.text = ""
            selectedDob = ""
            edtName.clearFocus()
            edtPhone.clearFocus()
            edtEmail.clearFocus()
            edtPassword.clearFocus()
        }

        btnAction.setOnClickListener {
            animateAndStart(btnAction) {
                handleAuth(isLoginFlow, edtName, edtPhone, edtEmail, edtPassword, cbTerms, btnAction, tvForgotPassword, progressBar, tvStatus, prefs)
            }
        }

        tvForgotPassword.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().alpha(0.5f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().alpha(1.0f).setDuration(100).start()
                }
            }
            false
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

    private fun animateAndStart(view: View, action: () -> Unit) {
        view.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(7)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(8)
                    .withEndAction { action() }
                    .start()
            }
            .start()
    }

    private fun showAuthForm(isLogin: Boolean, selection: View, form: View, edtName: EditText, edtPhone: EditText, tvDob: TextView, edtEmail: EditText, edtPassword: EditText, btnAction: Button, tvForgot: View, restore: Boolean = false) {
        val autofillManager = getSystemService(AutofillManager::class.java)

        if (!restore) {
            edtName.text.clear()
            edtPhone.text.clear()
            edtEmail.text.clear()
            edtPassword.text.clear()
            edtName.clearFocus()
            edtPhone.clearFocus()
            edtEmail.clearFocus()
            edtPassword.clearFocus()
        }

        val transition = android.transition.AutoTransition()
        transition.duration = 150
        android.transition.TransitionManager.beginDelayedTransition(findViewById(android.R.id.content) as android.view.ViewGroup, transition)
        selection.visibility = View.GONE
        form.visibility = View.VISIBLE
        if (isLogin) {
            edtName.visibility = View.GONE
            edtPhone.visibility = View.GONE
            tvDob.visibility = View.GONE
            findViewById<View>(R.id.layoutTerms).visibility = View.GONE
            btnAction.text = "Login"
            tvForgot.visibility = View.VISIBLE

            // 🟢 Proper Login Autofill hints
            edtEmail.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            edtPassword.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            edtEmail.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
            edtPassword.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)

            edtName.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            edtPhone.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        } else {
            edtName.visibility = View.VISIBLE
            edtPhone.visibility = View.VISIBLE
            tvDob.visibility = View.VISIBLE
            findViewById<View>(R.id.layoutTerms).visibility = View.VISIBLE
            btnAction.text = "Register"
            tvForgot.visibility = View.GONE

            val service = android.provider.Settings.Secure.getString(contentResolver, "autofill_service")
            val isSamsung = service?.contains("samsung", ignoreCase = true) == true

            if (isSamsung) {
                // Samsung Pass: explicitly define every field
                edtEmail.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                edtPassword.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                edtName.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                edtPhone.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES

                edtName.setAutofillHints("personName")
                edtPhone.setAutofillHints("phone")
                edtEmail.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                edtPassword.setAutofillHints("newPassword")
            } else {
                // GPM: only email + password are credential fields, hide name/phone from autofill
                edtEmail.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                edtPassword.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                edtName.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                edtPhone.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO

                edtName.setAutofillHints("")
                edtPhone.setAutofillHints("")
                edtEmail.setAutofillHints(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS)
                edtPassword.setAutofillHints("newPassword")
            }
        }

        // Apply theme-specific button colors (especially for White Theme Register)
        applyThemedButtonColors(isLogin, btnAction, findViewById(R.id.btnSelectRegister))
    }

    private fun applyThemedButtonColors(isLogin: Boolean, btnAction: Button, btnSelectRegister: Button) {
        if (ThemeHelper.isWhiteTheme(this)) {
            val darkText = Color.parseColor("#1A1A1A")
            btnAction.setTextColor(darkText)
        } else {
            btnAction.setTextColor(Color.WHITE)
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
                tvMessage?.text = "Your account has been deleted due to breach of our security and privacy policies. Register a new account to continue using CashDash."
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

    private var failedLoginAttempts = 0
    private var isLockedOut = false

    private fun handleAuth(
        isLogin: Boolean,
        edtName: EditText,
        edtPhone: EditText,
        edtEmail: EditText,
        edtPassword: EditText,
        cbTerms: CheckBox,
        btnAction: Button,
        tvForgotPassword: TextView,
        progressBar: ProgressBar,
        tvStatus: TextView,
        prefs: android.content.SharedPreferences
    ) {
        // Hide the keyboard automatically if it is active
        edtName.clearFocus()
        edtPhone.clearFocus()
        edtEmail.clearFocus()
        edtPassword.clearFocus()

        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val currentView = currentFocus ?: window.decorView
        imm.hideSoftInputFromWindow(currentView.windowToken, 0)

        if (isLockedOut) {
            tvStatus.text = "Too many failed attempts. Please wait 30 seconds."
            return
        }

        val email = edtEmail.text.toString().trim().lowercase()
        val pass = edtPassword.text.toString().trim()
        val name = edtName.text.toString().trim()
        val phone = edtPhone.text.toString().trim()

        if (email.isEmpty() || pass.isEmpty()) {
            tvStatus.text = "Please fill in Email and Password"
            return
        }

        if (!isLogin && (name.isEmpty() || phone.isEmpty() || selectedDob.isEmpty())) {
            tvStatus.text = "Please fill in all 5 details"
            return
        }

        if (!isLogin && !cbTerms.isChecked) {
            tvStatus.text = "You must agree to the Terms of Service and Privacy Policy to register"
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
                        failedLoginAttempts = 0
                        savePrefsAndContinue(prefs, name, phone, email, pass, isLogin = true, btnAction, tvForgotPassword, progressBar, tvStatus)
                    } else {
                        failedLoginAttempts++
                        var errorMsg = task.exception?.message ?: "Unknown error"

                        if (errorMsg.contains("blocked all requests", ignoreCase = true) || errorMsg.contains("unusual activity", ignoreCase = true)) {
                            errorMsg = "Account temporarily locked by security. Please reset password or wait 15 mins."
                            resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: $errorMsg", "Login")
                        } else if (failedLoginAttempts >= 5) {
                            isLockedOut = true
                            errorMsg = "Too many failed attempts. Please wait 30 seconds."
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                isLockedOut = false
                                failedLoginAttempts = 0
                                if (tvStatus.text.contains("wait 30 seconds")) {
                                    tvStatus.text = "You can try logging in again."
                                    tvStatus.setTextColor(Color.parseColor("#8BF7E6"))
                                }
                            }, 30000)
                            resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: $errorMsg", "Login")
                        } else if (task.exception is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                            // The user does not exist in Auth. Check if they were recently deleted.
                            tvStatus.text = "Checking account status..."
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("deleted_accounts").document(email).get()
                                .addOnCompleteListener { dbTask ->
                                    if (dbTask.isSuccessful && dbTask.result?.exists() == true) {
                                        // Account was deleted!
                                        resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Security Notice: This account was deleted. You must register a new account.", "Login")
                                    } else {
                                        errorMsg = "User not found or deleted. Please register."
                                        resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: $errorMsg", "Login")
                                    }
                                }
                        } else {
                            errorMsg = "Incorrect Email or Password. (${5 - failedLoginAttempts} tries left)"
                            resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: $errorMsg", "Login")
                        }
                    }
                }
        } else {
            btnAction.text = "Registering..."
            auth.createUserWithEmailAndPassword(email, pass)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        savePrefsAndContinue(prefs, name, phone, email, pass, isLogin = false, btnAction, tvForgotPassword, progressBar, tvStatus)
                    } else {
                        val exception = task.exception
                        if (exception is com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                            // "Email already in use" — could be a zombie account left from a failed ghost wipe
                            // Try signing in to check if the profile was wiped
                            tvStatus.text = "Checking account status..."
                            auth.signInWithEmailAndPassword(email, pass)
                                .addOnCompleteListener { signInTask ->
                                    if (signInTask.isSuccessful) {
                                        // Sign-in worked — check Firestore for a valid profile
                                        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                        db.collection("users").document(email)
                                            .collection("config").document("profile")
                                            .get()
                                            .addOnCompleteListener { profileTask ->
                                                val profileDoc = if (profileTask.isSuccessful) profileTask.result else null
                                                val status = profileDoc?.getString("account_status") ?: ""

                                                // Only a POSITIVE marker counts as a zombie. This previously also
                                                // treated a missing or unreadable profile doc as proof, so a user
                                                // whose profile write failed mid-registration — or whose read simply
                                                // failed — had their real Firebase Auth account deleted on the next
                                                // registration attempt. A failed read is never grounds for deletion.
                                                val isZombie = profileTask.isSuccessful &&
                                                        profileDoc != null &&
                                                        profileDoc.exists() &&
                                                        status == "admin_deleted"

                                                if (isZombie) {
                                                    // Zombie account — delete Auth entry and re-register fresh
                                                    tvStatus.text = "Recovering account..."
                                                    auth.currentUser?.delete()?.addOnCompleteListener { deleteTask ->
                                                        auth.signOut()
                                                        // Retry registration with a clean slate
                                                        auth.createUserWithEmailAndPassword(email, pass)
                                                            .addOnCompleteListener { retryTask ->
                                                                if (retryTask.isSuccessful) {
                                                                    savePrefsAndContinue(prefs, name, phone, email, pass, isLogin = false, btnAction, tvForgotPassword, progressBar, tvStatus)
                                                                } else {
                                                                    resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Registration Failed: ${retryTask.exception?.message}", "Register")
                                                                }
                                                            }
                                                    }
                                                } else {
                                                    // Real collision — someone else owns this email
                                                    auth.signOut() // Important: sign out of the other user's account!
                                                    resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Email already in use. Please use a different email or login.", "Register")
                                                }
                                            }
                                    } else {
                                        // Wrong password — genuine collision, not a zombie
                                        resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Email already in use. Please use a different email or login.", "Register")
                                    }
                                }
                        } else {
                            resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Registration Failed: ${exception?.message}", "Register")
                        }
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
        // 🚨 CRITICAL SANITIZATION: Thoroughly purge stale state from previous device users
        // so new registrations don't inherit ghosts of older local caches.
        val prefsToPurge = listOf(
            "WalletPrefs", "CategoryPrefs", "GraphData",
            "CategoryWeekData", "MoneySchedulePrefs", "ScannerHistory",
            "LocalScanPrefs", "ScannerMetadataPrefs", "NotificationCache", "AppPrefs" // Cleared carefully below
        )

        // Capture ThemePrefs if needed? Actually we probably want to keep theme? No, clear state.
        prefsToPurge.forEach { prefName ->
            getSharedPreferences(prefName, MODE_PRIVATE).edit().clear().apply()
        }

        // Fully nuke Room SQL Database to destroy lingering transaction logs
        val context = this.applicationContext
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            try {
                AppDatabase.getDatabase(context).clearAllTables()
            } catch (e: Exception) {
                // Room database might lock, safe ignore as next write will force override
            }
        }

        // Re-obtain clear preferences reference just to be explicitly distinct
        val finalPrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val editor = finalPrefs.edit()
            .putString(KEY_EMAIL, email)

        if (!isLogin) {
            // Register logic: Setup not done yet, so Splash routes back into HomeFragment's balance setup logic
            editor.putBoolean(KEY_FIRST, false)
            editor.putString(KEY_NAME, name)
            editor.putString(KEY_PHONE, phone)
            editor.putString("user_dob", selectedDob)
            editor.putLong("account_creation_time", System.currentTimeMillis())

            val themePrefs = getSharedPreferences("ThemePrefs", MODE_PRIVATE)
            themePrefs.edit().putString("current_theme", "System").apply()
        }
        editor.apply()

        // Tell Android Autofill that the form was submitted successfully
        window.decorView.clearFocus()
        val autofillManager = getSystemService(AutofillManager::class.java)
        autofillManager?.commit()

        if (isLogin) {
            FirestoreSyncManager.pullDataFromCloud(this) { success, _, isAdminDeleted, profileData ->
                if (success && isAdminDeleted) {
                    val user = auth.currentUser
                    val email = user?.email
                    if (user != null && email != null) {
                        CashDashApplication.setOfflineImmediate(this@EntryActivity)
                        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        val batch = db.batch()

                        val docs = listOf("profile", "wallet", "categories", "history", "analytics", "history_scanner", "undo_details", "scanner_metadata", "finminder", "upi_allocations")
                        docs.forEach { docName ->
                            batch.delete(db.collection("users").document(email).collection("config").document(docName))
                        }

                        db.collection("users").document(email).collection("notifications").get().addOnCompleteListener { task ->
                            if (task.isSuccessful && task.result != null) {
                                for (doc in task.result.documents) {
                                    batch.delete(doc.reference)
                                }
                            }
                            batch.delete(db.collection("users").document(email))
                            batch.delete(db.collection("deleted_accounts").document(email))

                            val safeEmail = email.replace(".", ",")
                            com.google.firebase.database.FirebaseDatabase.getInstance().getReference("status").child(safeEmail).removeValue()

                            batch.commit().addOnCompleteListener {
                                user.delete().addOnCompleteListener {
                                    auth.signOut()
                                    prefs.edit().clear().apply()
                                    resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: Incorrect Email or Password.", "Login")
                                }
                            }
                        }
                    } else {
                        auth.signOut()
                        prefs.edit().clear().apply()
                        resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Login Failed: Incorrect Email or Password.", "Login")
                    }
                } else if (success) {
                    // Update local prefs with cloud data (if available) and mark as NOT first launch
                    prefs.edit()
                        .putString(KEY_NAME, profileData?.get("name") as? String ?: "User")
                        .putString(KEY_PHONE, profileData?.get("phone") as? String ?: "")
                        .putBoolean(KEY_FIRST, false)
                        .commit() // 🚀 Mandatory commit for SplashActivity immediate read

                    CashDashApplication.setupRealtimePresence(this@EntryActivity)
                    startActivity(Intent(this, SplashActivity::class.java))
                    finish()
                } else {
                    resetUIAfterFailure(btnAction, tvForgotPassword, progressBar, tvStatus, "Error: Could not sync with cloud. Check internet.", "Login")
                }
            }
        } else {
            // REGISTRATION PATH: New user - no cloud data to pull yet.
            // Directly write the profile to Firestore and proceed.
            val user = auth.currentUser
            val regEmail = user?.email ?: email
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()

            val profileData = mapOf(
                "name" to name,
                "phone" to phone,
                "email" to regEmail,
                "dob" to selectedDob,
                "setup_complete" to false, // wallet not set up yet
                "account_status" to "active",
                "wallet_popup_shown" to false,
                "account_creation_time" to finalPrefs.getLong("account_creation_time", System.currentTimeMillis()),
                "last_local_modification" to System.currentTimeMillis()
            )

            db.collection("users").document(regEmail).collection("config").document("profile")
                .set(profileData)
                .addOnCompleteListener { task ->
                    // Mark sync as complete so pushAllDataToCloud is not blocked
                    FirestoreSyncManager.isInitialSyncCompleted = true

                    prefs.edit().putBoolean(KEY_FIRST, false).commit()
                    CashDashApplication.setupRealtimePresence(this@EntryActivity)
                    startActivity(Intent(this, SplashActivity::class.java))
                    finish()
                }
        }
    }

    private fun showDobPickerDialog(tvDob: TextView) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, ThemeHelper.getBottomSheetTheme(this))
        val sheetView = layoutInflater.inflate(R.layout.layout_dob_bottom_sheet, null)
        dialog.setContentView(sheetView)

        val pickerYear = sheetView.findViewById<android.widget.NumberPicker>(R.id.pickerYear)
        val pickerMonth = sheetView.findViewById<android.widget.NumberPicker>(R.id.pickerMonth)
        val pickerDay = sheetView.findViewById<android.widget.NumberPicker>(R.id.pickerDay)
        val btnSave = sheetView.findViewById<android.widget.Button>(R.id.btnSaveDate)

        // Setup Year
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        pickerYear.minValue = 1900
        pickerYear.maxValue = currentYear

        // Setup Month
        pickerMonth.minValue = 1
        pickerMonth.maxValue = 12
        val monthNames = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        pickerMonth.displayedValues = monthNames

        // Setup Day range helper
        fun updateMaxDay(y: Int, m: Int) {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.YEAR, y)
            cal.set(java.util.Calendar.MONTH, m - 1)
            val maxDay = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            pickerDay.minValue = 1
            pickerDay.maxValue = maxDay
        }

        var startYear = 2000
        var startMonth = 6
        var startDay = 15

        val currentText = tvDob.text.toString().trim()
        if (currentText.isNotEmpty() && !currentText.equals("Date of Birth", ignoreCase = true)) {
            try {
                val parts = currentText.split(" ")
                if (parts.size == 3) {
                    startDay = parts[0].toInt()
                    val mName = parts[1]
                    startYear = parts[2].toInt()
                    val monthIndex = monthNames.indexOfFirst { it.startsWith(mName, ignoreCase = true) }
                    if (monthIndex != -1) {
                        startMonth = monthIndex + 1
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors, use default
            }
        }

        pickerYear.value = startYear
        pickerMonth.value = startMonth
        updateMaxDay(startYear, startMonth)
        pickerDay.value = startDay

        // Value change listeners to dynamically update maximum day
        pickerYear.setOnValueChangedListener { _, _, newVal ->
            updateMaxDay(newVal, pickerMonth.value)
        }
        pickerMonth.setOnValueChangedListener { _, _, newVal ->
            updateMaxDay(pickerYear.value, newVal)
        }

        // Theme text coloring for NumberPickers
        val textColor = ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor)
        fun customizePicker(picker: android.widget.NumberPicker) {
            val count = picker.childCount
            for (i in 0 until count) {
                val child = picker.getChildAt(i)
                if (child is android.widget.EditText) {
                    try {
                        child.setTextColor(textColor)
                        child.invalidate()
                    } catch (e: Exception) {}
                }
            }
        }
        customizePicker(pickerYear)
        customizePicker(pickerMonth)
        customizePicker(pickerDay)

        btnSave.setOnClickListener {
            val year = pickerYear.value
            val month = pickerMonth.value
            val day = pickerDay.value
            val formattedDate = String.format("%02d %s %d", day, monthNames[month - 1].substring(0, 3), year)
            tvDob.text = formattedDate
            selectedDob = formattedDate
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupTermsCheckbox(cbTerms: CheckBox, tvTerms: TextView, layoutTerms: LinearLayout) {
        val isWhiteTheme = ThemeHelper.isWhiteTheme(this)

        val checkboxColor = if (isWhiteTheme) {
            Color.parseColor("#1A1A1A") // Black background for white theme
        } else {
            Color.WHITE // White background for black theme
        }
        cbTerms.buttonTintList = android.content.res.ColorStateList.valueOf(checkboxColor)

        val primaryColor = ThemeHelper.resolveColorAttr(this, androidx.appcompat.R.attr.colorPrimary)
        val linkColor = if (isWhiteTheme) {
            Color.parseColor("#1A73E8") // Blue link color for white theme
        } else {
            primaryColor
        }

        val text = "I agree to the Terms of Service and Privacy Policy"
        val spannable = SpannableString(text)

        val termsClickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val termsText = applicationContext.assets.open("terms_of_service.html").bufferedReader().use { it.readText() }
                showLegalBottomSheet("Terms of Service", termsText)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = linkColor
                ds.isUnderlineText = true
                ds.isFakeBoldText = true
            }
        }

        val privacyClickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val privacyText = applicationContext.assets.open("privacy_policy.html").bufferedReader().use { it.readText() }
                showLegalBottomSheet("Privacy Policy", privacyText)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = linkColor
                ds.isUnderlineText = true
                ds.isFakeBoldText = true
            }
        }

        val termsStart = text.indexOf("Terms of Service")
        spannable.setSpan(termsClickable, termsStart, termsStart + "Terms of Service".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val privacyStart = text.indexOf("Privacy Policy")
        spannable.setSpan(privacyClickable, privacyStart, privacyStart + "Privacy Policy".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        tvTerms.text = spannable
        tvTerms.highlightColor = Color.TRANSPARENT
        tvTerms.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    private fun showLegalBottomSheet(title: String, content: String) {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_legal, null)
        bottomSheetDialog.setContentView(view)

        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        
        val theme = ThemeHelper.getCurrentTheme(this)
        val bgColor = when (theme) {
            "White" -> Color.WHITE
            "Blue" -> Color.parseColor("#010A43") // Matches center color of bg_main_gradient_blue
            else -> Color.parseColor("#0C0C0F") // Perfectly matches app_bg
        }
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(bgColor)
            val r = 26f * resources.displayMetrics.density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        view.background = shape

        val tvTitle = view.findViewById<TextView>(R.id.tvLegalTitle)
        val tvContent = view.findViewById<TextView>(R.id.tvLegalContent)
        val btnClose = view.findViewById<Button>(R.id.btnLegalClose)

        tvTitle.text = title
        tvContent.text = HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_COMPACT)

        btnClose.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }
}
