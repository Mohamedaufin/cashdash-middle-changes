package com.cash.dash

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class AgeSpecificPushActivity : ThemedActivity() {

    private lateinit var edtMinAge: EditText
    private lateinit var edtMaxAge: EditText
    private lateinit var btnFetchUsers: Button
    private lateinit var layoutUserListContainer: LinearLayout
    private lateinit var cbSelectAll: CheckBox
    private lateinit var layoutUsersInner: LinearLayout
    private lateinit var edtPushTitle: EditText
    private lateinit var edtPushContent: EditText
    private lateinit var btnSendPush: Button
    private lateinit var tvAgeSpecificTitle: TextView

    private val userTargets = mutableListOf<Pair<String, String>>() // <Email, DisplayName>
    private val selectedEmails = mutableSetOf<String>()
    private var isAnnouncement = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_age_specific_push)

        isAnnouncement = intent.getBooleanExtra("isAnnouncement", false)

        edtMinAge = findViewById(R.id.edtMinAge)
        edtMaxAge = findViewById(R.id.edtMaxAge)
        btnFetchUsers = findViewById(R.id.btnFetchUsers)
        layoutUserListContainer = findViewById(R.id.layoutUserListContainer)
        cbSelectAll = findViewById(R.id.cbSelectAll)
        layoutUsersInner = findViewById(R.id.layoutUsersInner)
        edtPushTitle = findViewById(R.id.edtPushTitle)
        edtPushContent = findViewById(R.id.edtPushContent)
        btnSendPush = findViewById(R.id.btnSendPush)
        tvAgeSpecificTitle = findViewById(R.id.tvAgeSpecificTitle)

        findViewById<ImageView>(R.id.btnBackAgeSpecific).setOnClickListener { finish() }

        if (isAnnouncement) {
            tvAgeSpecificTitle.text = "Age Specific Announcement"
        } else {
            tvAgeSpecificTitle.text = "Age Specific Notification"
        }

        btnFetchUsers.setOnClickListener {
            val minAge = edtMinAge.text.toString().toIntOrNull()
            val maxAge = edtMaxAge.text.toString().toIntOrNull()

            if (minAge == null || maxAge == null) {
                ToastHelper.showToast(this, "Please enter both minimum and maximum age.")
                return@setOnClickListener
            }

            fetchUsersByAgeRange(minAge, maxAge)
        }

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedEmails.addAll(userTargets.map { it.first })
            } else {
                selectedEmails.clear()
            }
            refreshCheckboxes()
        }

        btnSendPush.setOnClickListener {
            if (selectedEmails.isEmpty()) {
                ToastHelper.showToast(this, "Please select at least one user.")
                return@setOnClickListener
            }
            val title = edtPushTitle.text.toString().trim()
            val content = edtPushContent.text.toString().trim()
            if (title.isEmpty() || content.isEmpty()) {
                ToastHelper.showToast(this, "Please enter both title and content.")
                return@setOnClickListener
            }

            confirmSendPush(title, content)
        }
    }

    private fun fetchUsersByAgeRange(minAge: Int, maxAge: Int) {
        val db = FirebaseFirestore.getInstance()
        btnFetchUsers.text = "Fetching..."
        btnFetchUsers.isEnabled = false

        db.collection("users").get().addOnSuccessListener { result ->
            userTargets.clear()
            selectedEmails.clear()
            
            val sdfWithoutComma = SimpleDateFormat("dd MMM yyyy", Locale.US)
            val sdfWithComma = SimpleDateFormat("dd MMM, yyyy", Locale.US)
            val calendar = Calendar.getInstance()
            val currentYear = calendar.get(Calendar.YEAR)
            val currentDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

            for (document in result) {
                val dobString = document.getString("dob")
                if (!dobString.isNullOrEmpty()) {
                    try {
                        val dobDate = try {
                            sdfWithoutComma.parse(dobString)
                        } catch (e: Exception) {
                            sdfWithComma.parse(dobString)
                        }
                        if (dobDate != null) {
                            val dobCal = Calendar.getInstance()
                            dobCal.time = dobDate
                            
                            var age = currentYear - dobCal.get(Calendar.YEAR)
                            if (currentDayOfYear < dobCal.get(Calendar.DAY_OF_YEAR)) {
                                age--
                            }

                            if (age in minAge..maxAge) {
                                val email = document.id
                                val profileName = document.getString("name")
                                val displayName = if (!profileName.isNullOrEmpty()) profileName else email.substringBefore("@")
                                userTargets.add(Pair(email, displayName))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AgeSpecificPush", "Failed to parse DOB: '$dobString' for user ${document.id}")
                    }
                }
            }

            btnFetchUsers.text = "Fetch Users"
            btnFetchUsers.isEnabled = true

            if (userTargets.isEmpty()) {
                ToastHelper.showToast(this, "No users found in this age range.")
                layoutUserListContainer.visibility = View.GONE
            } else {
                layoutUserListContainer.visibility = View.VISIBLE
                populateUserCheckboxes()
            }
        }.addOnFailureListener {
            ToastHelper.showToast(this, "Failed to fetch users: ${it.message}")
            btnFetchUsers.text = "Fetch Users"
            btnFetchUsers.isEnabled = true
        }
    }

    private fun populateUserCheckboxes() {
        layoutUsersInner.removeAllViews()
        val density = resources.displayMetrics.density

        userTargets.sortBy { it.second.lowercase() }

        for (target in userTargets) {
            val email = target.first
            val displayName = target.second
            
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                
                val cb = CheckBox(this@AgeSpecificPushActivity).apply {
                    buttonTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.resolveColorAttr(this@AgeSpecificPushActivity, R.attr.textPrimaryColor))
                    isChecked = selectedEmails.contains(email)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedEmails.add(email)
                        } else {
                            selectedEmails.remove(email)
                            cbSelectAll.setOnCheckedChangeListener(null)
                            cbSelectAll.isChecked = false
                            // Re-attach listener
                            cbSelectAll.setOnCheckedChangeListener { _, selectAllChecked ->
                                if (selectAllChecked) {
                                    selectedEmails.addAll(userTargets.map { it.first })
                                } else {
                                    selectedEmails.clear()
                                }
                                refreshCheckboxes()
                            }
                        }
                    }
                }
                
                val tv = TextView(this@AgeSpecificPushActivity).apply {
                    text = "$displayName - $email"
                    setTextColor(ThemeHelper.resolveColorAttr(this@AgeSpecificPushActivity, R.attr.textPrimaryColor))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding((8 * density).toInt(), 0, 0, 0)
                }

                addView(cb)
                addView(tv)
            }
            layoutUsersInner.addView(layout)
        }
    }

    private fun refreshCheckboxes() {
        for (i in 0 until layoutUsersInner.childCount) {
            val childLayout = layoutUsersInner.getChildAt(i) as? LinearLayout
            if (childLayout != null) {
                val cb = childLayout.getChildAt(0) as? CheckBox
                val tv = childLayout.getChildAt(1) as? TextView
                if (cb != null && tv != null) {
                    val fullText = tv.text.toString()
                    val email = fullText.substringAfterLast(" - ")
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = selectedEmails.contains(email)
                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedEmails.add(email)
                        } else {
                            selectedEmails.remove(email)
                        }
                    }
                }
            }
        }
    }

    private fun confirmSendPush(title: String, content: String) {
        val confirmDialog = AlertDialog.Builder(this)
            .setTitle("Confirm")
            .setMessage("Are you sure you want to send this to ${selectedEmails.size} user(s)?")
            .setPositiveButton("Send") { _, _ ->
                val minAge = edtMinAge.text.toString().trim()
                val maxAge = edtMaxAge.text.toString().trim()
                val ageRange = if (minAge.isNotEmpty() && maxAge.isNotEmpty()) "$minAge - $maxAge" else null
                
                if (isAnnouncement) {
                    sendAnnouncements(title, content, ageRange)
                } else {
                    sendNotifications(title, content, ageRange)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        confirmDialog.window?.setBackgroundDrawableResource(ThemeHelper.getDrawable(this, R.drawable.bg_3d_card))
        confirmDialog.show()
    }

    private fun sendAnnouncements(title: String, body: String, ageRange: String?) {
        btnSendPush.text = "Sending..."
        btnSendPush.isEnabled = false

        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        val data = hashMapOf<String, Any>(
            "subject" to title,
            "query" to body,
            "reply" to "[ANNOUNCEMENT]",
            "status" to "resolved",
            "timestamp" to timestamp,
            "time" to timeStr,
            "read" to false,
            "adminOnly" to false,
            "targetEmails" to selectedEmails.toList()
        )

        val targetsStr = selectedEmails.map { email ->
            val name = userTargets.find { it.first == email }?.second ?: "Unknown"
            "$name - $email"
        }.joinToString(", ")

        db.collection("announcements").document(timestamp.toString()).set(data).addOnSuccessListener {
            logAdminAction("Age Specific Announcement", title, body, targetsStr, ageRange)
            ToastHelper.showToast(this, "Announcements sent successfully!")
            finish()
        }.addOnFailureListener {
            ToastHelper.showToast(this, "Failed to send announcements: ${it.message}")
            btnSendPush.text = "Send"
            btnSendPush.isEnabled = true
        }
    }

    private fun sendNotifications(title: String, body: String, ageRange: String?) {
        btnSendPush.text = "Sending..."
        btnSendPush.isEnabled = false

        val db = FirebaseFirestore.getInstance()
        val targetsStr = selectedEmails.map { email ->
            val name = userTargets.find { it.first == email }?.second ?: "Unknown"
            "$name - $email"
        }.joinToString(", ")
        logAdminAction("Age Specific Notification", title, body, targetsStr, ageRange)

        val batch = db.batch()
        val timestamp = System.currentTimeMillis()
        selectedEmails.forEachIndexed { index, email ->
            val data = hashMapOf(
                "email" to email,
                "title" to title,
                "message" to body,
                "timestamp" to timestamp + index
            )
            batch.set(db.collection("user_pushes").document(), data)
        }

        batch.commit()
            .addOnSuccessListener {
                ToastHelper.showToast(this, "Notifications sent to ${selectedEmails.size} user(s)!")
                finish()
            }
            .addOnFailureListener {
                ToastHelper.showToast(this, "Failed to send: ${it.message}")
                btnSendPush.text = "Send"
                btnSendPush.isEnabled = true
            }
    }


    private fun logAdminAction(type: String, title: String, content: String, targets: String? = null, ageRange: String? = null) {
        val db = FirebaseFirestore.getInstance()
        val logData = hashMapOf<String, Any>(
            "timestamp" to System.currentTimeMillis(),
            "actionType" to type,
            "title" to title,
            "message" to content,
            "performedBy" to (getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", "Unknown") ?: "Unknown")
        )
        if (targets != null) {
            logData["details"] = targets
        }
        if (ageRange != null) {
            logData["ageRange"] = ageRange
        }
        db.collection("admin_logs").add(logData)
    }
}
