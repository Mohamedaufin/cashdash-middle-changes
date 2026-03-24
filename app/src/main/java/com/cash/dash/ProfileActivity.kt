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


class ProfileActivity : AppCompatActivity() {

    private val PREFS = "AppPrefs"
    private val KEY_NAME = "user_name"
    private val KEY_PHONE = "user_phone"
    private val KEY_EMAIL = "user_email"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        val edtName = findViewById<EditText>(R.id.edtName)
        val edtPhone = findViewById<EditText>(R.id.edtPhone)
        val edtEmail = findViewById<EditText>(R.id.edtEmail)
        val btnChangePassword = findViewById<Button>(R.id.btnChangePassword)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val btnDeleteAccount = findViewById<Button>(R.id.btnDeleteAccount)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Load saved data
        edtName.setText(prefs.getString(KEY_NAME, ""))
        edtPhone.setText(prefs.getString(KEY_PHONE, ""))
        edtEmail.setText(prefs.getString(KEY_EMAIL, ""))

        btnChangePassword.setOnClickListener {
            showChangePasswordDialog()
        }

        btnSave.setOnClickListener {
            val name = edtName.text.toString().trim()
            val phone = edtPhone.text.toString().trim()
            val email = edtEmail.text.toString().trim()

            if(name.isEmpty()){
                ToastHelper.showToast(this@ProfileActivity, "Enter name")
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString(KEY_NAME, name)
                putString(KEY_PHONE, phone)
                putString(KEY_EMAIL, email)
                apply()
            }

            // Sync these updated preferences straight to the newly created Firestore
            FirestoreSyncManager.pushAllDataToCloud(this@ProfileActivity)

            ToastHelper.showToast(this@ProfileActivity, "Profile Updated ✔")
            finish()
        }

        btnLogout.setOnClickListener {
            showConfirmDialog(
                title = "Logout Action",
                message = "Are you sure you want to log out of your session on this device?",
                actionText = "Logout"
            ) {
                // 0. Stop automatic sync listeners BEFORE clearing data
                FirestoreSyncManager.stopRealTimeSync(this@ProfileActivity)

                // 1. Clear ALL local data to prevent leak between accounts
                val prefsToClear = listOf(
                    "AppPrefs", "WalletPrefs", "CategoryPrefs", 
                    "GraphData", "CategoryWeekData", "MoneySchedulePrefs", 
                    "ScannerHistory", "LocalScanPrefs", "NotificationCache"
                )
                prefsToClear.forEach { name ->
                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                }

                // 2. Sign out
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
        val email = user.email ?: ""

        // Show a re-auth dialog to confirm identity — standard secure pattern
        showReauthDialog(email) { enteredPassword ->
            val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, enteredPassword)

            user.reauthenticate(credential).addOnCompleteListener { reAuthTask ->
                if (!reAuthTask.isSuccessful) {
                    ToastHelper.showToast(this, "Incorrect password. Please try again.")
                    return@addOnCompleteListener
                }

                val uid = user.uid

                // 1. Show feedback & redirect instantly
                ToastHelper.showToast(this@ProfileActivity, "Your account has been deleted permanently")
                FirestoreSyncManager.stopRealTimeSync(this@ProfileActivity)

                val prefsToClear = listOf(
                    "AppPrefs", "WalletPrefs", "CategoryPrefs",
                    "GraphData", "CategoryWeekData", "MoneySchedulePrefs",
                    "ScannerHistory", "LocalScanPrefs", "NotificationCache"
                )
                prefsToClear.forEach { name ->
                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                }
                auth.signOut()

                val i = Intent(this@ProfileActivity, EntryActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(i)
                finish()

                // 2. Silent background Firebase Auth + Firestore wipe
                user.delete().addOnCompleteListener { delTask ->
                    if (delTask.isSuccessful) wipeUserFirestoreData(uid, email)
                }
            }
        }
    }

    private fun showReauthDialog(email: String, onConfirmed: (String) -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_change_password)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - 100,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Reuse the change_password dialog layout — hide fields we don't need
        val edtOld = dialog.findViewById<android.widget.EditText>(R.id.edtOldPassword)
        val edtNew = dialog.findViewById<android.widget.EditText>(R.id.edtNewPassword)
        val edtConfirm = dialog.findViewById<android.widget.EditText>(R.id.edtConfirmPassword)
        val btnCancel = dialog.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnConfirm = dialog.findViewById<android.widget.Button>(R.id.btnSavePassword)

        edtNew.visibility = android.view.View.GONE
        edtConfirm.visibility = android.view.View.GONE
        edtOld.hint = "Enter your password to confirm"
        btnConfirm.text = "Delete Account"

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val password = edtOld.text.toString()
            if (password.isEmpty()) {
                ToastHelper.showToast(this, "Please enter your password")
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirmed(password)
        }
        dialog.show()
    }

    private fun wipeUserFirestoreData(uid: String, email: String) {
        val db = FirebaseFirestore.getInstance()
        try {
            // Log deletion request (using email as doc ID for visibility)
            val logData = hashMapOf(
                "uid" to uid,
                "email" to email,
                "deleted_at" to System.currentTimeMillis(),
                "status" to "PERMANENT_WIPE_COMPLETED"
            )
            db.collection("deleted_accounts").document(email).set(logData)

            // Wipe sub-collections (using email as root doc ID)
            val docs = listOf("profile", "wallet", "categories", "history", "analytics", "history_scanner", "undo_details")
            docs.forEach { docName ->
                db.collection("users").document(email).collection("config").document(docName).delete()
            }
            // Finally delete the root document itself
            db.collection("users").document(email).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    private fun showConfirmDialog(title: String, message: String, actionText: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_confirm_action)
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
}
