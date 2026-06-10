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

        val root = findViewById<android.view.View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomPadding = Math.max(systemBars.bottom, ime.bottom)
            view.setPadding(0, 0, 0, bottomPadding)
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
            val subjectStr = if (adminOnly) "🛡️ [Admin Only] $title" else "📣 $title"
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
                    ToastHelper.showToast(this, "Announcement Published!")
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
                    ToastHelper.showToast(this, "Push Notification Sent!")
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

        fun sendUserPushNotification(targetEmail: String) {
            val title = edtPushTitle.text.toString().trim()
            val body = edtPushBody.text.toString().trim()

            setPushButtonsEnabled(false, "Sending...")
            btnSendUserPush.isEnabled = false
            btnSendUserPush.text = "Sending..."

            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            val data = hashMapOf(
                "email" to targetEmail,
                "title" to title,
                "message" to body,
                "timestamp" to timestamp
            )

            db.collection("user_pushes").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Push Sent to $targetEmail!")
                    logAdminAction("User Specific Push", title, body, targetEmail)
                    edtPushTitle.text.clear()
                    edtPushBody.text.clear()
                    setPushButtonsEnabled(true, "")
                    btnSendUserPush.isEnabled = true
                    btnSendUserPush.text = "Send User Specific Push"
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to send user push")
                    setPushButtonsEnabled(true, "")
                    btnSendUserPush.isEnabled = true
                    btnSendUserPush.text = "Send User Specific Push"
                }
        }

        fun showUserSelectionDialog() {
            val title = edtPushTitle.text.toString().trim()
            val body = edtPushBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in push title and body first")
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
                        text = "Select Target User"
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
                    
                    // Styled custom adapter
                    val listAdapter = object : android.widget.ArrayAdapter<String>(this@AdminActivity, android.R.layout.simple_list_item_1, filteredList) {
                        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                            val view = super.getView(position, convertView, parent) as android.widget.TextView
                            view.setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
                            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                            // Clean item padding
                            view.setPadding(
                                (12 * resources.displayMetrics.density).toInt(),
                                (14 * resources.displayMetrics.density).toInt(),
                                (12 * resources.displayMetrics.density).toInt(),
                                (14 * resources.displayMetrics.density).toInt()
                            )
                            return view
                        }
                    }
                    listView.adapter = listAdapter

                    val dialog = dialogBuilder.create()
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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
                        val selectedEmail = filteredList[position]
                        dialog.dismiss()

                        // Ask for confirmation using dialog_confirm_action custom dialog layout
                        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
                        val confirmDialog = androidx.appcompat.app.AlertDialog.Builder(this@AdminActivity)
                            .setView(dialogView)
                            .create()
                        
                        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                        dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmTitle).text = "Confirm Send"
                        dialogView.findViewById<android.widget.TextView>(R.id.tvConfirmMessage).text = "Send this push notification to $selectedEmail?"
                        
                        val btnYes = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmAction)
                        val btnNo = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmCancel)

                        btnYes.text = "Send"
                        btnYes.setTextColor(android.graphics.Color.WHITE)
                        btnYes.setOnClickListener {
                            confirmDialog.dismiss()
                            sendUserPushNotification(selectedEmail)
                        }
                        
                        btnNo.setOnClickListener { confirmDialog.dismiss() }
                        
                        confirmDialog.show()
                    }

                    dialog.show()
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    ToastHelper.showToast(this, "Failed to fetch users")
                }
        }

        btnSendUserPush.setOnClickListener { showUserSelectionDialog() }

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
