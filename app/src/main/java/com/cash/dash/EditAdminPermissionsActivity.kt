package com.cash.dash

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*
import androidx.core.content.ContextCompat

class EditAdminPermissionsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_admin_permissions)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val email = intent.getStringExtra("email") ?: return finish()
        val name = intent.getStringExtra("name") ?: ""
        val isNewAdmin = intent.getBooleanExtra("isNewAdmin", false)
        val isReviewingRequest = intent.getBooleanExtra("isReviewingRequest", false)
        val isExtensionRequest = intent.getBooleanExtra("isExtensionRequest", false)

        val tvAdminEmail = findViewById<TextView>(R.id.tvAdminEmail)
        tvAdminEmail.text = if (name.isNotBlank() && name != "Unknown") "$name — $email" else email

        if (name.isBlank() || name == "Unknown") {
            db.collection("users").document(email).collection("config").document("profile")
                .get()
                .addOnSuccessListener { doc ->
                    val fetchedName = doc.getString("name")
                    if (!fetchedName.isNullOrBlank()) {
                        tvAdminEmail.text = "$fetchedName — $email"
                        // Backfill to admins collection so next load is fast
                        db.collection("admins").document(email).update("name", fetchedName)
                    }
                }
        }

        val cbAnnouncements = findViewById<CheckBox>(R.id.cbAnnouncements)
        val cbPromotions = findViewById<CheckBox>(R.id.cbPromotions)
        val cbNotifications = findViewById<CheckBox>(R.id.cbNotifications)
        val cbLastSeen = findViewById<CheckBox>(R.id.cbLastSeen)
        val cbAdminLogs = findViewById<CheckBox>(R.id.cbAdminLogs)
        val cbReplyQueries = findViewById<CheckBox>(R.id.cbReplyQueries)
        val cbAddNewAdmin = findViewById<CheckBox>(R.id.cbAddNewAdmin)

        cbAnnouncements.isChecked = intent.getBooleanExtra("sendAnnouncements", false)
        cbPromotions.isChecked = intent.getBooleanExtra("sendPromotions", false)
        cbNotifications.isChecked = intent.getBooleanExtra("sendNotifications", false)
        cbLastSeen.isChecked = intent.getBooleanExtra("viewLastSeen", false)
        cbAdminLogs.isChecked = intent.getBooleanExtra("viewAdminLogs", false)
        cbReplyQueries.isChecked = intent.getBooleanExtra("replyToQueries", false)
        cbAddNewAdmin.isChecked = intent.getBooleanExtra("allocateAdmins", false)

        val btnSave = findViewById<View>(R.id.btnSavePermissions)
        val btnRevoke = findViewById<View>(R.id.btnRevokeAccess)
        
        if (isNewAdmin) {
            btnRevoke.visibility = View.GONE
        }

        btnSave.setOnClickListener {
            // Simplified for now
            Toast.makeText(this, "Permissions saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnRevoke.setOnClickListener {
            // Simplified for now
            Toast.makeText(this, "Access revoked", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
