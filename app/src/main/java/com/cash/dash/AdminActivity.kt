package com.cash.dash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.LinearLayout
import android.view.View
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import android.app.DatePickerDialog

class AdminActivity : ThemedActivity() {

    private val userStatusList = mutableListOf<UserStatusItem>()
    private var selectedDateCalendar = Calendar.getInstance()
    private var usersListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private val selectedQueries = mutableSetOf<String>()

    data class UserStatusItem(
        val email: String,
        val name: String,
        val activeDates: List<String>,
        val status: String = "",
        val lastActiveTime: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val root = (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomPadding = Math.max(systemBars.bottom, ime.bottom)
            view.setPadding(0, systemBars.top, 0, bottomPadding)
            insets
        }

        val edtTitle = findViewById<EditText>(R.id.edtAnnouncementTitle)
        val edtBody = findViewById<EditText>(R.id.edtAnnouncementBody)
        val btnPublishGlobal = findViewById<Button>(R.id.btnPublishGlobalAnnouncement)
        val btnPublishAdmin = findViewById<Button>(R.id.btnPublishAdminAnnouncement)

        fun setAnnouncementButtonsEnabled(enabled: Boolean, loadingText: String? = null) {
            btnPublishGlobal.isEnabled = enabled
            btnPublishAdmin.isEnabled = enabled
            if (loadingText != null) {
                if (!enabled) {
                    btnPublishGlobal.text = loadingText
                    btnPublishAdmin.text = loadingText
                } else {
                    btnPublishGlobal.text = "Publish Global"
                    btnPublishAdmin.text = "Publish Admin"
                }
            }
        }

        fun publishAnnouncement(adminOnly: Boolean) {
            val title = edtTitle.text.toString().trim()
            val body = edtBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in both fields")
                return
            }

            setAnnouncementButtonsEnabled(false, "Publishing...")

            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
            val subjectStr = title
            val data = hashMapOf(
                "subject" to subjectStr,
                "query" to body,
                "reply" to "[ANNOUNCEMENT]",
                "status" to "resolved",
                "timestamp" to timestamp,
                "time" to timeStr,
                "read" to false,
                "adminOnly" to adminOnly
            )

            db.collection("announcements").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Announcement Sent")
                    val logType = if (adminOnly) "Admin Announcement" else "Global Announcement"
                    logAdminAction(logType, title, body)
                    edtTitle.text.clear()
                    edtBody.text.clear()
                    setAnnouncementButtonsEnabled(true, "")
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to publish")
                    setAnnouncementButtonsEnabled(true, "")
                }
        }

        btnPublishGlobal.setOnClickListener { publishAnnouncement(false) }
        btnPublishAdmin.setOnClickListener { publishAnnouncement(true) }

        val edtPushTitle = findViewById<EditText>(R.id.edtPushTitle)
        val edtPushBody = findViewById<EditText>(R.id.edtPushBody)
        val btnSendGlobalPush = findViewById<Button>(R.id.btnSendGlobalPush)
        val btnSendAdminPush = findViewById<Button>(R.id.btnSendAdminPush)

        fun setPushButtonsEnabled(enabled: Boolean, loadingText: String? = null) {
            btnSendGlobalPush.isEnabled = enabled
            btnSendAdminPush.isEnabled = enabled
            if (loadingText != null) {
                if (!enabled) {
                    btnSendGlobalPush.text = loadingText
                    btnSendAdminPush.text = loadingText
                } else {
                    btnSendGlobalPush.text = "Send Global Push"
                    btnSendAdminPush.text = "Send Admin Push"
                }
            }
        }

        fun sendPushNotification(adminOnly: Boolean) {
            val title = edtPushTitle.text.toString().trim()
            val body = edtPushBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in both fields")
                return
            }

            setPushButtonsEnabled(false, "Sending...")

            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
            val data = hashMapOf(
                "title" to title,
                "message" to body,
                "timestamp" to timestamp,
                "time" to timeStr,
                "adminOnly" to adminOnly
            )

            db.collection("global_pushes").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Notification Sent")
                    val logType = if (adminOnly) "Admin Push" else "Global Push"
                    logAdminAction(logType, title, body)
                    edtPushTitle.text.clear()
                    edtPushBody.text.clear()
                    setPushButtonsEnabled(true, "")
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to send")
                    setPushButtonsEnabled(true, "")
                }
        }

        btnSendGlobalPush.setOnClickListener { sendPushNotification(false) }
        btnSendAdminPush.setOnClickListener { sendPushNotification(true) }

        val btnAgeSpecificAnnouncement = findViewById<Button>(R.id.btnAgeSpecificAnnouncement)
        btnAgeSpecificAnnouncement.setOnClickListener {
            val intent = android.content.Intent(this, AgeSpecificPushActivity::class.java)
            intent.putExtra("isAnnouncement", true)
            startActivity(intent)
        }

        val btnAgeSpecificNotification = findViewById<Button>(R.id.btnAgeSpecificNotification)
        btnAgeSpecificNotification.setOnClickListener {
            val intent = android.content.Intent(this, AgeSpecificPushActivity::class.java)
            intent.putExtra("isAnnouncement", false)
            startActivity(intent)
        }

        val btnSendUserPush = findViewById<Button>(R.id.btnSendUserPush)
        val btnPublishUserAnnouncement = findViewById<Button>(R.id.btnPublishUserAnnouncement)

        fun sendBatchUserPushes(targetEmails: List<String>, title: String, body: String) {
            setPushButtonsEnabled(false, "Sending...")
            btnSendUserPush.isEnabled = false
            btnSendUserPush.text = "Sending..."

            val db = FirebaseFirestore.getInstance()
            val batch = db.batch()
            val timestamp = System.currentTimeMillis()

            targetEmails.forEachIndexed { index, email ->
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
                    ToastHelper.showToast(this, "Notification Sent")
                    logAdminAction("User Specific Notification", title, body, targetEmails.joinToString(", "))
                    edtPushTitle.text.clear()
                    edtPushBody.text.clear()
                    setPushButtonsEnabled(true, "")
                    btnSendUserPush.isEnabled = true
                    btnSendUserPush.text = "Send User Specific Notifications"
                }
                .addOnFailureListener { e ->
                    ToastHelper.showToast(this, "Failed to send: ${e.message}")
                    setPushButtonsEnabled(true, "")
                    btnSendUserPush.isEnabled = true
                    btnSendUserPush.text = "Send User Specific Notifications"
                }
        }

        fun sendSelectedUserAnnouncement(targetEmails: List<String>, title: String, body: String) {
            setAnnouncementButtonsEnabled(false, "Publishing...")
            btnPublishUserAnnouncement.isEnabled = false
            btnPublishUserAnnouncement.text = "Sending..."

            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
            val data = hashMapOf(
                "subject" to title,
                "query" to body,
                "reply" to "[ANNOUNCEMENT]",
                "status" to "resolved",
                "timestamp" to timestamp,
                "time" to timeStr,
                "read" to false,
                "adminOnly" to false,
                "targetEmails" to targetEmails
            )

            db.collection("announcements").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Announcement Sent")
                    logAdminAction("User Specific Announcement", title, body, targetEmails.joinToString(", "))
                    edtTitle.text.clear()
                    edtBody.text.clear()
                    setAnnouncementButtonsEnabled(true, "")
                    btnPublishUserAnnouncement.isEnabled = true
                    btnPublishUserAnnouncement.text = "Send User Specific Announcements"
                }
                .addOnFailureListener { e ->
                    ToastHelper.showToast(this, "Failed: ${e.message}")
                    setAnnouncementButtonsEnabled(true, "")
                    btnPublishUserAnnouncement.isEnabled = true
                    btnPublishUserAnnouncement.text = "Send User Specific Announcements"
                }
        }

        fun showUserSelectionDialog(isAnnouncement: Boolean) {
            val title = if (isAnnouncement) edtTitle.text.toString().trim() else edtPushTitle.text.toString().trim()
            val body = if (isAnnouncement) edtBody.text.toString().trim() else edtPushBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                val typeStr = if (isAnnouncement) "announcement" else "push"
                ToastHelper.showToast(this, "Please fill in $typeStr title and body first")
                return
            }

            // Custom Progress Dialog
            val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this).let { builder ->
                val padding = (24 * resources.displayMetrics.density).toInt()
                val layout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(padding, padding, padding, padding)
                    val bgResId = ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_transaction)
                    setBackgroundResource(bgResId)
                }
                
                val bar = android.widget.ProgressBar(this).apply {
                    val sizeParams = android.widget.LinearLayout.LayoutParams(
                        (40 * resources.displayMetrics.density).toInt(),
                        (40 * resources.displayMetrics.density).toInt()
                    )
                    layoutParams = sizeParams
                }
                
                val text = android.widget.TextView(this).apply {
                    text = "Fetching users..."
                    setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                    textSize = 16f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = (20 * resources.displayMetrics.density).toInt()
                    }
                }
                
                layout.addView(bar)
                layout.addView(text)
                
                builder.setView(layout)
                builder.setCancelable(false)
                val dialog = builder.create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog
            }
            progressDialog.show()

            val db = FirebaseFirestore.getInstance()
            db.collection("users").get()
                .addOnSuccessListener { querySnapshot ->
                    progressDialog.dismiss()
                    val userTargets = querySnapshot.documents.map { doc ->
                        val email = doc.id
                        val profileName = doc.getString("profileName")
                        val displayName = if (!profileName.isNullOrEmpty()) profileName else email.substringBefore("@")
                        Pair(email, displayName)
                    }.sortedBy { it.second.lowercase() }
                    
                    if (userTargets.isEmpty()) {
                        ToastHelper.showToast(this, "No registered users found")
                        return@addOnSuccessListener
                    }

                    // Keep track of selected emails
                    val selectedEmails = mutableSetOf<String>()

                    // Create searchable dialog
                    val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
                    
                    val pad = (24 * resources.displayMetrics.density).toInt()
                    val container = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(pad, pad, pad, pad)
                        val bgResId = ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_transaction)
                        setBackgroundResource(bgResId)
                    }

                    // Title
                    val titleView = android.widget.TextView(this).apply {
                        text = "Select Target Users"
                        setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 0, 0, (20 * resources.displayMetrics.density).toInt())
                    }
                    container.addView(titleView)

                    // Search input styled beautifully
                    val searchInput = android.widget.EditText(this).apply {
                        hint = "Search email..."
                        setSingleLine(true)
                        setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                        setHintTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                        
                        val inputBgRes = ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_glass_input)
                        background = androidx.core.content.ContextCompat.getDrawable(this@AdminActivity, inputBgRes)
                        
                        setPadding(40, 40, 40, 40)
                        val layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        layoutParams.bottomMargin = (16 * resources.displayMetrics.density).toInt()
                        this.layoutParams = layoutParams
                    }
                    container.addView(searchInput)

                    // User List
                    val scrollView = android.widget.ScrollView(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            weight = 1f
                        }
                    }
                    val listView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    scrollView.addView(listView)
                    container.addView(scrollView)

                    dialogBuilder.setView(container)

                    val originalList = userTargets.toMutableList()
                    val filteredList = userTargets.toMutableList()
                    
                    fun refreshUserList() {
                        listView.removeAllViews()
                        val density = resources.displayMetrics.density
                        for (target in filteredList) {
                            val email = target.first
                            val displayName = target.second
                            
                            val itemLayout = android.widget.LinearLayout(this@AdminActivity).apply {
                                orientation = android.widget.LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(
                                    (16 * density).toInt(),
                                    (12 * density).toInt(),
                                    (16 * density).toInt(),
                                    (12 * density).toInt()
                                )
                                
                                val cb = android.widget.CheckBox(this@AdminActivity).apply {
                                    buttonTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                                    isFocusable = false
                                    isClickable = false
                                    isChecked = selectedEmails.contains(email)
                                }
                                val tv = android.widget.TextView(this@AdminActivity).apply {
                                    setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                                    setPadding((12 * density).toInt(), 0, 0, 0)
                                    text = "$displayName - $email"
                                }
                                addView(cb)
                                addView(tv)
                                
                                setOnClickListener {
                                    cb.isChecked = !cb.isChecked
                                    if (cb.isChecked) {
                                        selectedEmails.add(email)
                                    } else {
                                        selectedEmails.remove(email)
                                    }
                                }
                            }
                            listView.addView(itemLayout)
                        }
                    }
                    refreshUserList()

                    // Action buttons container at bottom
                    val actionContainer = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (16 * resources.displayMetrics.density).toInt()
                        }
                    }

                    val btnCancel = androidx.appcompat.widget.AppCompatButton(this).apply {
                        text = "Cancel"
                        setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                        setBackgroundResource(ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_card_dark))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            (50 * resources.displayMetrics.density).toInt(),
                            1f
                        ).apply {
                            marginEnd = (8 * resources.displayMetrics.density).toInt()
                        }
                    }
                    actionContainer.addView(btnCancel)

                    val btnNext = androidx.appcompat.widget.AppCompatButton(this).apply {
                        text = "Next"
                        setTextColor(androidx.core.content.ContextCompat.getColor(this@AdminActivity, android.R.color.white))
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textGreenColor)
                        )
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            (50 * resources.displayMetrics.density).toInt(),
                            1f
                        ).apply {
                            marginStart = (8 * resources.displayMetrics.density).toInt()
                        }
                    }
                    actionContainer.addView(btnNext)
                    container.addView(actionContainer)

                    val dialog = dialogBuilder.create()
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    
                    searchInput.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val query = s.toString().lowercase()
                            filteredList.clear()
                            if (query.isEmpty()) {
                                filteredList.addAll(originalList)
                            } else {
                                filteredList.addAll(originalList.filter { 
                                    it.first.lowercase().contains(query) || it.second.lowercase().contains(query)
                                })
                            }
                            refreshUserList()
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })


                    dialog.show()
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    ToastHelper.showToast(this, "Failed to fetch users")
                }
        }

        btnSendUserPush.setOnClickListener { showUserSelectionDialog(false) }
        btnPublishUserAnnouncement.setOnClickListener { showUserSelectionDialog(true) }

        val btnHistory = findViewById<ImageButton>(R.id.btnHistory)
        btnHistory.setOnClickListener {
            val intent = android.content.Intent(this, AdminLogsActivity::class.java)
            startActivity(intent)
        }

        val btnSelectDate = findViewById<android.widget.TextView>(R.id.btnSelectDate)
        btnSelectDate.setOnClickListener {
            val year = selectedDateCalendar.get(Calendar.YEAR)
            val month = selectedDateCalendar.get(Calendar.MONTH)
            val day = selectedDateCalendar.get(Calendar.DAY_OF_MONTH)

            val dpd = DatePickerDialog(this, ThemeHelper.getDatePickerTheme(this), { _, selectedYear, selectedMonth, selectedDay ->
                selectedDateCalendar.set(Calendar.YEAR, selectedYear)
                selectedDateCalendar.set(Calendar.MONTH, selectedMonth)
                selectedDateCalendar.set(Calendar.DAY_OF_MONTH, selectedDay)
                refreshUserStatusList()
            }, year, month, day)
            dpd.show()
        }

        loadUserStatusList()
        loadSupportInbox()
    }

    private fun loadSupportInbox() {
        val inboxContainer = findViewById<LinearLayout>(R.id.layoutSupportInbox) ?: return
        val tvEmpty = findViewById<TextView>(R.id.tvInboxEmpty) ?: return
        inboxContainer.removeAllViews()
        tvEmpty.text = "Loading..."
        tvEmpty.visibility = View.VISIBLE
        // Immediately hide Resolve Selected button on every fresh load
        findViewById<android.widget.Button>(R.id.btnResolveAll)?.visibility = View.GONE

        val db = FirebaseFirestore.getInstance()
        val density = resources.displayMetrics.density

        data class QueryItem(
            val userEmail: String,
            val userName: String,
            val docId: String,
            val subject: String,
            val timestamp: Long
        )

        db.collection("users").get()
            .addOnSuccessListener { usersSnap ->
                val allItems = mutableListOf<QueryItem>()
                val userEmails = usersSnap.documents.map { it.id }
                if (userEmails.isEmpty()) {
                    tvEmpty.text = "No users found."
                    return@addOnSuccessListener
                }

                var pendingFetches = userEmails.size

                fun showCustomResolveDialog(targetItems: List<QueryItem>, onResolved: () -> Unit) {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
                    val confirmDialog = androidx.appcompat.app.AlertDialog.Builder(this@AdminActivity)
                        .setView(dialogView)
                        .create()
                    confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmTitle).text = "Resolve Queries"
                    val countStr = if (targetItems.size == 1) "1 query" else "${targetItems.size} queries"
                    dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmMessage).text =
                        "Mark $countStr as resolved without reply?"

                    val tvUsers = dialogView.findViewById<android.widget.TextView>(R.id.tvTargetUsers)
                    if (tvUsers != null) {
                        tvUsers.text = targetItems.joinToString("\n") { "• ${it.subject.ifEmpty { it.userEmail }}" }
                        tvUsers.visibility = android.view.View.VISIBLE
                    }

                    val btnYes = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmAction)
                    val btnNo = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmCancel)

                    btnYes.text = "Resolve"
                    btnYes.setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                    btnYes.setOnClickListener {
                        confirmDialog.dismiss()
                        val batch = db.batch()
                        val now = System.currentTimeMillis()
                        for (item in targetItems) {
                            val docRef = db.collection("users").document(item.userEmail)
                                .collection("notifications").document(item.docId)
                            batch.update(docRef, mapOf(
                                "status" to "resolved",
                                "reply" to "This query has been marked as resolved by the admin.",
                                "read" to false,
                                "replyTimestamp" to now,
                                "timestamp" to now
                            ))
                        }
                        batch.commit()
                            .addOnSuccessListener {
                                ToastHelper.showToast(this@AdminActivity, "$countStr resolved!")
                                selectedQueries.clear()
                                onResolved()
                            }
                            .addOnFailureListener { e ->
                                ToastHelper.showToast(this@AdminActivity, "Failed: ${e.message}")
                            }
                    }
                    btnNo.setOnClickListener { confirmDialog.dismiss() }
                    confirmDialog.show()
                }

                fun onAllFetched() {
                    pendingFetches--
                    if (pendingFetches > 0) return

                    runOnUiThread {
                        val btnResolveSelected = findViewById<android.widget.Button>(R.id.btnResolveAll)
                        inboxContainer.removeAllViews()
                        selectedQueries.clear()

                        if (allItems.isEmpty()) {
                            tvEmpty.text = "✅ All queries replied — inbox is empty!"
                            tvEmpty.visibility = View.VISIBLE
                            btnResolveSelected?.visibility = View.GONE
                            return@runOnUiThread
                        }
                        tvEmpty.visibility = View.GONE

                        // Setup Resolve Selected button
                        if (btnResolveSelected != null) {
                            btnResolveSelected.visibility = View.GONE
                            btnResolveSelected.setOnClickListener {
                                val targets = allItems.filter { selectedQueries.contains(it.docId) }
                                if (targets.isEmpty()) {
                                    ToastHelper.showToast(this@AdminActivity, "Select at least one query")
                                    return@setOnClickListener
                                }
                                showCustomResolveDialog(targets) { loadSupportInbox() }
                            }
                        }

                        allItems.sortByDescending { it.timestamp }

                        for (item in allItems) {
                            val replyUrl = "https://adminreply-khhfw7mtba-uc.a.run.app" +
                                "?uid=${item.userEmail}&id=${item.docId}&email=${item.userEmail}"

                            val card = LinearLayout(this@AdminActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.TOP
                                setPadding(
                                    (12 * density).toInt(), (14 * density).toInt(),
                                    (16 * density).toInt(), (14 * density).toInt()
                                )
                                background = androidx.core.content.ContextCompat.getDrawable(
                                    this@AdminActivity,
                                    ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_transaction)
                                )
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (10 * density).toInt() }
                            }

                            // Left checkbox
                            val checkBox = android.widget.CheckBox(this@AdminActivity).apply {
                                buttonTintList = android.content.res.ColorStateList.valueOf(
                                    ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor)
                                )
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    rightMargin = (8 * density).toInt()
                                    topMargin = (2 * density).toInt()
                                }
                                isChecked = selectedQueries.contains(item.docId)
                            }
                            checkBox.setOnCheckedChangeListener { _, isChecked ->
                                if (isChecked) selectedQueries.add(item.docId)
                                else selectedQueries.remove(item.docId)
                                // Show/hide Resolve Selected button
                                btnResolveSelected?.visibility =
                                    if (selectedQueries.isEmpty()) View.GONE else View.VISIBLE
                            }
                            card.addView(checkBox)

                            // Right content column
                            val contentCol = LinearLayout(this@AdminActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            // Subject row with red dot
                            val headerRow = LinearLayout(this@AdminActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (4 * density).toInt() }
                            }
                            val redDot = View(this@AdminActivity).apply {
                                val sz = (8 * density).toInt()
                                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                                    rightMargin = (8 * density).toInt()
                                }
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.OVAL
                                    setColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textRedColor))
                                }
                            }
                            val tvSubject = TextView(this@AdminActivity).apply {
                                text = item.subject.ifEmpty { "(No Subject)" }
                                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                                textSize = 15f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            headerRow.addView(redDot)
                            headerRow.addView(tvSubject)
                            contentCol.addView(headerRow)

                            val tvName = TextView(this@AdminActivity).apply {
                                text = "👤 ${item.userName.ifEmpty { "Unknown" }}"
                                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                                textSize = 13f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (2 * density).toInt() }
                            }
                            contentCol.addView(tvName)

                            val tvEmail = TextView(this@AdminActivity).apply {
                                text = "✉️ ${item.userEmail}"
                                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                                textSize = 13f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (2 * density).toInt() }
                            }
                            contentCol.addView(tvEmail)

                            val tvTime = TextView(this@AdminActivity).apply {
                                val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
                                val formattedTime = if (item.timestamp > 0) sdf.format(Date(item.timestamp)) else "Unknown time"
                                text = "🕒 $formattedTime"
                                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                                textSize = 13f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (8 * density).toInt() }
                            }
                            contentCol.addView(tvTime)

                            val btnOpen = Button(this@AdminActivity).apply {
                                text = "🔗 Open Reply Page"
                                isAllCaps = false
                                setTextColor(android.graphics.Color.WHITE)
                                backgroundTintList = android.content.res.ColorStateList.valueOf(
                                    android.graphics.Color.parseColor("#7C83FF")
                                )
                                textSize = 14f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    (48 * density).toInt()
                                )
                                setOnClickListener {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(replyUrl))
                                    startActivity(intent)
                                }
                            }
                            contentCol.addView(btnOpen)

                            card.addView(contentCol)
                            inboxContainer.addView(card)
                        }
                    }
                }

                for (userEmail in userEmails) {
                    db.collection("users").document(userEmail)
                        .collection("notifications")
                        .whereEqualTo("reply", "Waiting for reply...")
                        .whereEqualTo("status", "pending")
                        .get()
                        .addOnSuccessListener { notifSnap ->
                            for (doc in notifSnap.documents) {
                                val subject = doc.getString("subject")
                                    ?: doc.getString("originalSubject") ?: ""
                                val name = doc.getString("name") ?: ""
                                val ts = doc.getLong("timestamp") ?: 0L
                                allItems.add(QueryItem(userEmail, name, doc.id, subject, ts))
                            }
                            onAllFetched()
                        }
                        .addOnFailureListener { onAllFetched() }
                }
            }
            .addOnFailureListener {
                tvEmpty.text = "Failed to load inbox."
            }
    }

    private fun logAdminAction(actionType: String, title: String, message: String, details: String? = null) {

        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val adminEmail = user?.email ?: "Unknown Admin"
        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()

        val logData = hashMapOf(
            "adminEmail" to adminEmail,
            "actionType" to actionType,
            "title" to title,
            "message" to message,
            "timestamp" to timestamp
        )
        if (details != null) {
            logData["details"] = details
        }

        db.collection("admin_logs").document(timestamp.toString()).set(logData)
            .addOnFailureListener { e ->
                android.util.Log.e("AdminActivity", "Failed to write admin log: ${e.message}")
            }
    }

    private fun loadUserStatusList() {
        val db = FirebaseFirestore.getInstance()
        usersListenerRegistration?.remove()
        usersListenerRegistration = db.collection("users").addSnapshotListener { querySnapshot, error ->
            if (error != null) {
                android.util.Log.e("AdminActivity", "Failed to load users: ${error.message}")
                return@addSnapshotListener
            }
            if (querySnapshot == null) return@addSnapshotListener

            userStatusList.clear()
            val emailsMissingName = mutableListOf<String>()

            for (doc in querySnapshot.documents) {
                val email = doc.id
                val name = doc.getString("name")
                val activeDates = doc.get("activeDates") as? List<String> ?: emptyList()
                val status = doc.getString("status") ?: ""
                val lastActiveTime = doc.getString("lastActiveTime") ?: ""
                userStatusList.add(UserStatusItem(email, name ?: "", activeDates, status, lastActiveTime))
                if (name.isNullOrEmpty()) emailsMissingName.add(email)
            }

            if (emailsMissingName.isEmpty()) {
                refreshUserStatusList()
                return@addSnapshotListener
            }

            // Show what we have immediately, then patch names from profile subcollection
            refreshUserStatusList()

            var pendingCount = emailsMissingName.size
            for (email in emailsMissingName) {
                db.collection("users").document(email)
                    .collection("config").document("profile")
                    .get()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val profileName = task.result?.getString("name")?.takeIf { it.isNotEmpty() }
                            val displayName = profileName ?: email.substringBefore("@")
                            val idx = userStatusList.indexOfFirst { it.email == email }
                            if (idx >= 0) {
                                userStatusList[idx] = userStatusList[idx].copy(name = displayName)
                            }
                        }
                        pendingCount--
                        if (pendingCount == 0) refreshUserStatusList()
                    }
            }
        }
    }

    private fun refreshUserStatusList() {
        val container = findViewById<LinearLayout>(R.id.layoutUserStatusContainer) ?: return
        container.removeAllViews()

        val btnSelectDate = findViewById<android.widget.TextView>(R.id.btnSelectDate)

        val todayCal = Calendar.getInstance()
        val isToday = todayCal.get(Calendar.YEAR) == selectedDateCalendar.get(Calendar.YEAR) &&
                todayCal.get(Calendar.MONTH) == selectedDateCalendar.get(Calendar.MONTH) &&
                todayCal.get(Calendar.DAY_OF_MONTH) == selectedDateCalendar.get(Calendar.DAY_OF_MONTH)

        val dateTextFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(selectedDateCalendar.time)
        btnSelectDate?.text = if (isToday) "Date: Today ($dateTextFormat)" else "Date: $dateTextFormat"

        val formattedQueryDateNew = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(selectedDateCalendar.time)

        val formattedQueryDateOld = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(selectedDateCalendar.time)

        val density = resources.displayMetrics.density

        if (userStatusList.isEmpty()) {
            val tvEmpty = android.widget.TextView(this).apply {
                text = "No users loaded."
                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
            }
            container.addView(tvEmpty)
            return
        }

        userStatusList.sortBy { it.name.lowercase() }

        // Today's date string in IST (dd/MM/yyyy) — matches lastActiveTime format
        val todayIstShort = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date())

        // Find duplicate names (case-insensitive) to show email for disambiguation
        val nameCounts = mutableMapOf<String, Int>()
        userStatusList.forEach { nameCounts[it.name.lowercase()] = (nameCounts[it.name.lowercase()] ?: 0) + 1 }
        val duplicateNames = nameCounts.filter { it.value > 1 }.keys

        userStatusList.forEach { item ->
            val isActive = if (isToday) {
                // For today: activeDates array OR lastActiveTime is today
                item.activeDates.contains(formattedQueryDateNew) ||
                item.activeDates.contains(formattedQueryDateOld) ||
                item.lastActiveTime.startsWith(todayIstShort)
            } else {
                item.activeDates.contains(formattedQueryDateNew) ||
                item.activeDates.contains(formattedQueryDateOld)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            }

            // Grey dot
            val dot = android.view.View(this).apply {
                val size = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = (12 * density).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.GRAY)
                }
            }
            row.addView(dot)

            // Theme-aware text username (show email if name is duplicated)
            val displayName = if (duplicateNames.contains(item.name.lowercase())) {
                "${item.name} (${item.email})"
            } else {
                item.name
            }
            val tvUsername = android.widget.TextView(this).apply {
                text = displayName
                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvUsername)

            // Opened/Not Opened status
            val tvStatus = android.widget.TextView(this).apply {
                text = if (isActive) "Opened" else "Not Opened"
                setTextColor(if (isActive) ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textGreenColor) else ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textRedColor))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            row.addView(tvStatus)

            container.addView(row)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usersListenerRegistration?.remove()
    }
}
