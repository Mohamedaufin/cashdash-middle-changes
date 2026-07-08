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
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
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
    private val rtdbPresenceMap = mutableMapOf<String, Pair<String, String>>()

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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val root = (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomPadding = Math.max(systemBars.bottom, ime.bottom)
            view.setPadding(0, systemBars.top, 0, bottomPadding)
            insets
        }

        FirebaseDatabase.getInstance().getReference("status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rtdbPresenceMap.clear()
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy, h:mm a", java.util.Locale.ENGLISH)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                for (child in snapshot.children) {
                    val safeEmail = child.key ?: continue
                    val email = safeEmail.replace(",", ".")
                    val state = child.child("state").getValue(String::class.java) ?: "Offline"
                    val lastChanged = child.child("last_changed").getValue(Long::class.java)
                    val lastActiveTime = if (lastChanged != null) sdf.format(java.util.Date(lastChanged)) else "Never"
                    rtdbPresenceMap[email] = Pair(state, lastActiveTime)
                }
                
                // Live update the list with new RTDB data
                for (i in userStatusList.indices) {
                    val rtdbData = rtdbPresenceMap[userStatusList[i].email]
                    if (rtdbData != null) {
                        userStatusList[i] = userStatusList[i].copy(status = rtdbData.first, lastActiveTime = rtdbData.second)
                    } else {
                        userStatusList[i] = userStatusList[i].copy(status = "Offline", lastActiveTime = "Never")
                    }
                }
                refreshUserStatusList()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        findViewById<View>(R.id.btnGoToAnnouncements).setOnClickListener {
            val intent = Intent(this, AdminMessagingActivity::class.java)
            intent.putExtra("isAnnouncement", true)
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGoToPushNotifications).setOnClickListener {
            val intent = Intent(this, AdminMessagingActivity::class.java)
            intent.putExtra("isAnnouncement", false)
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGoToPromotions).setOnClickListener {
            val intent = Intent(this, AdminPromotionsActivity::class.java)
            startActivity(intent)
        }

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
                
                val rtdbData = rtdbPresenceMap[email]
                val status = rtdbData?.first ?: "Offline"
                val lastActiveTime = rtdbData?.second ?: "Never"
                
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
