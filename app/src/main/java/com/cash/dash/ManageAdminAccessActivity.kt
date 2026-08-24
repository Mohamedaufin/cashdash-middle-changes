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
        
        btnAddAdminSection?.visibility = if (AdminManager.getPermissions().canAllocateAdmins()) View.VISIBLE else View.GONE
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
                                launchEditPermissionsActivity(email, name, requestedPerms, isNewAdmin = false, isReviewingRequest = true, isExtensionRequest = isExtension)
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
                launchEditPermissionsActivity(email, name, perms, isNewAdmin = false)
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
                launchEditPermissionsActivity(user.email, user.name, AdminManager.AdminPermissions(), isNewAdmin = true)
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

    private fun launchEditPermissionsActivity(
        email: String,
        name: String,
        perms: AdminManager.AdminPermissions,
        isNewAdmin: Boolean = false,
        isReviewingRequest: Boolean = false,
        isExtensionRequest: Boolean = false
    ) {
        val intent = android.content.Intent(this, EditAdminPermissionsActivity::class.java).apply {
            putExtra("email", email)
            putExtra("name", name)
            putExtra("isNewAdmin", isNewAdmin)
            putExtra("isReviewingRequest", isReviewingRequest)
            putExtra("isExtensionRequest", isExtensionRequest)
            putExtra("isFixedOwner", perms.isFixedOwner)
            putExtra("isPromotedOwner", perms.isPromotedOwner)
            putExtra("fullAccess", perms.fullAccess)
            putExtra("sendAnnouncements", perms.sendAnnouncements)
            putExtra("sendPromotions", perms.sendPromotions)
            putExtra("sendNotifications", perms.sendNotifications)
            putExtra("viewLastSeen", perms.viewLastSeen)
            putExtra("viewAdminLogs", perms.viewAdminLogs)
            putExtra("replyToQueries", perms.replyToQueries)
            putExtra("allocateAdmins", perms.allocateAdmins)
            putExtra("validUntil", perms.validUntil)
        }
        startActivity(intent)
    }

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
