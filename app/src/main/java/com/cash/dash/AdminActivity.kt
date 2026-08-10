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

        val etSearchNewAdmin = findViewById<EditText>(R.id.etSearchNewAdmin)
        val layoutAdminSearchResults = findViewById<LinearLayout>(R.id.layoutAdminSearchResults)
        val layoutSearchWrapper = findViewById<LinearLayout>(R.id.layoutSearchWrapper)
        val btnAddAdminSection = findViewById<LinearLayout>(R.id.btnAddAdminSection)
        
        btnAddAdminSection?.visibility = View.VISIBLE
        btnAddAdminSection?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(layoutSearchWrapper?.parent as? android.view.ViewGroup, android.transition.AutoTransition().apply { duration = 200 })
            if (layoutSearchWrapper?.visibility == View.VISIBLE) {
                layoutSearchWrapper.visibility = View.GONE
                layoutAdminSearchResults?.visibility = View.GONE
            } else {
                layoutSearchWrapper?.visibility = View.VISIBLE
                etSearchNewAdmin?.requestFocus()
                etSearchNewAdmin?.setText("")
                layoutAdminSearchResults?.removeAllViews()
                layoutAdminSearchResults?.visibility = View.VISIBLE
                val filteredUsers = userStatusList.filter { !currentAdminEmails.contains(it.email.lowercase(java.util.Locale.getDefault())) }
                for (user in filteredUsers) {
                    addSearchResultRow(layoutAdminSearchResults, user)
                }
            }
        }

        etSearchNewAdmin?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val container = layoutAdminSearchResults ?: return
                    container.removeAllViews()
                    
                    val query = s.toString().lowercase(java.util.Locale.getDefault()).trim()
                    val filteredUsers = userStatusList.filter { !currentAdminEmails.contains(it.email.lowercase(java.util.Locale.getDefault())) }
                    
                    if (query.isEmpty()) {
                        container.visibility = View.VISIBLE
                        for (user in filteredUsers) {
                            addSearchResultRow(container, user)
                        }
                        return
                    }

                    val matches = filteredUsers.filter {
                        it.email.lowercase(java.util.Locale.getDefault()).contains(query) ||
                        it.name.lowercase(java.util.Locale.getDefault()).contains(query)
                    }

                    if (matches.isNotEmpty()) {
                        container.visibility = View.VISIBLE
                        for (user in matches) {
                            addSearchResultRow(container, user)
                        }
                    } else {
                        container.visibility = View.GONE
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

        loadUserStatusList()
        loadSupportInbox()
        setupAdminAccessListener()
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
                                    val intent = Intent(this@AdminActivity, WebViewActivity::class.java).apply {
                                        putExtra("title", "Admin Reply")
                                        putExtra("url", replyUrl)
                                    }
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
        
        adminRequestsListenerRegistration?.remove()
        adminRequestsListenerRegistration = db.collection("admin_requests").addSnapshotListener { reqSnap, _ ->
            pendingAdminRequests.clear()
            if (reqSnap != null) {
                for (doc in reqSnap.documents) {
                    if (doc.getString("status") == "pending") {
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
                            if (reqDoc.exists() && reqDoc.getString("status") == "pending") {
                                val requestedPerms = AdminManager.AdminPermissions(
                                    isFixedOwner = false,
                                    isPromotedOwner = false,
                                    fullAccess = reqDoc.getBoolean("fullAccess") ?: false,
                                    sendAnnouncements = reqDoc.getBoolean("sendAnnouncements") ?: false,
                                    sendPromotions = reqDoc.getBoolean("sendPromotions") ?: false,
                                    sendNotifications = reqDoc.getBoolean("sendNotifications") ?: false,
                                    viewLastSeen = reqDoc.getBoolean("viewLastSeen") ?: false,
                                    allocateAdmins = reqDoc.getBoolean("allocateAdmins") ?: false
                                )
                                showEditAdminPermissionsDialog(email, name, requestedPerms, isNewAdmin = false, isReviewingRequest = true)
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

    private fun addAdminToFirestore(email: String, name: String) {
        val db = FirebaseFirestore.getInstance()
        val data = hashMapOf(
            "name" to name,
            "fullAccess" to false,
            "sendAnnouncements" to false,
            "sendPromotions" to false,
            "sendNotifications" to false,
            "viewLastSeen" to false,
            "allocateAdmins" to false
        )
        db.collection("admins").document(email.lowercase(java.util.Locale.getDefault())).set(data)
            .addOnSuccessListener {
                ToastHelper.showToast(this, "Added admin: $name")
                findViewById<EditText>(R.id.etSearchNewAdmin)?.setText("")
                findViewById<LinearLayout>(R.id.layoutAdminSearchResults)?.visibility = View.GONE
            }
    }

    private fun showEditAdminPermissionsDialog(
        email: String,
        name: String,
        currentPerms: AdminManager.AdminPermissions,
        isNewAdmin: Boolean = false,
        isReviewingRequest: Boolean = false
    ) {
        // BottomSheetDialogTheme makes the container transparent so our dark bg shows through.
        // Without this style, Material3 forces a white container background.
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)

        // Inflate with activity layoutInflater so ?attr colors resolve from the correct dark/light theme
        val view = layoutInflater.inflate(R.layout.bottom_sheet_admin_permissions, null, false)

        // Programmatically apply dark background with rounded top corners
        val bgColor = ThemeHelper.resolveColorAttr(this, com.google.android.material.R.attr.colorSurface)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadii = floatArrayOf(20f.dp, 20f.dp, 20f.dp, 20f.dp, 0f, 0f, 0f, 0f)
        }
        view.background = bg

        bottomSheet.setContentView(view)
        
        bottomSheet.setOnShowListener { dialog ->
            val d = dialog as com.google.android.material.bottomsheet.BottomSheetDialog
            val bottomSheetInternal = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheetInternal?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val tvEmail = view.findViewById<TextView>(R.id.tvAdminEmail) ?: return
        tvEmail.text = "$name - $email"

        val layoutCheckboxes = view.findViewById<View>(R.id.layoutCheckboxes) ?: return
        
        val cbAnnouncements = view.findViewById<android.widget.CheckBox>(R.id.cbAnnouncements) ?: return
        val cbPromotions = view.findViewById<android.widget.CheckBox>(R.id.cbPromotions) ?: return
        val cbNotifications = view.findViewById<android.widget.CheckBox>(R.id.cbNotifications) ?: return
        val cbLastSeen = view.findViewById<android.widget.CheckBox>(R.id.cbLastSeen) ?: return
        val cbAdminLogs = view.findViewById<android.widget.CheckBox>(R.id.cbAdminLogs) ?: return
        val cbReplyQueries = view.findViewById<android.widget.CheckBox>(R.id.cbReplyQueries) ?: return
        val cbAddNewAdmin = view.findViewById<android.widget.CheckBox>(R.id.cbAddNewAdmin) ?: return
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePermissions) ?: return
        val btnRevoke = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRevokeAccess) ?: return

        var selectedValidUntil = currentPerms.validUntil
        val tvValidityStatus = view.findViewById<TextView>(R.id.tvValidityStatus)
        val btnChangeValidity = view.findViewById<TextView>(R.id.btnChangeValidity)
        
        val updateValidityUI = {
            if (selectedValidUntil == 0L) {
                tvValidityStatus?.text = "Forever (No expiry)"
            } else {
                val formatted = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                tvValidityStatus?.text = "Valid until: $formatted"
            }
        }
        updateValidityUI()

        val layoutAdminValidity = view.findViewById<View>(R.id.layoutAdminValidity)

        btnChangeValidity?.setOnClickListener {
            val wrapper = android.view.ContextThemeWrapper(this, R.style.PopupMenuTheme)
            val popup = android.widget.PopupMenu(wrapper, btnChangeValidity)
            popup.menu.add(0, 1, 0, "Forever")
            popup.menu.add(0, 2, 0, "Choose Date")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        selectedValidUntil = 0L
                        updateValidityUI()
                        true
                    }
                    2 -> {
                        val cal = java.util.Calendar.getInstance()
                        if (selectedValidUntil > 0) cal.timeInMillis = selectedValidUntil
                        
                        val dpd = android.app.DatePickerDialog(this, ThemeHelper.getDatePickerTheme(this), { _, y, m, d ->
                            val newCal = java.util.Calendar.getInstance()
                            newCal.set(y, m, d, 23, 59, 59)
                            selectedValidUntil = newCal.timeInMillis
                            updateValidityUI()
                        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
                        dpd.datePicker.minDate = System.currentTimeMillis() - 1000
                        dpd.show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        val normalColor = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.WHITE
        val disabledColor = android.graphics.Color.parseColor("#44888888")

        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_enabled)
        )
        val colors = intArrayOf(
            disabledColor,
            normalColor
        )
        val checkboxTintList = android.content.res.ColorStateList(states, colors)
        cbAnnouncements.buttonTintList = checkboxTintList
        cbPromotions.buttonTintList = checkboxTintList
        cbNotifications.buttonTintList = checkboxTintList
        cbLastSeen.buttonTintList = checkboxTintList
        cbAdminLogs.buttonTintList = checkboxTintList
        cbReplyQueries.buttonTintList = checkboxTintList
        cbAddNewAdmin.buttonTintList = checkboxTintList

        val isWhite = ThemeHelper.isWhiteTheme(this)
        val greyTextColor = if (isWhite) android.graphics.Color.parseColor("#475569") else android.graphics.Color.parseColor("#E2E8F0")
        val greyBgColor = android.content.res.ColorStateList.valueOf(
            if (isWhite) android.graphics.Color.parseColor("#F1F5F9") else android.graphics.Color.parseColor("#334155")
        )
        
        btnSave.setTextColor(greyTextColor)
        btnSave.backgroundTintList = greyBgColor
        // It's a MaterialButton, remove stroke just in case
        btnSave.strokeWidth = 0

        if (isNewAdmin) {
            btnRevoke.text = "Cancel"
            btnRevoke.setTextColor(greyTextColor)
            btnRevoke.backgroundTintList = greyBgColor
            btnRevoke.strokeWidth = 0
            btnRevoke.setOnClickListener { bottomSheet.dismiss() }
        } else if (isReviewingRequest) {
            btnSave.text = "Approve Request"
            btnRevoke.text = "Reject"
            btnRevoke.setTextColor(greyTextColor)
            btnRevoke.backgroundTintList = greyBgColor
            btnRevoke.strokeWidth = 0
        }

        val isFixed = currentPerms.isFixedOwner
        val isPromotedOwner = currentPerms.isPromotedOwner
        val isTargetOwner = isFixed || isPromotedOwner
        val isTargetSA = !isTargetOwner && (currentPerms.fullAccess || (currentPerms.sendAnnouncements && currentPerms.sendPromotions && currentPerms.sendNotifications && currentPerms.viewLastSeen && currentPerms.viewAdminLogs && currentPerms.replyToQueries && currentPerms.allocateAdmins))
        val isTargetAdmin = !isTargetOwner && !isTargetSA

        val currentUserPerms = AdminManager.getPermissions()
        val isCurrentUserOwner = currentUserPerms.isOwner
        val isCurrentUserSA = !isCurrentUserOwner && currentUserPerms.fullAccess
        val isCurrentUserAdmin = !isCurrentUserOwner && !isCurrentUserSA

        val currentRole = if (isTargetOwner) "Owner"
        else if (isTargetSA) "Super Administrator"
        else "Admin"

        val isSelf = email.lowercase() == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.lowercase()

        // Visibility logic
        val canSeeAnnouncements = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canSendAnnouncements()) || (!isNewAdmin && currentPerms.canSendAnnouncements())
        val canSeePromotions = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canSendPromotions()) || (!isNewAdmin && currentPerms.canSendPromotions())
        val canSeeNotifications = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canSendNotifications()) || (!isNewAdmin && currentPerms.canSendNotifications())
        val canSeeLastSeen = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canViewLastSeen()) || (!isNewAdmin && currentPerms.canViewLastSeen())
        val canSeeAdminLogs = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canViewAdminLogs()) || (!isNewAdmin && currentPerms.canViewAdminLogs())
        val canSeeReplyQueries = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canReplyToQueries()) || (!isNewAdmin && currentPerms.canReplyToQueries())
        val canSeeAddNewAdmin = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canAllocateAdmins()) || (!isNewAdmin && currentPerms.canAllocateAdmins())

        cbAnnouncements.visibility = if (canSeeAnnouncements) View.VISIBLE else View.GONE
        cbPromotions.visibility = if (canSeePromotions) View.VISIBLE else View.GONE
        cbNotifications.visibility = if (canSeeNotifications) View.VISIBLE else View.GONE
        cbLastSeen.visibility = if (canSeeLastSeen) View.VISIBLE else View.GONE
        cbAdminLogs.visibility = if (canSeeAdminLogs) View.VISIBLE else View.GONE
        cbReplyQueries.visibility = if (canSeeReplyQueries) View.VISIBLE else View.GONE
        // cbAddNewAdmin visibility is handled dynamically based on other selections, but initial visibility depends on permissions too.
        cbAddNewAdmin.visibility = if (canSeeAddNewAdmin) View.VISIBLE else View.GONE

        // Checked logic
        if (isTargetOwner) {
            cbAnnouncements.isChecked = true
            cbPromotions.isChecked = true
            cbNotifications.isChecked = true
            cbLastSeen.isChecked = true
            cbAdminLogs.isChecked = true
            cbReplyQueries.isChecked = true
            cbAddNewAdmin.isChecked = true
        } else if (isNewAdmin) {
            cbAnnouncements.isChecked = false
            cbPromotions.isChecked = false
            cbNotifications.isChecked = false
            cbLastSeen.isChecked = false
            cbAdminLogs.isChecked = false
            cbReplyQueries.isChecked = false
            cbAddNewAdmin.isChecked = false
        } else {
            cbAnnouncements.isChecked = currentPerms.canSendAnnouncements()
            cbPromotions.isChecked = currentPerms.canSendPromotions()
            cbNotifications.isChecked = currentPerms.canSendNotifications()
            cbLastSeen.isChecked = currentPerms.canViewLastSeen()
            cbAdminLogs.isChecked = currentPerms.canViewAdminLogs()
            cbReplyQueries.isChecked = currentPerms.canReplyToQueries()
            cbAddNewAdmin.isChecked = currentPerms.canAllocateAdmins()
        }

        // Enable/Disable logic
        if (isTargetOwner) {
            cbAnnouncements.isEnabled = false
            cbPromotions.isEnabled = false
            cbNotifications.isEnabled = false
            cbLastSeen.isEnabled = false
            cbAdminLogs.isEnabled = false
            cbReplyQueries.isEnabled = false
            cbAddNewAdmin.isEnabled = false
            btnSave.isEnabled = true
            btnRevoke.visibility = View.GONE
            btnChangeValidity?.visibility = View.GONE
            layoutAdminValidity?.visibility = View.GONE
        } else if (isSelf && !isCurrentUserOwner) {
            // Cannot enable or disable anything in their own permissions option
            cbAnnouncements.isEnabled = false
            cbPromotions.isEnabled = false
            cbNotifications.isEnabled = false
            cbLastSeen.isEnabled = false
            cbAdminLogs.isEnabled = false
            cbReplyQueries.isEnabled = false
            cbAddNewAdmin.isEnabled = false
            btnSave.isEnabled = true
            btnRevoke.visibility = View.GONE
            btnChangeValidity?.visibility = View.GONE
            layoutAdminValidity?.visibility = View.VISIBLE
        } else {
            // Can toggle the ones they can see.
            cbAnnouncements.isEnabled = true
            cbPromotions.isEnabled = true
            cbNotifications.isEnabled = true
            cbLastSeen.isEnabled = true
            cbAdminLogs.isEnabled = true
            cbReplyQueries.isEnabled = true
            cbAddNewAdmin.isEnabled = true
            btnChangeValidity?.visibility = View.VISIBLE
            layoutAdminValidity?.visibility = View.VISIBLE
            if (isSelf) {
                btnRevoke.visibility = View.GONE
            }
        }

        // Dynamic visibility for "Add new admin"
        val updateAddNewAdminVisibility = {
            if (cbAnnouncements.isChecked || cbPromotions.isChecked || cbNotifications.isChecked || cbLastSeen.isChecked || cbAdminLogs.isChecked || cbReplyQueries.isChecked) {
                if (canSeeAddNewAdmin) {
                    cbAddNewAdmin.visibility = View.VISIBLE
                }
            } else {
                cbAddNewAdmin.isChecked = false
                cbAddNewAdmin.visibility = View.GONE
            }
        }
        
        cbAnnouncements.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbPromotions.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbNotifications.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbLastSeen.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbAdminLogs.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbReplyQueries.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }

        // Trigger once to set initial state
        updateAddNewAdminVisibility()

        btnSave.setOnClickListener {
            if (isSelf) {
                ToastHelper.showToast(this@AdminActivity, "Permissions saved")
                bottomSheet.dismiss()
                return@setOnClickListener
            }

            val db = FirebaseFirestore.getInstance()
            
            // if only cbAddNewAdmin is checked, that's equivalent to 0 features (cannot add admin with just this feature)
            val hasFeaturePermissions = cbAnnouncements.isChecked || cbPromotions.isChecked || cbNotifications.isChecked || cbLastSeen.isChecked || cbAdminLogs.isChecked || cbReplyQueries.isChecked
            val hasZeroPermissions = !hasFeaturePermissions
            
            if (hasZeroPermissions) {
                if (isNewAdmin) {
                    ToastHelper.showToast(this@AdminActivity, "Select at least 1 permission.")
                    return@setOnClickListener
                } else if (!isReviewingRequest) {
                    FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                        .addOnSuccessListener {
                            ToastHelper.showToast(this@AdminActivity, "Access revoked")
                            bottomSheet.dismiss()
                        }
                    return@setOnClickListener
                }
            }
            
            val isSuperAdminSave = cbAnnouncements.isChecked && cbPromotions.isChecked && cbNotifications.isChecked && cbLastSeen.isChecked && cbAdminLogs.isChecked && cbReplyQueries.isChecked && cbAddNewAdmin.isChecked
            val isOwnerSave = currentPerms.isPromotedOwner // Can't change owner here anyway since it's removed
            
            var needsRequest = false
            if (!isCurrentUserOwner) {
                if (!currentUserPerms.canAllocateAdmins()) {
                    needsRequest = true
                } else if (isSuperAdminSave && (!currentPerms.fullAccess || isNewAdmin)) {
                    needsRequest = true
                }
            }
            
            if (needsRequest) {
                val requestData = hashMapOf<String, Any>(
                    "email" to email.lowercase(),
                    "name" to name,
                    "isOwner" to false,
                    "fullAccess" to isSuperAdminSave,
                    "sendAnnouncements" to cbAnnouncements.isChecked,
                    "sendPromotions" to cbPromotions.isChecked,
                    "sendNotifications" to cbNotifications.isChecked,
                    "viewLastSeen" to cbLastSeen.isChecked,
                    "viewAdminLogs" to cbAdminLogs.isChecked,
                    "replyToQueries" to cbReplyQueries.isChecked,
                    "allocateAdmins" to cbAddNewAdmin.isChecked,
                    "validUntil" to selectedValidUntil,
                    "requestedBy" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: ""),
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "pending"
                )
                db.collection("admin_requests").document(email.lowercase()).set(requestData)
                    .addOnSuccessListener {
                        ToastHelper.showToast(this@AdminActivity, "Request sent to owners for approval")
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener { e -> 
                        ToastHelper.showToast(this@AdminActivity, "Failed to send request. Firebase Rules might be blocking it: ${e.message}") 
                    }
                return@setOnClickListener
            }

            val data = hashMapOf<String, Any>(
                "name" to name,
                "isOwner" to isOwnerSave,
                "fullAccess" to isSuperAdminSave,
                "sendAnnouncements" to cbAnnouncements.isChecked,
                "sendPromotions" to cbPromotions.isChecked,
                "sendNotifications" to cbNotifications.isChecked,
                "viewLastSeen" to cbLastSeen.isChecked,
                "viewAdminLogs" to cbAdminLogs.isChecked,
                "replyToQueries" to cbReplyQueries.isChecked,
                "allocateAdmins" to cbAddNewAdmin.isChecked,
                "validUntil" to selectedValidUntil
            )

            if (isReviewingRequest) {
                db.collection("admins").document(email.lowercase()).set(data)
                    .addOnSuccessListener {
                        db.collection("admin_requests").document(email.lowercase()).delete()
                        ToastHelper.showToast(this@AdminActivity, "Request approved")
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(this@AdminActivity, "Failed: ${e.message}")
                    }
                return@setOnClickListener
            }

            db.collection("admins").document(email.lowercase()).set(data)
                .addOnSuccessListener {
                    val msg = if (isNewAdmin) "Admin added: $name" else "Permissions updated"
                    ToastHelper.showToast(this@AdminActivity, msg)
                    this@AdminActivity.findViewById<EditText>(R.id.etSearchNewAdmin)?.setText("")
                    this@AdminActivity.findViewById<LinearLayout>(R.id.layoutAdminSearchResults)?.visibility = View.GONE
                    bottomSheet.dismiss()
                }
                .addOnFailureListener { e -> 
                    ToastHelper.showToast(this@AdminActivity, "Failed to save. Firebase Rules might be blocking it: ${e.message}") 
                }
        }

        if (isReviewingRequest) {
            btnRevoke.setOnClickListener {
                FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).delete()
                    .addOnSuccessListener {
                        ToastHelper.showToast(this, "Request rejected")
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(this, "Failed to reject: ${e.message}")
                    }
            }
        } else if (!isNewAdmin) {
            btnRevoke.setOnClickListener {
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Revoke Access")
                    .setMessage("Are you sure you want to revoke admin access for $email?")
                    .setPositiveButton("Revoke") { _, _ ->
                        FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                            .addOnSuccessListener {
                                ToastHelper.showToast(this, "Access revoked")
                                bottomSheet.dismiss()
                            }
                            .addOnFailureListener { e ->
                                ToastHelper.showToast(this, "Failed to revoke: ${e.message}")
                            }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                
                dialog.setOnShowListener {
                    val color = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
                }
                dialog.show()
            }
        }

        bottomSheet.show()
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

}
