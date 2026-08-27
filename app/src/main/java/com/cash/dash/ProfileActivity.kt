@file:Suppress("DEPRECATION")
package com.cash.dash

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore


class ProfileActivity : ThemedActivity() {

    private val PREFS = "AppPrefs"
    private val KEY_NAME = "user_name"
    private val KEY_PHONE = "user_phone"
    private val KEY_EMAIL = "user_email"
    private var selectedDob = ""

    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val originalName = prefs.getString(KEY_NAME, "") ?: ""
            val originalPhone = prefs.getString(KEY_PHONE, "") ?: ""
            val originalEmail = prefs.getString(KEY_EMAIL, "") ?: ""
            val originalDob = prefs.getString("user_dob", "") ?: ""
            
            val edtName = findViewById<EditText>(R.id.edtName)
            val edtPhone = findViewById<EditText>(R.id.edtPhone)
            val edtEmail = findViewById<EditText>(R.id.edtEmail)
            val tvDob = findViewById<TextView>(R.id.tvDob)

            if (!edtName.isFocused) edtName.setText(originalName)
            if (!edtPhone.isFocused) edtPhone.setText(originalPhone)
            if (!edtEmail.isFocused) edtEmail.setText(originalEmail)
            selectedDob = originalDob
            tvDob.text = dobLine(selectedDob)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val edtName = findViewById<EditText>(R.id.edtName)
        val edtPhone = findViewById<EditText>(R.id.edtPhone)
        val edtEmail = findViewById<EditText>(R.id.edtEmail)
        val btnChangePassword = findViewById<Button>(R.id.btnChangePassword)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val btnDeleteAccount = findViewById<Button>(R.id.btnDeleteAccount)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val tvDob = findViewById<TextView>(R.id.tvDob)

        val originalName = prefs.getString(KEY_NAME, "") ?: ""
        val originalPhone = prefs.getString(KEY_PHONE, "") ?: ""
        val originalEmail = prefs.getString(KEY_EMAIL, "") ?: ""
        val originalDob = prefs.getString("user_dob", "") ?: ""

        edtName.setText(originalName)
        edtPhone.setText(originalPhone)
        edtEmail.setText(originalEmail)
        selectedDob = originalDob
        tvDob.text = dobLine(selectedDob)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentName = edtName.text.toString().trim()
                val currentPhone = edtPhone.text.toString().trim()
                val currentEmail = edtEmail.text.toString().trim().lowercase()
                val currentDob = selectedDob

