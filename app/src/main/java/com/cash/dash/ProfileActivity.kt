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

        // Load saved data
        edtName.setText(prefs.getString(KEY_NAME, ""))
        edtPhone.setText(prefs.getString(KEY_PHONE, ""))
        edtEmail.setText(prefs.getString(KEY_EMAIL, ""))
        selectedDob = prefs.getString("user_dob", "") ?: ""
        tvDob.text = selectedDob

        tvDob.setOnClickListener {
            showDobPickerDialog(tvDob)
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
                    "AppPrefs", "WalletPrefs", "CategoryPrefs",
                    "GraphData", "CategoryWeekData", "MoneySchedulePrefs",
                    "ScannerHistory", "LocalScanPrefs", "NotificationCache"
                )
                prefsToClear.forEach { name ->
                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                }

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
                        "AppPrefs", "WalletPrefs", "CategoryPrefs",
                        "GraphData", "CategoryWeekData", "MoneySchedulePrefs",
                        "ScannerHistory", "LocalScanPrefs", "ScannerMetadataPrefs", "NotificationCache"
                    )
                    prefsToClear.forEach { name ->
                        getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                    }
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

        // Retrieve notifications to include in a single atomic batch deletion
        db.collection("users").document(email).collection("notifications")
            .get()
            .addOnCompleteListener { task ->
                val batch = db.batch()

                // 1. Log deletion request in deleted_accounts
                batch.set(db.collection("deleted_accounts").document(email), logData)

                // 2. Wipe sub-collections under config
                val docs = listOf("profile", "wallet", "categories", "history", "analytics", "history_scanner", "undo_details", "scanner_metadata")
                docs.forEach { docName ->
                    batch.delete(db.collection("users").document(email).collection("config").document(docName))
                }

                // 3. Wipe sub-collections under notifications if found
                if (task.isSuccessful && task.result != null) {
                    for (doc in task.result.documents) {
                        batch.delete(doc.reference)
                    }
                }

                // 4. Finally delete the root document itself
                batch.delete(db.collection("users").document(email))

                // Commit the atomic batch
                batch.commit()
                    .addOnSuccessListener {
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("ProfileActivity", "Firestore batch wipe failed", e)
                        // Fallback: Proceed with Auth deletion anyway
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

    private fun showDobPickerDialog(tvDob: TextView) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
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
}
