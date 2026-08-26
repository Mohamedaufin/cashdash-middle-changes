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
    private val rtdbPresenceMap = mutableMapOf<String, Pair<String, String>>()
    private val currentAdminEmails = mutableSetOf<String>()
    
    private var adminRequestsListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private val pendingAdminRequests = mutableMapOf<String, String>()

    private var isFirstPermissionCheck = true
    private val permissionListener: (AdminManager.AdminPermissions) -> Unit = { perms ->
        // Skip the first synchronous invocation before Firestore has responded.
        if (!isFirstPermissionCheck) {
            if (!perms.hasAnyAccess) {
                ToastHelper.showToast(this, "Permission denied")
                finish()
            } else {
                val lastSeenContainer = findViewById<LinearLayout>(R.id.layoutUserStatusContainer)
                if (!perms.canViewLastSeen()) {
                    lastSeenContainer?.removeAllViews()
                    val tv = TextView(this).apply {
                        text = "Permission denied to view Last Seen status."
                        setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                        setPadding(16, 16, 16, 16)
                    }
                    lastSeenContainer?.addView(tv)
                } else if (lastSeenContainer?.childCount == 1 && (lastSeenContainer.getChildAt(0) as? TextView)?.text?.contains("Permission denied") == true) {
                    // Restore if it was previously denied
                    loadUserStatusList()
                }
            }
        }
        isFirstPermissionCheck = false
    }

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

        AdminManager.addListener(permissionListener)

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
            val p = AdminManager.getPermissions()
            if (!p.isOwner && !p.canSendAnnouncements()) {
                ToastHelper.showToast(this, "Permission denied")
                return@setOnClickListener
            }
            val intent = Intent(this, AdminMessagingActivity::class.java)
            intent.putExtra("isAnnouncement", true)
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGoToPushNotifications).setOnClickListener {
            val p = AdminManager.getPermissions()
            if (!p.isOwner && !p.canSendNotifications()) {
                ToastHelper.showToast(this, "Permission denied")
                return@setOnClickListener
            }
            val intent = Intent(this, AdminMessagingActivity::class.java)
            intent.putExtra("isAnnouncement", false)
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGoToPromotions).setOnClickListener {
            val p = AdminManager.getPermissions()
            if (!p.isOwner && !p.canSendPromotions()) {
                ToastHelper.showToast(this, "Permission denied")
                return@setOnClickListener
            }
            val intent = Intent(this, AdminPromotionsActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGoToManageAdminAccess)?.setOnClickListener {
            val intent = Intent(this, ManageAdminAccessActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGoToUpdateLock)?.setOnClickListener {
            val p = AdminManager.getPermissions()
            if (!p.isOwner && !p.canAllocateAdmins()) {
                ToastHelper.showToast(this, "Permission denied")
                return@setOnClickListener
            }
            showUpdateLockDialog()
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


                fun onAllFetched() {
                    pendingFetches--
                    if (pendingFetches > 0) return

                    runOnUiThread {
                        inboxContainer.removeAllViews()

                        if (allItems.isEmpty()) {
                            tvEmpty.text = "✅ All queries replied — inbox is empty!"
                            tvEmpty.visibility = View.VISIBLE
                            return@runOnUiThread
                        }
                        tvEmpty.visibility = View.GONE

                        allItems.sortByDescending { it.timestamp }

                        for (item in allItems) {

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

                            // Right content column
                            val contentCol = LinearLayout(this@AdminActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
                                    SupportReplyLink.openReplyPage(
                                        this@AdminActivity,
                                        item.userEmail,
                                        item.docId
                                    )
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

    /**
     * Sends a formal in-app notification to a specific user's notifications subcollection.
     * This appears in their Notification Activity just like an announcement.
     */
    private fun sendAdminInAppNotification(targetEmail: String, subject: String, body: String) {
        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(timestamp)
        val notifData = hashMapOf<String, Any>(
            "subject"   to subject,
            "query"     to body,
            "reply"     to "[ANNOUNCEMENT]",
            "status"    to "resolved",
            "timestamp" to timestamp,
            "time"      to timeStr,
            "read"      to false,
            "promo_id"  to timestamp.toString()
        )
        db.collection("users").document(targetEmail.lowercase())
            .collection("notifications").document(timestamp.toString())
            .set(notifData)
            .addOnFailureListener { e ->
                android.util.Log.e("AdminActivity", "Failed to send admin notification: ${e.message}")
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
                @Suppress("UNCHECKED_CAST")
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

        if (!AdminManager.getPermissions().canViewLastSeen()) {
            val tv = TextView(this).apply {
                text = "Permission denied to view Last Seen status."
                setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textMutedColor))
                setPadding(16, 16, 16, 16)
            }
            container.addView(tv)
            return
        }

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
        AdminManager.removeListener(permissionListener)
        usersListenerRegistration?.remove()
        adminAccessListenerRegistration?.remove()
        adminRequestsListenerRegistration?.remove()
    }

    private var adminAccessListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    private fun setupAdminAccessListener() {
        val container = findViewById<LinearLayout>(R.id.layoutAdminAccess) ?: return
        val db = FirebaseFirestore.getInstance()
        
        container.removeAllViews()
        
        fun inflateSuperAdmins() {
            for (superEmail in listOf("arunbhalaji200904@gmail.com", "mohamedaufin64@gmail.com")) {
                val foundUser = userStatusList.find { it.email.lowercase() == superEmail }
                var name = foundUser?.name
                if (name.isNullOrBlank()) {
                    name = if (superEmail == "arunbhalaji200904@gmail.com") "Arun" else "Mohamed Aufin"
                }
                addAdminRow(container, superEmail, name ?: "Unknown", "Owner", AdminManager.AdminPermissions(isFixedOwner = true))
            }
        }
        
        // Always inflate super admins immediately in case the listener fails due to Firestore rules.
        inflateSuperAdmins()
        
        fun renderAdminsList(snapshot: com.google.firebase.firestore.QuerySnapshot) {
            container.removeAllViews()
            inflateSuperAdmins()
            
            val renderedEmails = mutableSetOf<String>()
            
            currentAdminEmails.clear()
            AdminManager.superAdmins.forEach { currentAdminEmails.add(it.lowercase(java.util.Locale.getDefault())) }
            
            for (doc in snapshot.documents) {
                val email = doc.id
                val emailLower = email.lowercase(java.util.Locale.getDefault())
                currentAdminEmails.add(emailLower)
                if (AdminManager.superAdmins.contains(email)) continue
                
                val name = doc.getString("name") ?: userStatusList.find { it.email.lowercase() == emailLower }?.name ?: "Unknown"
                val isPromotedOwner = doc.getBoolean("isOwner") ?: false
                val fullAccess = doc.getBoolean("fullAccess") ?: false
                val sendAnnouncements = doc.getBoolean("sendAnnouncements") ?: false
                val sendPromotions = doc.getBoolean("sendPromotions") ?: false
                val sendNotifications = doc.getBoolean("sendNotifications") ?: false
                val viewLastSeen = doc.getBoolean("viewLastSeen") ?: false
                val allocateAdmins = doc.getBoolean("allocateAdmins") ?: false
                
                val perms = AdminManager.AdminPermissions(
                    isFixedOwner = false,
                    isPromotedOwner = isPromotedOwner,
                    fullAccess = fullAccess,
                    sendAnnouncements = sendAnnouncements,
                    sendPromotions = sendPromotions,
                    sendNotifications = sendNotifications,
                    viewLastSeen = viewLastSeen,
                    allocateAdmins = allocateAdmins
                )
                
                val isSuperAdmin = fullAccess || (sendAnnouncements && sendPromotions && sendNotifications && viewLastSeen && allocateAdmins)
                var roleStr = if (isPromotedOwner) "Owner" else if (isSuperAdmin) "Super Administrator" else "Admin"
                
                if (pendingAdminRequests.containsKey(emailLower)) {
                    roleStr = "Super Administrator (Request pending)"
                }
                
                val cleanName = name.substringBefore(" - Owner").substringBefore(" - Super Administrator").substringBefore(" - Admin").substringBefore(" - Super Administrator (Request pending)")
                
                addAdminRow(container, email, cleanName, roleStr, perms)
                renderedEmails.add(emailLower)
            }
            
            // Render pending requests for users NOT in the admins collection (e.g. brand new admins requested by SA)
            for ((pendingEmail, _) in pendingAdminRequests) {
                if (!renderedEmails.contains(pendingEmail) && !AdminManager.superAdmins.contains(pendingEmail)) {
                    val name = userStatusList.find { it.email.lowercase() == pendingEmail }?.name ?: pendingEmail.substringBefore("@")
                    val roleStr = "Super Administrator (Request pending)"
                    val perms = AdminManager.AdminPermissions() // Default empty permissions
                    addAdminRow(container, pendingEmail, name, roleStr, perms)
                }
            }
        }
        
        val curPerms = AdminManager.getPermissions()
        val curIsOwner = curPerms.isOwner
        val curIsLifetimeSA = !curIsOwner && curPerms.fullAccess && curPerms.validUntil == 0L
        
        adminRequestsListenerRegistration?.remove()
        adminRequestsListenerRegistration = db.collection("admin_requests").addSnapshotListener { reqSnap, _ ->
            pendingAdminRequests.clear()
            if (reqSnap != null) {
                for (doc in reqSnap.documents) {
                    val isRequester = doc.id.lowercase() == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.lowercase()
                    val reqFullAccess = doc.getBoolean("fullAccess") ?: false
                    
                    val canSeeRequest = isRequester || curIsOwner || (curIsLifetimeSA && !reqFullAccess)
                    
                    if (canSeeRequest && (doc.getString("status") == "pending" || doc.getBoolean("isExtensionRequest") == true)) {
                        val requestedBy = doc.getString("requestedBy") ?: "Unknown"
                        pendingAdminRequests[doc.id.lowercase()] = requestedBy
                    }
                }
            }
            // Trigger a re-render from the admins collection to incorporate the updated requests
            db.collection("admins").get().addOnSuccessListener { snapshot ->
                renderAdminsList(snapshot)
            }
        }
        
        adminAccessListenerRegistration?.remove()
        adminAccessListenerRegistration = db.collection("admins").addSnapshotListener { snapshot, e ->
            if (e != null) {
                android.util.Log.e("AdminActivity", "Failed to load admins", e)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            renderAdminsList(snapshot)
        }
    }

    private fun addAdminRow(container: LinearLayout, email: String, name: String, role: String, perms: AdminManager.AdminPermissions) {
        val view = layoutInflater.inflate(R.layout.item_admin_access, container, false)
        val tvName = view.findViewById<TextView>(R.id.tvAdminName)
        val tvEmail = view.findViewById<TextView>(R.id.tvAdminEmail)
        val btnEdit = view.findViewById<ImageButton>(R.id.btnEditAdmin)
        val btnReview = view.findViewById<TextView>(R.id.btnReviewAdmin)

        tvName.text = "$name - ${if (role == "Admin") "Administrator" else role}"
        tvEmail.text = email

        val currentUserPerms = AdminManager.getPermissions()
        val currentUserIsOwner = currentUserPerms.isOwner
        val currentUserIsSA = !currentUserIsOwner && currentUserPerms.fullAccess
        val currentUserIsAdmin = !currentUserIsOwner && !currentUserIsSA

        val targetIsOwner = perms.isFixedOwner || perms.isPromotedOwner
        val targetIsSA = !targetIsOwner && (perms.fullAccess || (perms.sendAnnouncements && perms.sendPromotions && perms.sendNotifications && perms.viewLastSeen && perms.allocateAdmins))
        val targetIsAdmin = !targetIsOwner && !targetIsSA

        var canSeeEdit = false
        if (currentUserIsOwner) {
            canSeeEdit = true
        } else if (currentUserIsSA) {
            canSeeEdit = targetIsSA || targetIsAdmin
        } else if (currentUserIsAdmin) {
            canSeeEdit = targetIsAdmin
        }

        val isPendingRequest = pendingAdminRequests.containsKey(email.lowercase())
        if (isPendingRequest) {
            val createdBy = pendingAdminRequests[email.lowercase()]
            val isRequester = createdBy?.lowercase() == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.lowercase()
            
            if (isRequester) {
                tvEmail.text = "$email\nRequest pending"
                btnEdit.visibility = View.GONE
                btnReview?.visibility = View.VISIBLE
                btnReview?.text = "Cancel Request"
                btnReview?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#f87171")) // Red tone
                
                val cancelAction = View.OnClickListener {
                    FirebaseFirestore.getInstance().collection("admin_requests").document(email).delete()
                        .addOnSuccessListener {
                            ToastHelper.showToast(this@AdminActivity, "Request cancelled")
                        }
                }
                btnReview?.setOnClickListener(cancelAction)
                view.setOnClickListener(cancelAction)
            } else if (currentUserIsOwner || currentUserIsSA) {
                tvEmail.text = "$email\nRequested by: $createdBy"
                btnEdit.visibility = View.GONE
                btnReview?.visibility = View.VISIBLE
                btnReview?.text = "Review Required"
                btnReview?.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.resolveColorAttr(this, androidx.appcompat.R.attr.colorPrimary))
                
                val reviewAction = View.OnClickListener {
                    FirebaseFirestore.getInstance().collection("admin_requests").document(email).get()
                        .addOnSuccessListener { reqDoc ->
                            val isPending = reqDoc.exists() && reqDoc.getString("status") == "pending"
                            val isExtension = reqDoc.exists() && reqDoc.getBoolean("isExtensionRequest") == true
                            if (isPending || isExtension) {
                                val requestedPerms = AdminManager.AdminPermissions(
                                    isFixedOwner = reqDoc.getBoolean("isFixedOwner") ?: false,
                                    isPromotedOwner = reqDoc.getBoolean("isPromotedOwner") ?: false,
                                    fullAccess = reqDoc.getBoolean("fullAccess") ?: false,
                                    sendAnnouncements = reqDoc.getBoolean("sendAnnouncements") ?: false,
                                    sendPromotions = reqDoc.getBoolean("sendPromotions") ?: false,
                                    sendNotifications = reqDoc.getBoolean("sendNotifications") ?: false,
                                    viewLastSeen = reqDoc.getBoolean("viewLastSeen") ?: false,
                                    viewAdminLogs = reqDoc.getBoolean("viewAdminLogs") ?: false,
                                    replyToQueries = reqDoc.getBoolean("replyToQueries") ?: false,
                                    allocateAdmins = reqDoc.getBoolean("allocateAdmins") ?: false,
                                    validUntil = reqDoc.getLong("validUntil") ?: 0L
                                )
                                showEditAdminPermissionsDialog(email, name, requestedPerms, isNewAdmin = false, isReviewingRequest = true, isExtensionRequest = isExtension)
                            }
                        }
                }
                btnReview?.setOnClickListener(reviewAction)
                view.setOnClickListener(reviewAction)
            } else {
                tvEmail.text = "$email\nRequest pending"
                btnEdit.visibility = View.GONE
                btnReview?.visibility = View.GONE
                view.isClickable = false
            }
        } else if (canSeeEdit) {
            btnEdit.visibility = View.VISIBLE
            btnReview?.visibility = View.GONE
            val editAction = View.OnClickListener {
                showEditAdminPermissionsDialog(email, name, perms, isNewAdmin = false)
            }
            btnEdit.setOnClickListener(editAction)
            view.setOnClickListener(editAction)
            
            val typedValue = android.util.TypedValue()
            view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            view.setBackgroundResource(typedValue.resourceId)
        } else {
            btnEdit.visibility = View.GONE
            view.isClickable = false
        }

        container.addView(view)
    }





    private fun addSearchResultRow(container: LinearLayout, user: UserStatusItem) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            setOnClickListener {
                this@AdminActivity.findViewById<EditText>(R.id.etSearchNewAdmin)?.setText("")
                showEditAdminPermissionsDialog(user.email, user.name, AdminManager.AdminPermissions(), isNewAdmin = true)
            }
        }

        val tv = TextView(this).apply {
            text = "${user.name} - ${user.email}"
            textSize = 15f
            setPadding((16 * density).toInt(), 0, (8 * density).toInt(), 0)
            setTextColor(ThemeHelper.resolveColorAttr(this@AdminActivity, R.attr.textPrimaryColor))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val btnAdd = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_plus_vector)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.textPrimaryColor, typedValue, true)
            setColorFilter(typedValue.data)
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }

        row.addView(tv)
        row.addView(btnAdd)
        container.addView(row)
        
        val div = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@AdminActivity, R.color.border))
        }
        container.addView(div)
    }

    // The body of this moved to AdminPermissionsSheet so ManageAdminAccessActivity can
    // use the same editor. That screen used to launch EditAdminPermissionsActivity, which
    // displayed the same controls and then wrote nothing -- see the note on the sheet.
    private fun showEditAdminPermissionsDialog(
        email: String,
        name: String,
        currentPerms: AdminManager.AdminPermissions,
        isNewAdmin: Boolean = false,
        isReviewingRequest: Boolean = false,
        isExtensionRequest: Boolean = false
    ) = AdminPermissionsSheet.show(
        this, email, name, currentPerms, isNewAdmin, isReviewingRequest, isExtensionRequest
    )

    private fun logAdminAction(action: String, targetEmail: String, details: String) {
        val currentUserEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "unknown"
        val logData = hashMapOf(
            "action" to action,
            "target_email" to targetEmail.lowercase(),
            "actor_email" to currentUserEmail.lowercase(),
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "details" to details
        )
        FirebaseFirestore.getInstance().collection("audit_logs").add(logData)
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selectedYear", selectedDateCalendar.get(Calendar.YEAR))
        outState.putInt("selectedMonth", selectedDateCalendar.get(Calendar.MONTH))
        outState.putInt("selectedDay", selectedDateCalendar.get(Calendar.DAY_OF_MONTH))
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val year = savedInstanceState.getInt("selectedYear", selectedDateCalendar.get(Calendar.YEAR))
        val month = savedInstanceState.getInt("selectedMonth", selectedDateCalendar.get(Calendar.MONTH))
        val day = savedInstanceState.getInt("selectedDay", selectedDateCalendar.get(Calendar.DAY_OF_MONTH))
        selectedDateCalendar.set(year, month, day)
    }

    private fun showUpdateLockDialog() {
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_update_lock, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, ThemeHelper.getBottomSheetTheme(this))
        dialog.setContentView(sheetView)

        val switchLock = sheetView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchUpdateLock)
        val actvVersion = sheetView.findViewById<android.widget.AutoCompleteTextView>(R.id.actvMinVersionName)
        val btnSave = sheetView.findViewById<android.widget.Button>(R.id.btnSaveUpdateLock)

        val currentVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.4.7"
        } catch (e: Exception) { "0.4.7" }

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        db.collection("system").document("config")
            .get()
            .addOnSuccessListener { doc ->
                // Doc may exist with no data (e.g. on first open before any admin saved settings)
                val forceUpdateEnabled = doc.getBoolean("force_update_enabled") ?: false
                switchLock?.isChecked = forceUpdateEnabled

                val defaultVersions = listOf(
                    "0.5.0", "0.4.9", "0.4.8", "0.4.7", "0.4.6", "0.4.5", "0.4.4", "0.4.3", "0.4.2", "0.4.1", "0.4.0",
                    "0.3.9", "0.3.8", "0.3.7", "0.3.6", "0.3.5", "0.3.4", "0.3.3", "0.3.2",
                    "0.3.1", "0.3.0", "0.2.0", "0.1.0"
                )
                val cloudVersions = doc.get("version_history") as? List<String> ?: emptyList()
                val minVersion = doc.getString("min_supported_version_name") ?: currentVersionName
                
                val rawVersions = (cloudVersions + defaultVersions + listOf(currentVersionName, minVersion))
                    .distinct()
                    .sortedWith { v1, v2 ->
                        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
                        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
                        val length = maxOf(parts1.size, parts2.size)
                        for (i in 0 until length) {
                            val p1 = parts1.getOrElse(i) { 0 }
                            val p2 = parts2.getOrElse(i) { 0 }
                            if (p1 != p2) return@sortedWith p2.compareTo(p1)
                        }
                        0
                    }

                val displayVersions = rawVersions.map { ver ->
                    if (ver == currentVersionName) "$ver (Current)" else ver
                }

                val initialDisplay = if (minVersion == currentVersionName) "$minVersion (Current)" else minVersion
                
                val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayVersions)
                actvVersion?.setAdapter(adapter)
                actvVersion?.threshold = 1
                actvVersion?.setText(initialDisplay, false)

                btnSave?.setOnClickListener {
                    val isEnabled = switchLock?.isChecked == true
                    var selectedVersion = actvVersion?.text?.toString()?.trim() ?: currentVersionName
                    selectedVersion = selectedVersion.replace(" (Current)", "").trim()

                    val partsSel = selectedVersion.split(".").map { it.toIntOrNull() ?: 0 }
                    val partsCur = currentVersionName.split(".").map { it.toIntOrNull() ?: 0 }
                    val lengthCheck = maxOf(partsSel.size, partsCur.size)
                    var isSelectedNewer = false
                    for (i in 0 until lengthCheck) {
                        val pS = partsSel.getOrElse(i) { 0 }
                        val pC = partsCur.getOrElse(i) { 0 }
                        if (pS > pC) {
                            isSelectedNewer = true
                            break
                        } else if (pS < pC) {
                            break
                        }
                    }

                    if (isSelectedNewer) {
                        ToastHelper.showToast(this, "Cannot set minimum version higher than your current version ()")
                        return@setOnClickListener
                    }

                    val updatedRawVersions = (rawVersions + listOf(selectedVersion))
                        .distinct()
                        .sortedWith { v1, v2 ->
                            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
                            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
                            val length = maxOf(parts1.size, parts2.size)
                            for (i in 0 until length) {
                                val p1 = parts1.getOrElse(i) { 0 }
                                val p2 = parts2.getOrElse(i) { 0 }
                                if (p1 != p2) return@sortedWith p2.compareTo(p1)
                            }
                            0
                        }

                    val updateData = hashMapOf<String, Any>(
                        "force_update_enabled" to isEnabled,
                        "min_supported_version_name" to selectedVersion,
                        "version_history" to updatedRawVersions,
                        "last_updated_at" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )

                    db.collection("system").document("config")
                        .set(updateData, com.google.firebase.firestore.SetOptions.merge())
                        .addOnSuccessListener {
                            ToastHelper.showToast(this, "App update lock settings saved")
                            logAdminAction("Update Lock Config", if (isEnabled) "Enabled (Min: $selectedVersion)" else "Disabled", "Update lock turned ${if (isEnabled) "ON" else "OFF"}. Min required version: $selectedVersion", null)
                            dialog.dismiss()
                        }
                        .addOnFailureListener { e ->
                            ToastHelper.showToast(this, "Failed to save settings: ${e.message}")
                        }
                }
            }
            .addOnFailureListener {
                // Network error: still show dialog with defaults so user isn't blocked
                val defaultVersions = listOf(
                    "0.5.0", "0.4.9", "0.4.8", "0.4.7", "0.4.6", "0.4.5", "0.4.4", "0.4.3", "0.4.2", "0.4.1", "0.4.0",
                    "0.3.9", "0.3.8", "0.3.7", "0.3.6", "0.3.5", "0.3.4", "0.3.3", "0.3.2",
                    "0.3.1", "0.3.0", "0.2.0", "0.1.0"
                )
                val rawVersions = (defaultVersions + listOf(currentVersionName))
                    .distinct()
                    .sortedWith { v1, v2 ->
                        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
                        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
                        val length = maxOf(parts1.size, parts2.size)
                        for (i in 0 until length) {
                            val p1 = parts1.getOrElse(i) { 0 }
                            val p2 = parts2.getOrElse(i) { 0 }
                            if (p1 != p2) return@sortedWith p2.compareTo(p1)
                        }
                        0
                    }
                val displayVersions = rawVersions.map { ver ->
                    if (ver == currentVersionName) "$ver (Current)" else ver
                }
                val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayVersions)
                actvVersion?.setAdapter(adapter)
                actvVersion?.threshold = 1
                actvVersion?.setText("$currentVersionName (Current)", false)
                switchLock?.isChecked = false
                ToastHelper.showToast(this, "Loaded offline defaults — check connection before saving")
            }

        dialog.show()
    }
}
