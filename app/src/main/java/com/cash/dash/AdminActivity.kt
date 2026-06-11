package com.cash.dash

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.LinearLayout
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminActivity : ThemedActivity() {

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
            val subjectStr = title
            val data = hashMapOf(
                "subject" to subjectStr,
                "query" to body,
                "reply" to "[ANNOUNCEMENT]",
                "status" to "resolved",
                "timestamp" to timestamp,
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
            val data = hashMapOf(
                "title" to title,
                "message" to body,
                "timestamp" to timestamp,
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
            val data = hashMapOf(
                "subject" to title,
                "query" to body,
                "reply" to "[ANNOUNCEMENT]",
                "status" to "resolved",
                "timestamp" to timestamp,
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
                    val emails = querySnapshot.documents.map { it.id }.sorted()
                    if (emails.isEmpty()) {
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

                    // ListView
                    val listView = android.widget.ListView(this).apply {
                        val layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (300 * resources.displayMetrics.density).toInt()
                        )
                        this.layoutParams = layoutParams
                        divider = null
                        dividerHeight = 0
                    }
                    container.addView(listView)

                    dialogBuilder.setView(container)

                    val originalList = emails.toMutableList()
                    val filteredList = emails.toMutableList()
                    
                    // Styled custom adapter with Checkboxes
                    val listAdapter = object : android.widget.ArrayAdapter<String>(this@AdminActivity, 0, filteredList) {
                        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                            val context = this@AdminActivity
                            val density = context.resources.displayMetrics.density
                            
                            val layout = (convertView as? android.widget.LinearLayout) ?: android.widget.LinearLayout(context).apply {
                                orientation = android.widget.LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(
                                    (16 * density).toInt(),
                                    (12 * density).toInt(),
                                    (16 * density).toInt(),
                                    (12 * density).toInt()
                                )
                                
                                val cb = android.widget.CheckBox(context).apply {
                                    buttonTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                                    isFocusable = false
                                    isClickable = false
                                }
                                val tv = android.widget.TextView(context).apply {
                                    setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                                    setPadding((12 * density).toInt(), 0, 0, 0)
                                }
                                addView(cb)
                                addView(tv)
                            }
                            
                            val cb = layout.getChildAt(0) as android.widget.CheckBox
                            val tv = layout.getChildAt(1) as android.widget.TextView
                            
                            val email = filteredList[position]
                            tv.text = email
                            cb.isChecked = selectedEmails.contains(email)
                            
                            return layout
                        }
                    }
                    listView.adapter = listAdapter

                    // Action buttons container at bottom
                    val buttonContainer = android.widget.LinearLayout(this@AdminActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val btnCancel = android.widget.Button(this@AdminActivity).apply {
                        text = "Cancel"
                        isAllCaps = false
                        setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                        background = androidx.core.content.ContextCompat.getDrawable(this@AdminActivity, ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_3d_card))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            (48 * resources.displayMetrics.density).toInt(),
                            1f
                        ).apply {
                            rightMargin = (8 * resources.displayMetrics.density).toInt()
                        }
                    }

                    val btnSendSelectedPush = android.widget.Button(this@AdminActivity).apply {
                        text = "Send"
                        isAllCaps = false
                        setTextColor(android.graphics.Color.WHITE)
                        background = androidx.core.content.ContextCompat.getDrawable(this@AdminActivity, ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_3d_card))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            (48 * resources.displayMetrics.density).toInt(),
                            1.2f
                        ).apply {
                            rightMargin = (8 * resources.displayMetrics.density).toInt()
                        }
                    }

                    val btnSendSelectedAnnounce = android.widget.Button(this@AdminActivity).apply {
                        text = "Send"
                        isAllCaps = false
                        setTextColor(android.graphics.Color.WHITE)
                        background = androidx.core.content.ContextCompat.getDrawable(this@AdminActivity, ThemeHelper.getDrawable(this@AdminActivity, R.drawable.bg_3d_card))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            (48 * resources.displayMetrics.density).toInt(),
                            1.2f
                        )
                    }

                    if (isAnnouncement) {
                        btnSendSelectedPush.visibility = android.view.View.GONE
                    } else {
                        btnSendSelectedAnnounce.visibility = android.view.View.GONE
                        // adjust margin since only 2 buttons visible now
                        val params = btnSendSelectedPush.layoutParams as android.widget.LinearLayout.LayoutParams
                        params.rightMargin = 0
                        btnSendSelectedPush.layoutParams = params
                    }

                    buttonContainer.addView(btnCancel)
                    buttonContainer.addView(btnSendSelectedPush)
                    buttonContainer.addView(btnSendSelectedAnnounce)
                    container.addView(buttonContainer)

                    val dialog = dialogBuilder.create()
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    btnCancel.setOnClickListener { dialog.dismiss() }

                    btnSendSelectedPush.setOnClickListener {
                        if (selectedEmails.isEmpty()) {
                            ToastHelper.showToast(this@AdminActivity, "Please select at least one user")
                            return@setOnClickListener
                        }
                        dialog.dismiss()

                        // Ask for confirmation
                        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
                        val confirmDialog = androidx.appcompat.app.AlertDialog.Builder(this@AdminActivity)
                            .setView(dialogView)
                            .create()
                        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                        dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmTitle).text = "Confirm Notification"
                        val msgSuffix = if (selectedEmails.size == 1) "user?" else "users?"
                        dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmMessage).text = "Send notification to the selected $msgSuffix"
                        
                        val tvUsers = dialogView.findViewById<android.widget.TextView>(R.id.tvTargetUsers)
                        if (tvUsers != null) {
                            val usersText = if (selectedEmails.size == 1) {
                                "• ${selectedEmails.first()}"
                            } else {
                                selectedEmails.joinToString("\n") { "• $it" }
                            }
                            tvUsers.text = usersText
                            tvUsers.visibility = android.view.View.VISIBLE
                        }
                        
                        val btnYes = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmAction)
                        val btnNo = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmCancel)

                        btnYes.text = "Send"
                        btnYes.setTextColor(android.graphics.Color.WHITE)
                        btnYes.setOnClickListener {
                            confirmDialog.dismiss()
                            sendBatchUserPushes(selectedEmails.toList(), title, body)
                        }
                        btnNo.setOnClickListener { confirmDialog.dismiss() }
                        confirmDialog.show()
                    }

                    btnSendSelectedAnnounce.setOnClickListener {
                        if (selectedEmails.isEmpty()) {
                            ToastHelper.showToast(this@AdminActivity, "Please select at least one user")
                            return@setOnClickListener
                        }
                        dialog.dismiss()

                        // Ask for confirmation
                        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
                        val confirmDialog = androidx.appcompat.app.AlertDialog.Builder(this@AdminActivity)
                            .setView(dialogView)
                            .create()
                        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                        dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmTitle).text = "Confirm Announcement"
                        val msgSuffix = if (selectedEmails.size == 1) "user?" else "users?"
                        dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmMessage).text = "Send announcement to the selected $msgSuffix"
                        
                        val tvUsers = dialogView.findViewById<android.widget.TextView>(R.id.tvTargetUsers)
                        if (tvUsers != null) {
                            val usersText = if (selectedEmails.size == 1) {
                                "• ${selectedEmails.first()}"
                            } else {
                                selectedEmails.joinToString("\n") { "• $it" }
                            }
                            tvUsers.text = usersText
                            tvUsers.visibility = android.view.View.VISIBLE
                        }
                        
                        val btnYes = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmAction)
                        val btnNo = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmCancel)

                        btnYes.text = "Publish"
                        btnYes.setTextColor(android.graphics.Color.WHITE)
                        btnYes.setOnClickListener {
                            confirmDialog.dismiss()
                            sendSelectedUserAnnouncement(selectedEmails.toList(), title, body)
                        }
                        btnNo.setOnClickListener { confirmDialog.dismiss() }
                        confirmDialog.show()
                    }

                    searchInput.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val text = s?.toString()?.trim() ?: ""
                            filteredList.clear()
                            if (text.isEmpty()) {
                                filteredList.addAll(originalList)
                            } else {
                                filteredList.addAll(originalList.filter { it.contains(text, ignoreCase = true) })
                            }
                            listAdapter.notifyDataSetChanged()
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })

                    listView.setOnItemClickListener { _, _, position, _ ->
                        val email = filteredList[position]
                        if (selectedEmails.contains(email)) {
                            selectedEmails.remove(email)
                        } else {
                            selectedEmails.add(email)
                        }
                        listAdapter.notifyDataSetChanged()
                    }

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
}
