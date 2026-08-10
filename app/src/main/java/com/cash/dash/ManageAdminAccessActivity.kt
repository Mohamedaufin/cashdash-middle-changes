package com.cash.dash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

class ManageAdminAccessActivity : ThemedActivity() {

    private val userStatusList = mutableListOf<UserStatusItem>()
    private var usersListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private val rtdbPresenceMap = mutableMapOf<String, Pair<String, String>>()
    private val currentAdminEmails = mutableSetOf<String>()
    
    private var adminRequestsListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private val pendingAdminRequests = mutableMapOf<String, String>()

    private var isFirstPermissionCheck = true
    private val permissionListener: (AdminManager.AdminPermissions) -> Unit = { perms ->
        if (!isFirstPermissionCheck) {
            if (!perms.hasAnyAccess) {
                ToastHelper.showToast(this, "Permission denied")
                finish()
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
        setContentView(R.layout.activity_manage_admin_access)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val root = (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomPadding = Math.max(systemBars.bottom, ime.bottom)
            view.setPadding(0, systemBars.top, 0, bottomPadding)
            insets
        }

        AdminManager.addListener(permissionListener)



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
                val filteredUsers = userStatusList.filter { !currentAdminEmails.contains(it.email.lowercase(java.util.Locale.getDefault())) }.sortedBy { it.name.lowercase(java.util.Locale.getDefault()) }
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
                    val filteredUsers = userStatusList.filter { !currentAdminEmails.contains(it.email.lowercase(java.util.Locale.getDefault())) }.sortedBy { it.name.lowercase(java.util.Locale.getDefault()) }
                    
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
        setupAdminAccessListener()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findViewById<View>(R.id.layoutEditPermissionsContainer)?.visibility == View.VISIBLE) {
                    closeEditPermissionsView(withConfirmation = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
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

                            val card = LinearLayout(this@ManageAdminAccessActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.TOP
                                setPadding(
                                    (12 * density).toInt(), (14 * density).toInt(),
                                    (16 * density).toInt(), (14 * density).toInt()
                                )
                                background = androidx.core.content.ContextCompat.getDrawable(
                                    this@ManageAdminAccessActivity,
                                    ThemeHelper.getDrawable(this@ManageAdminAccessActivity, R.drawable.bg_transaction)
                                )
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (10 * density).toInt() }
                            }

                            // Right content column
                            val contentCol = LinearLayout(this@ManageAdminAccessActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            }

                            // Subject row with red dot
                            val headerRow = LinearLayout(this@ManageAdminAccessActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (4 * density).toInt() }
                            }
                            val redDot = View(this@ManageAdminAccessActivity).apply {
                                val sz = (8 * density).toInt()
                                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                                    rightMargin = (8 * density).toInt()
                                }
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.OVAL
                                    setColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textRedColor))
                                }
                            }
                            val tvSubject = TextView(this@ManageAdminAccessActivity).apply {
                                text = item.subject.ifEmpty { "(No Subject)" }
                                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textPrimaryColor))
                                textSize = 15f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            headerRow.addView(redDot)
                            headerRow.addView(tvSubject)
                            contentCol.addView(headerRow)

                            val tvName = TextView(this@ManageAdminAccessActivity).apply {
                                text = "👤 ${item.userName.ifEmpty { "Unknown" }}"
                                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textMutedColor))
                                textSize = 13f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (2 * density).toInt() }
                            }
                            contentCol.addView(tvName)

                            val tvEmail = TextView(this@ManageAdminAccessActivity).apply {
                                text = "✉️ ${item.userEmail}"
                                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textMutedColor))
                                textSize = 13f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (2 * density).toInt() }
                            }
                            contentCol.addView(tvEmail)

                            val tvTime = TextView(this@ManageAdminAccessActivity).apply {
                                val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
                                val formattedTime = if (item.timestamp > 0) sdf.format(Date(item.timestamp)) else "Unknown time"
                                text = "🕒 $formattedTime"
                                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textMutedColor))
                                textSize = 13f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (8 * density).toInt() }
                            }
                            contentCol.addView(tvTime)

                            val btnOpen = Button(this@ManageAdminAccessActivity).apply {
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
                                    val intent = Intent(this@ManageAdminAccessActivity, WebViewActivity::class.java).apply {
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
                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textMutedColor))
                setPadding(16, 16, 16, 16)
            }
            container.addView(tv)
            return
        }

        val btnSelectDate = findViewById<android.widget.TextView>(R.id.btnSelectDate)

        val todayCal = Calendar.getInstance()
        val isToday = todayCal.get(Calendar.YEAR) == 0 &&
                todayCal.get(Calendar.MONTH) == 0 &&
                todayCal.get(Calendar.DAY_OF_MONTH) == 0

        val dateTextFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(java.util.Date())
        btnSelectDate?.text = if (isToday) "Date: Today ($dateTextFormat)" else "Date: $dateTextFormat"

        val formattedQueryDateNew = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date())

        val formattedQueryDateOld = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date())

        val density = resources.displayMetrics.density

        if (userStatusList.isEmpty()) {
            val tvEmpty = android.widget.TextView(this).apply {
                text = "No users loaded."
                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textMutedColor))
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
                setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textPrimaryColor))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvUsername)

            // Opened/Not Opened status
            val tvStatus = android.widget.TextView(this).apply {
                text = if (isActive) "Opened" else "Not Opened"
                setTextColor(if (isActive) ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textGreenColor) else ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textRedColor))
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
                            ToastHelper.showToast(this@ManageAdminAccessActivity, "Request cancelled")
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
                this@ManageAdminAccessActivity.findViewById<EditText>(R.id.etSearchNewAdmin)?.setText("")
                showEditAdminPermissionsDialog(user.email, user.name, AdminManager.AdminPermissions(), isNewAdmin = true)
            }
        }

        val tv = TextView(this).apply {
            text = "${user.name} - ${user.email}"
            textSize = 15f
            setPadding((16 * density).toInt(), 0, (8 * density).toInt(), 0)
            setTextColor(ThemeHelper.resolveColorAttr(this@ManageAdminAccessActivity, R.attr.textPrimaryColor))
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
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@ManageAdminAccessActivity, R.color.border))
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

    private fun showCancelConfirmationDialog(onConfirmed: () -> Unit) {
        val color = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val titleSpan = android.text.SpannableString("Cancel Edit")
        titleSpan.setSpan(android.text.style.ForegroundColorSpan(color), 0, titleSpan.length, 0)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(titleSpan)
            .setMessage("Are you sure you want to cancel the action? Unsaved changes will be lost.")
            .setPositiveButton("Yes") { _, _ -> onConfirmed() }
            .setNegativeButton("No", null)
            .create()
            
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
        }
        dialog.show()
    }

    private fun closeEditPermissionsView(withConfirmation: Boolean = false) {
        if (withConfirmation) {
            showCancelConfirmationDialog {
                findViewById<View>(R.id.layoutEditPermissionsContainer)?.visibility = View.GONE
                findViewById<View>(R.id.layoutMainContent)?.visibility = View.VISIBLE
            }
        } else {
            findViewById<View>(R.id.layoutEditPermissionsContainer)?.visibility = View.GONE
            findViewById<View>(R.id.layoutMainContent)?.visibility = View.VISIBLE
        }
    }

    private fun showEditAdminPermissionsDialog(
        email: String,
        name: String,
        currentPerms: AdminManager.AdminPermissions,
        isNewAdmin: Boolean = false,
        isReviewingRequest: Boolean = false,
        isExtensionRequest: Boolean = false
    ) {
        findViewById<View>(R.id.layoutMainContent)?.visibility = View.GONE
        findViewById<View>(R.id.layoutEditPermissionsContainer)?.visibility = View.VISIBLE

        val tvEmail = findViewById<TextView>(R.id.tvAdminEmail) ?: return
        tvEmail.text = "$name - $email"

        val cbAnnouncements = findViewById<android.widget.CheckBox>(R.id.cbAnnouncements) ?: return
        val cbPromotions = findViewById<android.widget.CheckBox>(R.id.cbPromotions) ?: return
        val cbNotifications = findViewById<android.widget.CheckBox>(R.id.cbNotifications) ?: return
        val cbLastSeen = findViewById<android.widget.CheckBox>(R.id.cbLastSeen) ?: return
        val cbAdminLogs = findViewById<android.widget.CheckBox>(R.id.cbAdminLogs) ?: return
        val cbReplyQueries = findViewById<android.widget.CheckBox>(R.id.cbReplyQueries) ?: return
        val cbAddNewAdmin = findViewById<android.widget.CheckBox>(R.id.cbAddNewAdmin) ?: return
        val btnSave = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePermissions) ?: return
        val btnRevoke = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRevokeAccess) ?: return
        val btnCancelEditTop = findViewById<TextView>(R.id.btnCancelEditTop) ?: return

        btnCancelEditTop.setOnClickListener {
            closeEditPermissionsView(withConfirmation = true)
        }

        val cbIncludeNotification = findViewById<android.widget.CheckBox>(R.id.cbIncludeNotification)
        val layoutNotificationTemplate = findViewById<LinearLayout>(R.id.layoutNotificationTemplate)
        val actvTitle = findViewById<android.widget.EditText>(R.id.actvNotificationTitle)
        val actvContent = findViewById<android.widget.EditText>(R.id.actvNotificationContent)
        val btnAIRephraseTitle = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAIRephraseTitle)
        val btnAIRephraseBody = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAIRephraseBody)

        cbIncludeNotification?.setOnCheckedChangeListener { _, isChecked ->
            layoutNotificationTemplate?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        val tvUndoRephraseTitle = findViewById<android.widget.TextView>(R.id.tvUndoRephraseTitle)
        var originalTitleBeforeAI: String? = null
        tvUndoRephraseTitle?.setOnClickListener {
            originalTitleBeforeAI?.let { actvTitle?.setText(it) }
            tvUndoRephraseTitle.visibility = android.view.View.GONE
        }

        btnAIRephraseTitle?.setOnClickListener {
            val originalText = actvTitle?.text?.toString() ?: ""
            if (originalText.isBlank()) {
                ToastHelper.showToast(this@ManageAdminAccessActivity, "Please type a title first.")
                return@setOnClickListener
            }
            ToastHelper.showToast(this@ManageAdminAccessActivity, "Rephrasing Title...")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val result = GenerativeAiManager.rephraseText(originalText, true, "AQ.Ab8RN6IA4oaqMPLgc4p9LZcFoNJhpokd9RQyM5bSMi2m_JOgVw")
                    originalTitleBeforeAI = originalText
                    actvTitle?.setText(result)
                    tvUndoRephraseTitle?.visibility = android.view.View.VISIBLE
                } catch (e: Exception) {
                    ToastHelper.showErrorDialog(this@ManageAdminAccessActivity, "AI Error", e.message ?: "Unknown Error")
                }
            }
        }

        val tvUndoRephraseBody = findViewById<android.widget.TextView>(R.id.tvUndoRephraseBody)
        var originalBodyBeforeAI: String? = null
        tvUndoRephraseBody?.setOnClickListener {
            originalBodyBeforeAI?.let { actvContent?.setText(it) }
            tvUndoRephraseBody.visibility = android.view.View.GONE
        }

        btnAIRephraseBody?.setOnClickListener {
            val originalText = actvContent?.text?.toString() ?: ""
            if (originalText.isBlank()) {
                ToastHelper.showToast(this@ManageAdminAccessActivity, "Please type some content first.")
                return@setOnClickListener
            }
            ToastHelper.showToast(this@ManageAdminAccessActivity, "Rephrasing Content...")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val result = GenerativeAiManager.rephraseText(originalText, false, "AQ.Ab8RN6IA4oaqMPLgc4p9LZcFoNJhpokd9RQyM5bSMi2m_JOgVw")
                    originalBodyBeforeAI = originalText
                    actvContent?.setText(result)
                    tvUndoRephraseBody?.visibility = android.view.View.VISIBLE
                } catch (e: Exception) {
                    ToastHelper.showErrorDialog(this@ManageAdminAccessActivity, "AI Error", e.message ?: "Unknown Error")
                }
            }
        }

        var selectedValidUntil = currentPerms.validUntil
        
        val currentUserPerms = AdminManager.getPermissions()
        val isCurrentUserOwner = currentUserPerms.isOwner
        val isCurrentUserSA = !isCurrentUserOwner && currentUserPerms.fullAccess
        val isCurrentUserAdmin = !isCurrentUserOwner && !isCurrentUserSA
        val isCurrentUserLifetime = currentUserPerms.validUntil == 0L
        val canGiveForever = isCurrentUserOwner || (isCurrentUserSA && isCurrentUserLifetime)
        val tvValidityStatus = findViewById<TextView>(R.id.tvValidityStatus)
        val btnChangeValidity = findViewById<TextView>(R.id.btnChangeValidity)
        val btnRequestExtension = findViewById<TextView>(R.id.btnRequestExtension)
        
        val updateValidityUI = {
            if (selectedValidUntil == 0L) {
                tvValidityStatus?.text = "Forever (No expiry)"
            } else {
                val formatted = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                tvValidityStatus?.text = "Valid until: $formatted"
            }
        }
        updateValidityUI()

        val layoutAdminValidity = findViewById<View>(R.id.layoutAdminValidity)

        btnChangeValidity?.setOnClickListener {
            val options = if (canGiveForever) {
                arrayOf("Forever (No expiry)", "Choose Date")
            } else {
                arrayOf("Choose Date")
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Admin Validity")
                .setItems(options) { dialog, which ->
                    val selectedText = options[which]
                    if (selectedText == "Forever (No expiry)") {
                        selectedValidUntil = 0L
                        updateValidityUI()
                    } else if (selectedText == "Choose Date") {
                        val cal = java.util.Calendar.getInstance()
                        if (selectedValidUntil > 0) cal.timeInMillis = selectedValidUntil

                        val dpd = android.app.DatePickerDialog(this, ThemeHelper.getDatePickerTheme(this), { _, y, m, d ->
                            val newCal = java.util.Calendar.getInstance()
                            newCal.set(y, m, d, 23, 59, 59)
                            selectedValidUntil = newCal.timeInMillis
                            updateValidityUI()
                        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
                        dpd.datePicker.minDate = System.currentTimeMillis() - 1000
                        if (!isCurrentUserLifetime && currentUserPerms.validUntil > 0L) {
                            dpd.datePicker.maxDate = currentUserPerms.validUntil
                        }
                        dpd.show()
                    }
                }
                .show()
        }

        btnRequestExtension?.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            if (currentUserPerms.validUntil > 0) cal.timeInMillis = currentUserPerms.validUntil

            val dpd = android.app.DatePickerDialog(this, ThemeHelper.getDatePickerTheme(this), { _, y, m, d ->
                val newCal = java.util.Calendar.getInstance()
                newCal.set(y, m, d, 23, 59, 59)
                val requestedTime = newCal.timeInMillis
                
                // Submit extension request
                val requestData = hashMapOf(
                    "isFixedOwner" to currentUserPerms.isFixedOwner,
                    "isPromotedOwner" to currentUserPerms.isPromotedOwner,
                    "fullAccess" to currentUserPerms.fullAccess,
                    "sendAnnouncements" to currentUserPerms.sendAnnouncements,
                    "sendPromotions" to currentUserPerms.sendPromotions,
                    "sendNotifications" to currentUserPerms.sendNotifications,
                    "viewLastSeen" to currentUserPerms.viewLastSeen,
                    "viewAdminLogs" to currentUserPerms.viewAdminLogs,
                    "replyToQueries" to currentUserPerms.replyToQueries,
                    "allocateAdmins" to currentUserPerms.allocateAdmins,
                    "validUntil" to requestedTime,
                    "isExtensionRequest" to true,
                    "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).set(requestData)
                    .addOnSuccessListener {
                        ToastHelper.showToast(this, "Extension request submitted")
                        closeEditPermissionsView()
                    }
                    .addOnFailureListener {
                        ToastHelper.showToast(this, "Failed to submit request")
                    }
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
            dpd.datePicker.minDate = System.currentTimeMillis() - 1000
            dpd.show()
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
        btnSave.strokeWidth = 0

        if (isNewAdmin) {
            btnRevoke.text = "Cancel"
            btnRevoke.setTextColor(greyTextColor)
            btnRevoke.backgroundTintList = greyBgColor
            btnRevoke.strokeWidth = 0
            btnRevoke.setOnClickListener { closeEditPermissionsView(withConfirmation = true) }
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

        val isSelf = email.lowercase() == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.lowercase()

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
        cbAddNewAdmin.visibility = if (canSeeAddNewAdmin) View.VISIBLE else View.GONE

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

        if (isTargetOwner) {
            cbAnnouncements.isEnabled = false
            cbPromotions.isEnabled = false
            cbNotifications.isEnabled = false
            cbLastSeen.isEnabled = false
            cbAdminLogs.isEnabled = false
            cbReplyQueries.isEnabled = false
            cbAddNewAdmin.isEnabled = false
            btnSave.isEnabled = true
            
            if (isSelf && !currentPerms.isFixedOwner) {
                btnRevoke.visibility = View.VISIBLE
                btnRevoke.text = "Resign as Admin"
            } else {
                btnRevoke.visibility = View.GONE
            }
            
            btnChangeValidity?.visibility = View.GONE
            btnRequestExtension?.visibility = View.GONE
            layoutAdminValidity?.visibility = View.GONE
        } else if (isSelf && !isCurrentUserOwner) {
            cbAnnouncements.isEnabled = false
            cbPromotions.isEnabled = false
            cbNotifications.isEnabled = false
            cbLastSeen.isEnabled = false
            cbAdminLogs.isEnabled = false
            cbReplyQueries.isEnabled = false
            cbAddNewAdmin.isEnabled = false
            btnSave.isEnabled = true
            
            btnRevoke.visibility = View.VISIBLE
            btnRevoke.text = "Resign as Admin"
            btnChangeValidity?.visibility = View.GONE
            
            if (currentUserPerms.validUntil > 0L) {
                btnRequestExtension?.visibility = View.VISIBLE
            } else {
                btnRequestExtension?.visibility = View.GONE
            }
            layoutAdminValidity?.visibility = View.VISIBLE
        } else {
            cbAnnouncements.isEnabled = true
            cbPromotions.isEnabled = true
            cbNotifications.isEnabled = true
            cbLastSeen.isEnabled = true
            cbAdminLogs.isEnabled = true
            cbReplyQueries.isEnabled = true
            cbAddNewAdmin.isEnabled = true
            btnChangeValidity?.visibility = View.VISIBLE
            btnRequestExtension?.visibility = View.GONE
            layoutAdminValidity?.visibility = View.VISIBLE
            if (isSelf) {
                if (!currentPerms.isFixedOwner) {
                    btnRevoke.visibility = View.VISIBLE
                    btnRevoke.text = "Resign as Admin"
                } else {
                    btnRevoke.visibility = View.GONE
                }
            } else {
                btnRevoke.visibility = View.VISIBLE
                btnRevoke.text = "Revoke Access"
            }
        }

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

        updateAddNewAdminVisibility()

        btnSave.setOnClickListener {
            if (isSelf) {
                ToastHelper.showToast(this@ManageAdminAccessActivity, "Permissions saved")
                closeEditPermissionsView()
                return@setOnClickListener
            }

            val db = FirebaseFirestore.getInstance()
            
            val hasFeaturePermissions = cbAnnouncements.isChecked || cbPromotions.isChecked || cbNotifications.isChecked || cbLastSeen.isChecked || cbAdminLogs.isChecked || cbReplyQueries.isChecked
            val hasZeroPermissions = !hasFeaturePermissions
            
            if (hasZeroPermissions) {
                if (isNewAdmin) {
                    ToastHelper.showToast(this@ManageAdminAccessActivity, "Select at least 1 permission.")
                    return@setOnClickListener
                } else if (!isReviewingRequest) {
                    FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                        .addOnSuccessListener {
                            ToastHelper.showToast(this@ManageAdminAccessActivity, "Access revoked")
                            closeEditPermissionsView()
                        }
                    return@setOnClickListener
                }
            }
            
            val isSuperAdminSave = cbAnnouncements.isChecked && cbPromotions.isChecked && cbNotifications.isChecked && cbLastSeen.isChecked && cbAdminLogs.isChecked && cbReplyQueries.isChecked && cbAddNewAdmin.isChecked
            val isOwnerSave = currentPerms.isPromotedOwner 
            
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
                        ToastHelper.showToast(this@ManageAdminAccessActivity, "Request sent to owners for approval")
                        closeEditPermissionsView()
                    }
                    .addOnFailureListener { e -> 
                        ToastHelper.showToast(this@ManageAdminAccessActivity, "Failed to send request. Firebase Rules might be blocking it: ${e.message}") 
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
                        val approverEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An administrator"
                        val validityStr = if (selectedValidUntil == 0L) "Lifetime\n(Note: Your continued access is a reflection of the trust placed in you. It remains subject to review based on your ongoing performance and commitment to the CashDash platform.)" else java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                        val roleStr = if (isSuperAdminSave) "Super Administrator" else "Administrator"

                        if (cbIncludeNotification?.isChecked == true) {
                            val notifSubject = actvTitle?.text?.toString()?.trim() ?: ""
                            var notifBody = actvContent?.text?.toString()?.trim() ?: ""
                            notifBody = notifBody.replace("{Validity}", validityStr)
                            notifBody += "\n\nRegards,\nTeam CashDash"
                            
                            if (notifSubject.isNotEmpty() && notifBody.isNotEmpty()) {
                                sendAdminInAppNotification(email, notifSubject, notifBody)
                            }
                        }

                        if (isExtensionRequest) {
                            logAdminAction("Admin Extension Approved", actvTitle?.text?.toString() ?: "Extension Approved", "$approverEmail approved extension request for $email. New validity: $validityStr", email)
                        } else {
                            logAdminAction("Admin Request Approved", actvTitle?.text?.toString() ?: "Request Approved", "$approverEmail approved admin request for $email as $roleStr. Validity: $validityStr", email)
                        }

                        logAdminAction("APPROVED_REQUEST", email, "Approved request and granted/updated permissions.")
                        ToastHelper.showToast(this@ManageAdminAccessActivity, "Request approved")
                        closeEditPermissionsView()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(this@ManageAdminAccessActivity, "Failed: ${e.message}")
                    }
                return@setOnClickListener
            }

            db.collection("admins").document(email.lowercase()).set(data)
                .addOnSuccessListener {
                    val action = if (isNewAdmin) "GRANTED_ACCESS" else "UPDATED_PERMISSIONS"
                    val msg = if (isNewAdmin) "Admin added: $name" else "Permissions updated"
                    logAdminAction(action, email, msg)

                    val actorEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An administrator"
                    val validityStr = if (selectedValidUntil == 0L) "Lifetime\n(Note: Your continued access is a reflection of the trust placed in you. It remains subject to review based on your ongoing performance and commitment to the CashDash platform.)" else java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                    val roleStr = if (isSuperAdminSave) "Super Administrator" else "Administrator"

                    if (isNewAdmin) {
                        if (cbIncludeNotification?.isChecked == true) {
                            val notifSubject = actvTitle?.text?.toString()?.trim() ?: ""
                            var notifBody = actvContent?.text?.toString()?.trim() ?: ""
                            notifBody = notifBody.replace("{Validity}", validityStr)
                            notifBody += "\n\nRegards,\nTeam CashDash"
                            if (notifSubject.isNotEmpty() && notifBody.isNotEmpty()) {
                                sendAdminInAppNotification(email, notifSubject, notifBody)
                            }
                        }
                        logAdminAction("Admin Promotion", actvTitle?.text?.toString() ?: "Admin Added", "$actorEmail granted $roleStr access to $email. Validity: $validityStr", email)
                    } else if (true) {
                        if (cbIncludeNotification?.isChecked == true) {
                            val notifSubject = actvTitle?.text?.toString()?.trim() ?: ""
                            var notifBody = actvContent?.text?.toString()?.trim() ?: ""
                            notifBody = notifBody.replace("{Validity}", validityStr)
                            notifBody += "\n\nRegards,\nTeam CashDash"
                            if (notifSubject.isNotEmpty() && notifBody.isNotEmpty()) {
                                sendAdminInAppNotification(email, notifSubject, notifBody)
                            }
                        }
                        logAdminAction("Admin Validity Update", actvTitle?.text?.toString() ?: "Access Updated", "$actorEmail updated validity for $email to $validityStr", email)
                    }

                    ToastHelper.showToast(this@ManageAdminAccessActivity, msg)
                    this@ManageAdminAccessActivity.findViewById<EditText>(R.id.etSearchNewAdmin)?.setText("")
                    this@ManageAdminAccessActivity.findViewById<LinearLayout>(R.id.layoutAdminSearchResults)?.visibility = View.GONE
                    closeEditPermissionsView()
                }
                .addOnFailureListener { e -> 
                    ToastHelper.showToast(this@ManageAdminAccessActivity, "Failed to save. Firebase Rules might be blocking it: ${e.message}") 
                }
        }

        if (isReviewingRequest) {
            btnRevoke.setOnClickListener {
                FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).delete()
                    .addOnSuccessListener {
                        logAdminAction("REJECTED_REQUEST", email, "Rejected admin extension/access request.")
                        ToastHelper.showToast(this, "Request rejected")
                        closeEditPermissionsView()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(this, "Failed to reject: ${e.message}")
                    }
            }
        } else if (isSelf && !currentPerms.isFixedOwner) {
            btnRevoke.setOnClickListener {
                val color = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                val titleSpan = android.text.SpannableString("Resign as Admin")
                titleSpan.setSpan(android.text.style.ForegroundColorSpan(color), 0, titleSpan.length, 0)

                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(titleSpan)
                    .setMessage("Are you sure you want to resign? You will lose all administrative privileges immediately.")
                    .setPositiveButton("Resign") { _, _ ->
                        FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                            .addOnSuccessListener {
                                FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).delete()
                                logAdminAction("RESIGNED", email, "User voluntarily resigned from admin privileges.")
                                ToastHelper.showToast(this, "You have resigned as admin.")
                                closeEditPermissionsView()
                                finish() 
                            }
                            .addOnFailureListener { e ->
                                ToastHelper.showToast(this, "Failed to resign: ${e.message}")
                            }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
                }
                dialog.show()
            }
        } else if (!isNewAdmin) {
            btnRevoke.setOnClickListener {
                val color = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                val titleSpan = android.text.SpannableString("Revoke Access")
                titleSpan.setSpan(android.text.style.ForegroundColorSpan(color), 0, titleSpan.length, 0)

                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(titleSpan)
                    .setMessage("Are you sure you want to revoke admin access for $email?")
                    .setPositiveButton("Revoke") { _, _ ->
                        FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                            .addOnSuccessListener {
                                val actorEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An administrator"
                                if (cbIncludeNotification?.isChecked == true) {
                                    val notifSubject = actvTitle?.text?.toString()?.trim() ?: ""
                                    var notifBody = actvContent?.text?.toString()?.trim() ?: ""
                                    notifBody += "\n\nRegards,\nTeam CashDash"
                                    
                                    if (notifSubject.isNotEmpty() && notifBody.isNotEmpty()) {
                                        sendAdminInAppNotification(email, notifSubject, notifBody)
                                    }
                                }
                                logAdminAction("REVOKED_ACCESS", email, "Revoked all admin privileges.")
                                logAdminAction("Admin Revocation", "Access Revoked", "$actorEmail revoked admin access for $email", email)
                                ToastHelper.showToast(this, "Access revoked")
                                closeEditPermissionsView()
                            }
                            .addOnFailureListener { e ->
                                ToastHelper.showToast(this, "Failed to revoke: ${e.message}")
                            }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
                }
                dialog.show()
            }
        }
    }

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
        // EditText contents (search, notification title/body) are auto-saved by Android
        // via view state since they have android:id set. We only need to persist
        // variables that aren't reflected in the view hierarchy.
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Nothing extra to restore — EditText state is automatically re-applied
        // by Android's view state restoration.
    }

}
