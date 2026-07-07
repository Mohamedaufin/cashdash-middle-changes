package com.cash.dash

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FirebaseFirestore

class AdminMessagingActivity : ThemedActivity() {

    private var isAnnouncement = false
    private var currentTab = TabType.GLOBAL
    private val selectedEmails = mutableSetOf<String>()
    private val allUsers = mutableListOf<Pair<String, String>>() // Email, Display Name
    private val filteredUsers = mutableListOf<Pair<String, String>>()

    enum class TabType { GLOBAL, ADMIN, USER, AGE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_messaging)

        isAnnouncement = intent.getBooleanExtra("isAnnouncement", false)

        val tvHeaderTitle = findViewById<TextView>(R.id.tvHeaderTitle)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val tvTargetDesc = findViewById<TextView>(R.id.tvTargetDesc)
        val edtMessageTitle = findViewById<EditText>(R.id.edtMessageTitle)
        val edtMessageBody = findViewById<EditText>(R.id.edtMessageBody)

        tvHeaderTitle.text = if (isAnnouncement) "New Announcement" else "New Push Notification"
        
        edtMessageTitle.hint = if (isAnnouncement) "Announcement Title" else "Notification Title"
        edtMessageBody.hint = if (isAnnouncement) "Write your announcement here..." else "Write your notification here..."

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupTabs()
        setupUserSearch()
        
        val btnSendInline = findViewById<Button>(R.id.btnSendInline)
        
        val sendAction = View.OnClickListener {
            val title = edtMessageTitle.text.toString().trim()
            val body = edtMessageBody.text.toString().trim()
            
            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in both fields")
                return@OnClickListener
            }