                if (currentName != originalName || currentPhone != originalPhone || currentEmail != originalEmail || currentDob != originalDob) {
                    AlertDialogHelper.createFlatDialogBuilder(this@ProfileActivity)
                        .setTitle("Unsaved Changes")
                        .setMessage("Do you want to save changes to your profile?")
                        .setPositiveButton("Save") {
                            btnSave.performClick()
                        }
                        .setNeutralButton("Discard") {
                            finish()
                        }
                        .setNegativeButton("Cancel")
                        .show()
                } else {
                    finish()
                }
            }
        })

        // Display only -- selectedDob keeps the raw "02 Oct 2004" that gets saved and
        // synced. Appending the age to the field text instead would put it into
        // user_dob on the next save.
        // Date of birth and gender are set once, at registration or via the lock sheet,
        // and neither is editable here. The picker sheet says "These are permanent and
        // cannot be changed" at the point of entry, so this has to hold or that promise
        // is a lie. Kept tappable on purpose: a field that silently ignores a tap reads
        // as a bug, so explain instead. Dimmed to signal it is not an input.
        tvDob.alpha = 0.6f
        tvDob.setOnClickListener {
            ToastHelper.showToast(this, "Date of birth and gender are permanent and cannot be changed.")
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        btnChangePassword.setOnClickListener {
            showChangePasswordDialog()
        }

        // Admin entry point moved to MenuActivity

        btnSave.setOnClickListener {
            val name = edtName.text.toString().trim()
            val phone = edtPhone.text.toString().trim()
            val newEmail = edtEmail.text.toString().trim().lowercase()
            val user = FirebaseAuth.getInstance().currentUser

            if(name.isEmpty()){
                ToastHelper.showToast(this@ProfileActivity, "Enter name")
                return@setOnClickListener
            }

            if (newEmail.isEmpty()) {
                ToastHelper.showToast(this@ProfileActivity, "Enter email")
                return@setOnClickListener
            }

            if (user != null && user.email != null && newEmail != user.email) {
                val oldEmail = user.email!!
                btnSave.isEnabled = false
                btnSave.text = "Checking..."

                user.updateEmail(newEmail).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        prefs.edit().apply {
                            putString(KEY_NAME, name)
                            putString(KEY_PHONE, phone)
                            putString("user_dob", selectedDob)
                            putString(KEY_EMAIL, newEmail)
                            apply()
                        }

                        // Wipe old firestore document so we don't leave orphaned data
                        wipeUserFirestoreData(user.uid, oldEmail)

                        FirestoreSyncManager.pushAllDataToCloud(this@ProfileActivity)
                        ToastHelper.showToast(this@ProfileActivity, "Profile Updated ✔")
                        finish()
                    } else {
                        btnSave.isEnabled = true
                        btnSave.text = "Save"
                        val error = task.exception?.message ?: "Unknown error"
                        if (error.contains("already in use", ignoreCase = true) || error.contains("collision", ignoreCase = true)) {
                            ToastHelper.showToast(this@ProfileActivity, "This Email ID already exists!")
                        } else if (error.contains("recent login", ignoreCase = true) || error.contains("recent authentication", ignoreCase = true)) {
                            ToastHelper.showToast(this@ProfileActivity, "Security: Please logout and login again to change email.")
                        } else {
                            ToastHelper.showToast(this@ProfileActivity, "Failed: $error")
                        }
                    }
                }
            } else {
                prefs.edit().apply {
                    putString(KEY_NAME, name)
                    putString(KEY_PHONE, phone)
                    putString("user_dob", selectedDob)
                    putString(KEY_EMAIL, newEmail)
                    apply()
                }

                FirestoreSyncManager.pushAllDataToCloud(this@ProfileActivity)
                ToastHelper.showToast(this@ProfileActivity, "Profile Updated ✔")

                // Finish activity to go back
                finish()
            }
        }

        btnLogout.setOnClickListener {
            showConfirmDialog(
                title = "Logout Action",
                message = "Are you sure you want to log out of your session on this device?",
                actionText = "Logout"
            ) {
                // 0. Stop automatic sync listeners BEFORE clearing data
                FirestoreSyncManager.stopRealTimeSync(this@ProfileActivity)
                SecurityManager.stopListening()

                // 1. Clear ALL local data to prevent leak between accounts
                val prefsToClear = listOf(
                    "AppPrefs", "WalletPrefs", "WalletPrefs_v2", "CategoryPrefs",
                    "GraphData", "CategoryWeekData", "MoneySchedulePrefs",
                    "ScannerHistory", "LocalScanPrefs", "LocalScanPrefs_v2", "NotificationCache"
                )
                prefsToClear.forEach { name ->
                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                }
                // See SecurePrefsStore.wipe — clearing these as plain prefs corrupts them.
                WalletStore.wipe(this@ProfileActivity)
                ScanStore.wipe(this@ProfileActivity)

                // 2. Sign out & mark offline immediately
                CashDashApplication.setOfflineImmediate(this@ProfileActivity)
                FirebaseAuth.getInstance().signOut()

                // 3. Navigate back to login
                val i = Intent(this@ProfileActivity, EntryActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(i)
            }
        }

        btnDeleteAccount.setOnClickListener {
            showConfirmDialog(
                title = "Delete Account",
                message = "This action is irreversible. Your account will be deleted from CashDash servers immediately.",
                actionText = "Delete"
            ) {
                performAccountDeletion()
            }
        }
    }

    private fun performAccountDeletion() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return
        val uid = user.uid
        val email = (user.email ?: "").lowercase()

        // Prevent presence listener from creating users collection documents during deletion
        CashDashApplication.isDeletingAccount = true

        SecurityManager.stopListening()

        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Deleting account...")
            setCancelable(false)
            show()
        }

        // Wipe Firestore data first while still authenticated
        wipeUserFirestoreData(uid, email) {
            // Once data is wiped, delete the Auth user
            user.delete().addOnCompleteListener { delTask ->
                progressDialog.dismiss()
                if (delTask.isSuccessful) {
                    ToastHelper.showToast(this@ProfileActivity, "Your account has been deleted permanently")
                    FirestoreSyncManager.stopRealTimeSync(this@ProfileActivity)
                    SecurityManager.stopListening()

                    val prefsToClear = listOf(
                        "AppPrefs", "WalletPrefs", "WalletPrefs_v2", "CategoryPrefs",
                        "GraphData", "CategoryWeekData", "MoneySchedulePrefs",
                        "ScannerHistory", "LocalScanPrefs", "LocalScanPrefs_v2", "ScannerMetadataPrefs", "NotificationCache"
                    )
                    prefsToClear.forEach { name ->
                        getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                    }
                    // See SecurePrefsStore.wipe — clearing these as plain prefs corrupts them.
                    WalletStore.wipe(this@ProfileActivity)
                    ScanStore.wipe(this@ProfileActivity)
                    auth.signOut()

                    startActivity(Intent(this@ProfileActivity, EntryActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                } else {
                    // Reset flag since deletion failed and session is still active
                    CashDashApplication.isDeletingAccount = false
                    // Usually fails if the user hasn't logged in recently (Firebase Security Requirement)
                    ToastHelper.showToast(this@ProfileActivity, "Security verification required. Please log out and log back in, then try deleting again.")
                }
            }
        }
    }

    private fun wipeUserFirestoreData(uid: String, email: String, onComplete: () -> Unit = {}) {
        val db = FirebaseFirestore.getInstance()
        val logData = hashMapOf(
            "uid" to uid,
            "email" to email,
            "deleted_at" to System.currentTimeMillis(),
            "status" to "PERMANENT_WIPE_COMPLETED"
        )

        // The deleted_accounts marker is written on its own, NOT inside the wipe
        // batch. Security rules allow the owner to create it but nothing more, and
        // a batch is atomic: when this write was part of it, one rejected document
        // failed the whole thing and the account's financial data survived the
        // "irreversible" deletion. The marker is best-effort; the wipe is not.
        db.collection("deleted_accounts").document(email).set(logData)
            .addOnFailureListener { e ->
                android.util.Log.w("ProfileActivity", "Could not write deletion marker", e)
            }

        // Retrieve notifications to include in a single atomic batch deletion
        db.collection("users").document(email).collection("notifications")
            .get()
            .addOnCompleteListener { task ->
                val batch = db.batch()

                // Wipe sub-collections under config. Keep this list in step with
                // the documents FirestoreSyncManager writes — the Cloud Function
                // that runs on account deletion enumerates the collection instead.
                val docs = listOf(
                    "profile", "wallet", "categories", "history", "analytics",
                    "history_scanner", "undo_details", "scanner_metadata",
                    "finminder", "upi_allocations"
                )
                docs.forEach { docName ->
                    batch.delete(db.collection("users").document(email).collection("config").document(docName))
                }

                // Wipe sub-collections under notifications if found
                if (task.isSuccessful && task.result != null) {
                    for (doc in task.result.documents) {
                        batch.delete(doc.reference)
                    }
                }

                // Finally delete the root document itself
                batch.delete(db.collection("users").document(email))

                // Remove RTDB presence data so admin center no longer shows deleted users
                val safeEmail = email.replace(".", ",")
                com.google.firebase.database.FirebaseDatabase.getInstance().getReference("status").child(safeEmail).removeValue()

                // Commit the atomic batch
                batch.commit()
                    .addOnSuccessListener {
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("ProfileActivity", "Firestore batch wipe failed", e)
                        // Proceed with Auth deletion anyway: the onAuthUserDeleted
                        // Cloud Function wipes the same data with admin rights, so
                        // stopping here would strand the account instead. Tell the
                        // user rather than reporting a clean deletion silently.
                        ToastHelper.showToast(
                            this@ProfileActivity,
                            "Some data could not be removed from this device's session — server cleanup will finish it."
                        )
                        onComplete()
                    }
            }
    }



    private fun showConfirmDialog(title: String, message: String, actionText: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        val layoutRes = if (ThemeHelper.getCurrentTheme(this) == "Blue") {
            R.layout.dialog_confirm_blue
        } else {
            R.layout.dialog_confirm_action
        }
        dialog.setContentView(layoutRes)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - 100,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvConfirmTitle)
        val tvMessage = dialog.findViewById<TextView>(R.id.tvConfirmMessage)
        val btnCancel = dialog.findViewById<Button>(R.id.btnConfirmCancel)
        val btnAction = dialog.findViewById<Button>(R.id.btnConfirmAction)

        tvTitle.text = title
        tvMessage.text = message
        btnAction.text = actionText

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAction.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
    }

    private fun showChangePasswordDialog() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            ToastHelper.showToast(this@ProfileActivity, "You must be signed in to change password")
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_change_password)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - 100,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val edtOldPassword = dialog.findViewById<EditText>(R.id.edtOldPassword)
        val edtNewPassword = dialog.findViewById<EditText>(R.id.edtNewPassword)
        val edtConfirmPassword = dialog.findViewById<EditText>(R.id.edtConfirmPassword)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancel)
        val btnSavePassword = dialog.findViewById<Button>(R.id.btnSavePassword)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSavePassword.setOnClickListener {
            val oldPass = edtOldPassword.text.toString()
            val newPass = edtNewPassword.text.toString()
            val confPass = edtConfirmPassword.text.toString()

            if (oldPass.isEmpty() || newPass.isEmpty() || confPass.isEmpty()) {
                Toast.makeText(this@ProfileActivity, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass != confPass) {
                Toast.makeText(this@ProfileActivity, "New passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass.length < 6) {
                Toast.makeText(this@ProfileActivity, "New password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSavePassword.isEnabled = false
            btnSavePassword.text = "Saving..."

            val email = user.email ?: return@setOnClickListener
            val credential = EmailAuthProvider.getCredential(email, oldPass)

            user.reauthenticate(credential).addOnCompleteListener { reAuthTask ->
                if (reAuthTask.isSuccessful) {
                    user.updatePassword(newPass).addOnCompleteListener { updateTask ->
                        if (updateTask.isSuccessful) {
                            FirestoreSyncManager.pushAllDataToCloud(this@ProfileActivity)
                            ToastHelper.showToast(this@ProfileActivity, "Password updated successfully!")
                            dialog.dismiss()
                        } else {
                            btnSavePassword.isEnabled = true
                            btnSavePassword.text = "Save"
                            Toast.makeText(this@ProfileActivity, "Failed: ${updateTask.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    btnSavePassword.isEnabled = true
                    btnSavePassword.text = "Save"
                    Toast.makeText(this@ProfileActivity, "Authentication failed. Check old password.", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(syncReceiver, android.content.IntentFilter(FirestoreSyncManager.ACTION_SYNC_UPDATE))
    }

    override fun onPause() {
        super.onPause()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(syncReceiver)
    }

    /**
     * "02 Oct 2004" -> "02 Oct 2004  (21 years old)".
     *
     * Locale.ENGLISH is deliberate: the picker writes the month with a hardcoded English
     * three-letter abbreviation, so parsing under the device locale would fail on a
     * non-English phone and hide the date entirely.
     *
     * Anything that does not parse is returned unchanged, so a stored value in an older
     * or unexpected format still shows the date rather than an error or a blank field.
     */
    /**
     * "02 Oct 2004  (21 years old)  ·  Male".
     *
     * The dot separates the date block from the gender, as the register sheet does; the
     * age travels with the date because it is derived from it. Each part is dropped
     * independently: no gender recorded loses the dot and the label, and an unparseable
     * date loses only the age.
     */
    private fun dobLine(dob: String): String {
        val gender = EntryActivity.genderLabel(
            getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_gender", "") ?: ""
        )
        val withAge = dobWithAge(dob)
        if (dob.isBlank() || gender.isEmpty()) return withAge
        return "$withAge  ·  $gender"
    }

    private fun dobWithAge(dob: String): String {
        if (dob.isBlank()) return dob
        return try {
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH)
            val birth = java.time.LocalDate.parse(dob.trim(), formatter)
            val years = java.time.Period.between(birth, java.time.LocalDate.now()).years
            if (years < 0) dob else "$dob  ($years ${if (years == 1) "year" else "years"} old)"
        } catch (e: Exception) {
            dob
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selectedDob", selectedDob)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedDob = savedInstanceState.getString("selectedDob", "")
        if (!savedDob.isNullOrEmpty()) {
            selectedDob = savedDob
            // Update the displayed DOB text if the view is available
            findViewById<android.widget.TextView>(R.id.tvDob)?.text = selectedDob
        }
    }
}