            when (currentTab) {
                TabType.GLOBAL -> confirmSend("Global", "Send to all users?", title, body)
                TabType.ADMIN -> confirmSend("Admin", "Send to Admins only?", title, body)
                TabType.USER, TabType.AGE -> {
                    if (selectedEmails.isEmpty()) {
                        ToastHelper.showToast(this, "Please select at least one user")
                        return@OnClickListener
                    }
                    val typeStr = if (currentTab == TabType.AGE) "Age Specific" else "User Specific"
                    confirmSend(typeStr, "Send to ${selectedEmails.size} user(s)?", title, body)
                }
            }
        }
        
        btnSend.setOnClickListener(sendAction)
        btnSendInline.setOnClickListener(sendAction)

        findViewById<TextView>(R.id.btnFetchUsers).setOnClickListener {
            fetchAgeSpecificUsers()
        }
        
        val cbSelectAll = findViewById<CheckBox>(R.id.cbSelectAll)
        val checkboxColor = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.WHITE
        cbSelectAll.buttonTintList = android.content.res.ColorStateList.valueOf(checkboxColor)
        
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedEmails.addAll(filteredUsers.map { it.first })
            } else {
                selectedEmails.clear()
            }
            refreshUserCheckboxes()
        }

        // Pre-fetch all users in background
        fetchAllUsers()
    }

    private fun setupTabs() {
        val tabGlobal = findViewById<TextView>(R.id.tabGlobal)
        val tabAdmin = findViewById<TextView>(R.id.tabAdmin)
        val tabUser = findViewById<TextView>(R.id.tabUser)
        val tabAge = findViewById<TextView>(R.id.tabAge)

        val layoutAgeSpecific = findViewById<View>(R.id.layoutAgeSpecific)
        val layoutUserList = findViewById<View>(R.id.layoutUserList)
        val tvTargetDesc = findViewById<TextView>(R.id.tvTargetDesc)
        val btnSend = findViewById<Button>(R.id.btnSend)

        fun selectTab(tab: TabType) {
            currentTab = tab
            
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(R.attr.roundBackground, typedValue, true)
            val bgResId = typedValue.resourceId
            
            val tabs = listOf(tabGlobal, tabAdmin, tabUser, tabAge)
            val defaultBg = androidx.core.content.ContextCompat.getDrawable(this, bgResId)
            for (t in tabs) {
                t.background = defaultBg?.constantState?.newDrawable()?.mutate()
                t.backgroundTintList = null
                t.setTextColor(ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor))
                t.invalidate()
            }
            
            // Highlight selected
            val selectedView = when (tab) {
                TabType.GLOBAL -> tabGlobal
                TabType.ADMIN -> tabAdmin
                TabType.USER -> tabUser
                TabType.AGE -> tabAge
            }
            
            val colorStr = when (tab) {
                TabType.GLOBAL -> "#008000"
                TabType.ADMIN -> "#FF4D4D"
                TabType.USER -> "#FFA500"
                TabType.AGE -> if (isAnnouncement) "#FF007F" else "#00C2FF"
            }
            val color = android.graphics.Color.parseColor(colorStr)
            
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(color)
            drawable.cornerRadius = 100f * resources.displayMetrics.density
            
            selectedView.background = drawable
            selectedView.backgroundTintList = null
            selectedView.setTextColor(android.graphics.Color.WHITE)
            
            btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            findViewById<Button>(R.id.btnSendInline).backgroundTintList = android.content.res.ColorStateList.valueOf(color)

            // Update UI based on tab
            layoutAgeSpecific.visibility = if (tab == TabType.AGE) View.VISIBLE else View.GONE
            layoutUserList.visibility = if (tab == TabType.USER || tab == TabType.AGE) View.VISIBLE else View.GONE
            
            if (tab == TabType.USER) {
                filteredUsers.clear()
                filteredUsers.addAll(allUsers)
                refreshUserCheckboxes()
                findViewById<EditText>(R.id.edtSearchUsers).visibility = View.VISIBLE
            } else if (tab == TabType.AGE) {
                filteredUsers.clear()
                refreshUserCheckboxes()
                findViewById<EditText>(R.id.edtSearchUsers).visibility = View.GONE
            }

            val btnSendInline = findViewById<Button>(R.id.btnSendInline)
            val layoutBottomBar = findViewById<View>(R.id.layoutBottomBar)
            
            // Update Descriptions & Button
            when (tab) {
                TabType.GLOBAL -> {
                    tvTargetDesc.text = "This will be sent to all users."
                    btnSend.text = "Send Globally"
                    btnSendInline.text = "Send Globally"
                    btnSendInline.visibility = View.VISIBLE
                    layoutBottomBar.visibility = View.GONE
                }
                TabType.ADMIN -> {
                    tvTargetDesc.text = "This will be sent to administrators only."
                    btnSend.text = "Send to Admins"
                    btnSendInline.text = "Send to Admins"
                    btnSendInline.visibility = View.VISIBLE
                    layoutBottomBar.visibility = View.GONE
                }
                TabType.USER -> {
                    tvTargetDesc.text = "Select specific users to send."
                    btnSend.text = "Send to Selected Users"
                    btnSendInline.visibility = View.GONE
                    layoutBottomBar.visibility = View.VISIBLE
                }
                TabType.AGE -> {
                    tvTargetDesc.text = "Filter users by age range."
                    btnSend.text = "Send to Selected Users"
                    btnSendInline.visibility = View.GONE
                    layoutBottomBar.visibility = View.VISIBLE
                }
            }
        }

        tabGlobal.setOnClickListener { selectTab(TabType.GLOBAL) }
        tabAdmin.setOnClickListener { selectTab(TabType.ADMIN) }
        tabUser.setOnClickListener { selectTab(TabType.USER) }
        tabAge.setOnClickListener { selectTab(TabType.AGE) }
        
        // Fix initial state styling properly
        selectTab(TabType.GLOBAL)
    }

    private fun setupUserSearch() {
        val edtSearch = findViewById<EditText>(R.id.edtSearchUsers)
        edtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase()
                filteredUsers.clear()
                if (query.isEmpty()) {
                    filteredUsers.addAll(allUsers)
                } else {
                    filteredUsers.addAll(allUsers.filter {
                        it.first.lowercase().contains(query) || it.second.lowercase().contains(query)
                    })
                }
                refreshUserCheckboxes()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun fetchAllUsers() {
        FirebaseFirestore.getInstance().collection("users").get()
            .addOnSuccessListener { querySnapshot ->
                allUsers.clear()
                for (doc in querySnapshot.documents) {
                    val email = doc.id
                    val profileName = doc.getString("name")
                    val displayName = if (!profileName.isNullOrEmpty()) profileName else email.substringBefore("@")
                    allUsers.add(Pair(email, displayName))
                }
                allUsers.sortBy { it.second.lowercase() }
                
                if (currentTab == TabType.USER) {
                    filteredUsers.clear()
                    filteredUsers.addAll(allUsers)
                    refreshUserCheckboxes()
                }
            }
    }

    private fun fetchAgeSpecificUsers() {
        val minAgeStr = findViewById<EditText>(R.id.edtMinAge).text.toString().trim()
        val maxAgeStr = findViewById<EditText>(R.id.edtMaxAge).text.toString().trim()
        
        val minAge = minAgeStr.toIntOrNull() ?: 0
        val maxAge = maxAgeStr.toIntOrNull() ?: 999
        
        if (minAge > maxAge) {
            ToastHelper.showToast(this, "Min age cannot be greater than max age")
            return
        }

        val btnFetch = findViewById<TextView>(R.id.btnFetchUsers)
        btnFetch.text = "Fetching..."
        btnFetch.isEnabled = false
        
        filteredUsers.clear()
        
        FirebaseFirestore.getInstance().collection("users").get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val dob = doc.getString("dob")
                    if (!dob.isNullOrEmpty()) {
                        val age = calculateAge(dob)
                        if (age in minAge..maxAge) {
                            val email = doc.id
                            val profileName = doc.getString("name")
                            val displayName = if (!profileName.isNullOrEmpty()) profileName else email.substringBefore("@")
                            filteredUsers.add(Pair(email, displayName))
                        }
                    }
                }
                filteredUsers.sortBy { it.second.lowercase() }
                refreshUserCheckboxes()
                ToastHelper.showToast(this, "Found ${filteredUsers.size} users")
                btnFetch.text = "Fetch Users"
                btnFetch.isEnabled = true
            }
            .addOnFailureListener {
                ToastHelper.showToast(this, "Failed to fetch users")
                btnFetch.text = "Fetch Users"
                btnFetch.isEnabled = true
            }
    }

    private fun refreshUserCheckboxes() {
        val layoutUsersInner = findViewById<LinearLayout>(R.id.layoutUsersInner)
        val tvEmptyList = findViewById<TextView>(R.id.tvEmptyList)
        
        layoutUsersInner.removeAllViews()
        
        if (filteredUsers.isEmpty()) {
            tvEmptyList.visibility = View.VISIBLE
            return
        }
        
        tvEmptyList.visibility = View.GONE
        val density = resources.displayMetrics.density
        val checkboxColor = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.WHITE
        val checkboxTintList = android.content.res.ColorStateList.valueOf(checkboxColor)

        for (target in filteredUsers) {
            val email = target.first
            val displayName = target.second
            
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                
                val cb = CheckBox(this@AdminMessagingActivity).apply {
                    buttonTintList = checkboxTintList
                    isChecked = selectedEmails.contains(email)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedEmails.add(email)
                        } else {
                            selectedEmails.remove(email)
                        }
                    }
                }
                
                val tv = TextView(this@AdminMessagingActivity).apply {
                    text = "$displayName - $email"
                    setTextColor(ThemeHelper.resolveColorAttr(this@AdminMessagingActivity, R.attr.textPrimaryColor))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding((8 * density).toInt(), 0, 0, 0)
                }

                addView(cb)
                addView(tv)
                
                setOnClickListener {
                    cb.isChecked = !cb.isChecked
                }
            }
            layoutUsersInner.addView(layout)
        }
    }

    private fun calculateAge(dobString: String): Int {
        try {
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
            val dob = sdf.parse(dobString) ?: return 0
            val today = java.util.Calendar.getInstance()
            val dobCal = java.util.Calendar.getInstance()
            dobCal.time = dob
            
            var age = today.get(java.util.Calendar.YEAR) - dobCal.get(java.util.Calendar.YEAR)
            if (today.get(java.util.Calendar.DAY_OF_YEAR) < dobCal.get(java.util.Calendar.DAY_OF_YEAR)) {
                age--
            }
            return age
        } catch (e: Exception) {
            return 0
        }
    }

    private fun confirmSend(type: String, message: String, title: String, body: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
        val confirmDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.tvConfirmTitle).text = "$type Send"
        dialogView.findViewById<TextView>(R.id.tvConfirmMessage).text = message
        
        val btnYes = dialogView.findViewById<Button>(R.id.btnConfirmAction)
        val btnNo = dialogView.findViewById<Button>(R.id.btnConfirmCancel)

        btnYes.text = "Send"
        btnYes.setTextColor(android.graphics.Color.WHITE)
        
        btnYes.setOnClickListener {
            confirmDialog.dismiss()
            if (isAnnouncement) {
                sendAnnouncement(title, body)
            } else {
                sendPushNotification(title, body)
            }
        }
        btnNo.setOnClickListener { confirmDialog.dismiss() }
        confirmDialog.show()
    }

    private fun sendAnnouncement(title: String, body: String) {
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnSendInline = findViewById<Button>(R.id.btnSendInline)
        btnSend.isEnabled = false
        btnSendInline.isEnabled = false
        btnSend.text = "Sending..."
        btnSendInline.text = "Sending..."

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
            "adminOnly" to (currentTab == TabType.ADMIN)
        )

        if (currentTab == TabType.USER || currentTab == TabType.AGE) {
            data["targetEmails"] = selectedEmails.toList()
        }

        db.collection("announcements").document(timestamp.toString()).set(data)
            .addOnSuccessListener {
                ToastHelper.showToast(this, "Announcement Sent!")
                logAction("Announcement", title, body)
                finish()
            }
            .addOnFailureListener {
                ToastHelper.showToast(this, "Failed to send: ${it.message}")
                btnSend.isEnabled = true
                btnSendInline.isEnabled = true
                btnSend.text = "Send"
                btnSendInline.text = "Send"
            }
    }

    private fun sendPushNotification(title: String, body: String) {
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnSendInline = findViewById<Button>(R.id.btnSendInline)
        btnSend.isEnabled = false
        btnSendInline.isEnabled = false
        btnSend.text = "Sending..."
        btnSendInline.text = "Sending..."

        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

        if (currentTab == TabType.GLOBAL || currentTab == TabType.ADMIN) {
            val data = hashMapOf(
                "title" to title,
                "message" to body,
                "timestamp" to timestamp,
                "time" to timeStr,
                "adminOnly" to (currentTab == TabType.ADMIN)
            )

            db.collection("global_pushes").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Push Notification Sent!")
                    logAction("Push Notification", title, body)
                    finish()
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to send: ${it.message}")
                    btnSend.isEnabled = true
                    btnSendInline.isEnabled = true
                    btnSend.text = "Send"
                    btnSendInline.text = "Send"
                }
        } else {
            val batch = db.batch()
            selectedEmails.forEachIndexed { index, email ->
                val data = hashMapOf(
                    "email" to email,
                    "title" to title,
                    "message" to body,
                    "timestamp" to timestamp + index
                )
                val docRef = db.collection("user_pushes").document()
                batch.set(docRef, data)
            }

            batch.commit()
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Notifications sent to ${selectedEmails.size} user(s)!")
                    logAction("User Push Notification", title, body)
                    finish()
                }
                .addOnFailureListener { e ->
                    ToastHelper.showToast(this, "Failed to send: ${e.message}")
                    btnSend.isEnabled = true
                    btnSendInline.isEnabled = true
                    btnSend.text = "Send"
                    btnSendInline.text = "Send"
                }
        }
    }

    private fun logAction(baseType: String, title: String, content: String) {
        val type = when (currentTab) {
            TabType.GLOBAL -> "Global $baseType"
            TabType.ADMIN -> "Admin $baseType"
            TabType.USER -> "User Specific $baseType"
            TabType.AGE -> "Age Specific $baseType"
        }
        
        val db = FirebaseFirestore.getInstance()
        val logData = hashMapOf<String, Any>(
            "timestamp" to System.currentTimeMillis(),
            "actionType" to type,
            "title" to title,
            "message" to content,
            "performedBy" to (getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", "Unknown") ?: "Unknown")
        )

        if (currentTab == TabType.USER || currentTab == TabType.AGE) {
            val targetsStr = selectedEmails.map { email ->
                val name = allUsers.find { it.first == email }?.second ?: "Unknown"
                "$name - $email"
            }.joinToString(", ")
            logData["details"] = targetsStr
        }

        if (currentTab == TabType.AGE) {
            val minAge = findViewById<EditText>(R.id.edtMinAge).text.toString().trim()
            val maxAge = findViewById<EditText>(R.id.edtMaxAge).text.toString().trim()
            if (minAge.isNotEmpty() || maxAge.isNotEmpty()) {
                logData["ageRange"] = "$minAge - $maxAge"
            }
        }

        db.collection("admin_logs").add(logData)
    }
}
