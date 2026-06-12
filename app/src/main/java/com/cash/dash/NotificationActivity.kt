package com.cash.dash

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

class NotificationActivity : ThemedActivity() {

    private var allNotifications = listOf<NotificationModel>()
    private var filteredNotifications = listOf<NotificationModel>()
    private var rawUserNotifications = listOf<NotificationEntity>()
    private var rawAnnouncements = listOf<NotificationEntity>()
    private var currentFilter = "all" // "all", "responded", "pending"
    private lateinit var adapter: NotificationAdapter
    private var notificationListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var hasScrolledToUnread = false

    private val initiallyReadAnnouncements = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val initiallyUnreadNotifications = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val animatedItems = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val selectedReplyImages = mutableMapOf<String, MutableList<Uri>>()
    private val replyUploadedUrls = mutableMapOf<String, MutableMap<Uri, String>>()
    private val replyUploadProgress = mutableMapOf<String, MutableMap<Uri, Int>>()
    private var activePickerQueryId: String? = null

    private val replyPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selectedUri = result.data?.data
            val id = activePickerQueryId
            if (selectedUri != null && id != null) {
                val list = selectedReplyImages.getOrPut(id) { mutableListOf() }
                if (list.size < 4) {
                    list.add(selectedUri)
                    uploadReplyImage(id, selectedUri)
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun updateViewHolderUploadProgress(holder: NotificationAdapter.ViewHolder, queryId: String) {
        val progressMap = replyUploadProgress[queryId] ?: emptyMap()
        if (progressMap.isNotEmpty()) {
            val avgProgress = progressMap.values.average().toInt()
            holder.layoutUploadProgress.visibility = View.VISIBLE
            holder.tvUploadStatus.text = "Uploading images… ($avgProgress%)"
            holder.progressUpload.progress = avgProgress
        } else {
            holder.layoutUploadProgress.visibility = View.GONE
            holder.progressUpload.progress = 0
        }
    }

    private fun uploadReplyImage(queryId: String, uri: Uri) {
        val progressMap = replyUploadProgress.getOrPut(queryId) { mutableMapOf() }
        progressMap[uri] = 0

        // Trigger UI update
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? NotificationAdapter.ViewHolder
            if (holder != null) {
                val pos = holder.adapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos < filteredNotifications.size) {
                    if (filteredNotifications[pos].id == queryId) {
                        updateViewHolderUploadProgress(holder, queryId)
                    }
                }
            }
        }

        val storageRef = FirebaseStorage.getInstance().reference
        val imageRef = storageRef.child("support_attachments/${System.currentTimeMillis()}_reply.jpg")

        imageRef.putFile(uri)
            .addOnProgressListener { taskSnapshot ->
                val percent = if (taskSnapshot.totalByteCount > 0) {
                    (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                } else 0
                
                // Update progress map
                val pm = replyUploadProgress[queryId]
                if (pm != null && pm.containsKey(uri)) {
                    pm[uri] = percent
                    
                    // Update visible ViewHolder
                    runOnUiThread {
                        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i)
                            val holder = rv.getChildViewHolder(child) as? NotificationAdapter.ViewHolder
                            if (holder != null) {
                                val pos = holder.adapterPosition
                                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos < filteredNotifications.size) {
                                    if (filteredNotifications[pos].id == queryId) {
                                        updateViewHolderUploadProgress(holder, queryId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .addOnSuccessListener {
                imageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    // Save URL
                    val urlMap = replyUploadedUrls.getOrPut(queryId) { mutableMapOf() }
                    urlMap[uri] = downloadUri.toString()
                    
                    // Remove from progress
                    replyUploadProgress[queryId]?.remove(uri)
                    
                    runOnUiThread {
                        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i)
                            val holder = rv.getChildViewHolder(child) as? NotificationAdapter.ViewHolder
                            if (holder != null) {
                                val pos = holder.adapterPosition
                                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos < filteredNotifications.size) {
                                    if (filteredNotifications[pos].id == queryId) {
                                        updateViewHolderUploadProgress(holder, queryId)
                                    }
                                }
                            }
                        }
                    }
                }.addOnFailureListener { e ->
                    replyUploadProgress[queryId]?.remove(uri)
                    selectedReplyImages[queryId]?.remove(uri)
                    runOnUiThread {
                        adapter.notifyDataSetChanged()
                        ToastHelper.showToast(this, "Failed to get download URL: ${e.message}")
                        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i)
                            val holder = rv.getChildViewHolder(child) as? NotificationAdapter.ViewHolder
                            if (holder != null) {
                                val pos = holder.adapterPosition
                                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos < filteredNotifications.size) {
                                    if (filteredNotifications[pos].id == queryId) {
                                        updateViewHolderUploadProgress(holder, queryId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                replyUploadProgress[queryId]?.remove(uri)
                selectedReplyImages[queryId]?.remove(uri)
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    ToastHelper.showToast(this, "Failed to upload image: ${e.message}")
                    val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
                    for (i in 0 until rv.childCount) {
                        val child = rv.getChildAt(i)
                        val holder = rv.getChildViewHolder(child) as? NotificationAdapter.ViewHolder
                        if (holder != null) {
                            val pos = holder.adapterPosition
                            if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos < filteredNotifications.size) {
                                    if (filteredNotifications[pos].id == queryId) {
                                        updateViewHolderUploadProgress(holder, queryId)
                                    }
                            }
                        }
                    }
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationListener?.remove()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        // Capture initially read announcements before any marking as read occurs in this session
        val readPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)
        initiallyReadAnnouncements.addAll(readPrefs.all.keys)

        setupRecyclerView()
        setupFilters()
        
        loadNotifications()
    }

    private fun setupRecyclerView() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        adapter = NotificationAdapter(mutableListOf(), 
            onDelete = { model -> showDeleteConfirmDialog(model) }
        )
        rv.adapter = adapter
    }

    private fun setupFilters() {
        findViewById<TextView>(R.id.chipAll).setOnClickListener { setFilter("all") }
        findViewById<TextView>(R.id.chipResponded).setOnClickListener { setFilter("responded") }
        findViewById<TextView>(R.id.chipPending).setOnClickListener { setFilter("pending") }
        updateChipAppearance()
    }

    private fun setFilter(filter: String) {
        currentFilter = filter
        updateChipAppearance()
        applyFilter()
    }

    private fun updateChipAppearance() {
        val chipAll = findViewById<TextView>(R.id.chipAll)
        val chipResponded = findViewById<TextView>(R.id.chipResponded)
        val chipPending = findViewById<TextView>(R.id.chipPending)

        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#606880")

        chipAll.setTextColor(if (currentFilter == "all") activeColor else inactiveColor)
        chipResponded.setTextColor(if (currentFilter == "responded") activeColor else inactiveColor)
        chipPending.setTextColor(if (currentFilter == "pending") activeColor else inactiveColor)
    }
    
    private fun markAllAsRead() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        val db = FirebaseFirestore.getInstance()
        
        // Update in-memory lists so animation stops/does not trigger
        allNotifications = allNotifications.map { it.copy(isUnread = false) }
        filteredNotifications = filteredNotifications.map { it.copy(isUnread = false) }
        adapter.updateList(filteredNotifications)
        
        // 1. Mark user support queries as read in Firestore and Room
        db.collection("users").document(email).collection("notifications")
            .whereEqualTo("read", false)
            .get()
            .addOnSuccessListener { docs ->
                if (!docs.isEmpty) {
                    val batch = db.batch()
                    for (doc in docs) batch.update(doc.reference, "read", true)
                    batch.commit()
                    
                    // Sync to Room
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = AppDatabase.getDatabase(this@NotificationActivity).notificationDao()
                        for (doc in docs) dao.updateReadStatus(doc.id, true)
                    }
                }
            }

        // 2. Mark announcements as read locally
        db.collection("announcements")
            .get()
            .addOnSuccessListener { adminDocs ->
                val readPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)
                val editor = readPrefs.edit()
                for (doc in adminDocs) {
                    editor.putBoolean(doc.id, true)
                }
                editor.apply()

                // Sync to Room so they persist as read locally
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = AppDatabase.getDatabase(this@NotificationActivity).notificationDao()
                    for (doc in adminDocs) {
                        dao.updateReadStatus(doc.id, true)
                    }
                }
            }
    }

    private fun loadNotifications() {
        loadFromRoomAndRender()
        fetchAnnouncementsAndListen()
    }

    private fun fetchAnnouncementsAndListen() {
        val db = FirebaseFirestore.getInstance()
        db.collection("announcements")
            .get()
            .addOnSuccessListener { adminDocs ->
                val user = FirebaseAuth.getInstance().currentUser
                val email = user?.email?.lowercase() ?: ""
                val registrationTime = user?.metadata?.creationTimestamp ?: 0L
                val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
                val isAdmin = adminEmails.contains(email)

                val deletedPrefs = getSharedPreferences("DeletedAnnouncements", MODE_PRIVATE)
                val readAnnPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)

                 rawAnnouncements = adminDocs.mapNotNull { doc ->
                    val timestamp = doc.getLong("timestamp") ?: doc.id.toLongOrNull() ?: 0L
                    if (timestamp < registrationTime) {
                        null
                    } else if (deletedPrefs.contains(doc.id)) {
                        null
                    } else {
                        val adminOnly = doc.getBoolean("adminOnly") ?: false
                        val targetEmails = doc.get("targetEmails") as? List<String>
                        if (adminOnly && !isAdmin) {
                            null
                        } else if (targetEmails != null && !targetEmails.map { it.lowercase() }.contains(email)) {
                            null
                        } else {
                            val isRead = readAnnPrefs.contains(doc.id)
                            NotificationEntity(
                                id = doc.id,
                                subject = doc.getString("subject") ?: "Announcement",
                                query = doc.getString("query") ?: "No query",
                                reply = doc.getString("reply") ?: "[ANNOUNCEMENT]",
                                timestamp = timestamp,
                                status = "resolved",
                                read = isRead,
                                imageUrl = doc.getString("imageUrl"),
                                imageUrls = (doc.get("imageUrls") as? List<*>)?.mapNotNull { it as? String }?.let { org.json.JSONArray(it).toString() }
                            )
                        }
                    }
                }
                listenToUserNotifications()
            }
            .addOnFailureListener {
                listenToUserNotifications()
            }
    }

    private fun listenToUserNotifications() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_email", null) ?: return
        val db = FirebaseFirestore.getInstance()
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)

        notificationListener?.remove()
        notificationListener = db.collection("users").document(email).collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { docs, e ->
                if (e != null) {
                    tvEmpty.text = "Failed to load notifications.\nPlease check your connection."
                    tvEmpty.visibility = View.VISIBLE
                    findViewById<LinearLayout>(R.id.filterBar).visibility = View.VISIBLE
                    rv.visibility = View.GONE
                    allNotifications = emptyList()
                    return@addSnapshotListener
                }

                if (docs == null || docs.isEmpty) {
                    val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    prefs.edit().putBoolean("migrated_notifications_uid", true).apply()
                    rawUserNotifications = emptyList()
                    val allEntities = (rawUserNotifications + rawAnnouncements).sortedByDescending { it.timestamp }
                    saveToRoom(allEntities)
                    val models = mapEntitiesToModels(allEntities)
                    allNotifications = models
                    applyFilter()
                    return@addSnapshotListener
                }

                // 1. Silent cleanup for duplicates
                val rawDocs = docs.documents
                val grouped = rawDocs.groupBy { "${it.getString("subject")}|${it.getString("query")}" }
                val toDelete = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
                
                for (group in grouped.values) {
                    if (group.size > 1) {
                        val keeper = group.find { (it.getString("reply") ?: "Waiting for reply...") != "Waiting for reply..." }
                            ?: group.maxByOrNull { it.getLong("timestamp") ?: 0L } ?: group.first()
                        group.forEach { if (it.id != keeper.id) toDelete.add(it) }
                    }
                }
                
                if (toDelete.isNotEmpty()) {
                    val batch = db.batch()
                    toDelete.forEach { batch.delete(it.reference) }
                    batch.commit()
                }

                // 🚀 ASYNCHRONOUS OPTIMIZATION: Heavy HTML parsing & mapping offloaded from UI Thread
                CoroutineScope(Dispatchers.Default).launch {
                    // 1. Filter and Map to Entities
                    val finalDocs = rawDocs.filter { d -> !toDelete.any { it.id == d.id } }
                    val entities = finalDocs.map { doc ->
                        val readVal = doc.getBoolean("read") ?: true
                        if (!readVal) {
                            initiallyUnreadNotifications.add(doc.id)
                        }
                        NotificationEntity(
                            id = doc.id,
                            subject = doc.getString("subject") ?: "General Help",
                            query = doc.getString("query") ?: "No query",
                            reply = doc.getString("reply") ?: "Waiting for reply...",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            status = doc.getString("status") ?: "",
                            read = readVal,
                            imageUrl = doc.getString("imageUrl"),
                            imageUrls = (doc.get("imageUrls") as? List<*>)?.mapNotNull { it as? String }?.let { org.json.JSONArray(it).toString() }
                        )
                    }
                    
                    rawUserNotifications = entities
                    val allEntities = (rawUserNotifications + rawAnnouncements).sortedByDescending { it.timestamp }
                    saveToRoom(allEntities)

                    // 2. Heavy CPU computation: String replacement + HTML interpolation
                    val models = mapEntitiesToModels(allEntities)
                    
                    // 3. Push results to UI
                    withContext(Dispatchers.Main) {
                        allNotifications = models
                        applyFilter()
                    }
                }
            }
    }

    private fun saveToRoom(entities: List<NotificationEntity>) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(this@NotificationActivity)
            db.notificationDao().deleteAll()
            db.notificationDao().insertAll(entities)
        }
    }

    private fun loadFromRoomAndRender() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(this@NotificationActivity)
            val entities = db.notificationDao().getAll()
            
            val deletedPrefs = getSharedPreferences("DeletedAnnouncements", MODE_PRIVATE)
            val readAnnPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)
            
            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email?.lowercase() ?: ""
            val registrationTime = user?.metadata?.creationTimestamp ?: 0L
            val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
            val isAdmin = adminEmails.contains(email)

            val filteredEntities = entities.filter { !deletedPrefs.contains(it.id) }.mapNotNull { entity ->
                if (entity.reply == "[ANNOUNCEMENT]") {
                    val isSubjectAdminOnly = entity.subject.startsWith("🛡️ [Admin Only]")
                    if (isSubjectAdminOnly && !isAdmin) {
                        null
                    } else if (entity.timestamp < registrationTime) {
                        null
                    } else {
                        entity.copy(read = readAnnPrefs.contains(entity.id))
                    }
                } else {
                    if (!entity.read) {
                        initiallyUnreadNotifications.add(entity.id)
                    }
                    entity
                }
            }
            
            val models = mapEntitiesToModels(filteredEntities)
            
            withContext(Dispatchers.Main) {
                allNotifications = models
                if (allNotifications.isNotEmpty()) applyFilter()
            }
        }
    }

    private fun mapEntitiesToModels(entities: List<NotificationEntity>): List<NotificationModel> {
        val sdf = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
        val now = System.currentTimeMillis()
        val fortyEightHours = 48 * 60 * 60 * 1000L

        val userPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val userName = userPrefs.getString("user_name", "User") ?: "User"
        val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
        val colorTeam = if (isWhite) "#008000" else "#4ADE80"
        val colorContent = if (isWhite) "#333333" else "#E0EBF5"
        val colorPending = if (isWhite) "#CD8500" else "#FFD93D"
        val userFormat = if (isWhite) "<font color='#0047AB'>$userName:</font>" else "$userName:"

        return entities.map { entity ->
            val query = entity.query
            val reply = entity.reply
            val subject = entity.subject
            val ts = entity.timestamp
            val status = entity.status.ifEmpty { if (reply == "Waiting for reply...") "pending" else "responded" }

            var isResolved = status == "resolved" || 
                             reply.contains("[RESOLVED]", ignoreCase = true) || 
                             reply.contains("[DONE]", ignoreCase = true)
            
            if (!isResolved && status == "responded" && (now - ts) > fortyEightHours) {
                isResolved = true
            }

            val isPending = (reply == "Waiting for reply...")
            val isAnnouncement = (reply == "[ANNOUNCEMENT]")
            val queryTitle = when {
                isAnnouncement -> "ANNOUNCEMENT"
                isResolved -> "Query resolved"
                isPending -> "Waiting for response"
                else -> "Query responded"
            }

            val color = Color.parseColor(when {
                isAnnouncement -> colorTeam
                isResolved -> "#606880"
                isPending -> colorPending
                else -> colorTeam
            })

            val displayQuery = if (isAnnouncement) {
                query.replace("\n", "<br>")
            } else {
                query
                    .replace("User Reply \\(\\d+\\):".toRegex(), userFormat)
                    .replace("User:".toRegex(), userFormat)
                    .replace("$userName:".toRegex(), userFormat)
                    .replace("Team Cashdash:".toRegex(), "<font color='$colorTeam'><b>Team Cashdash:</b></font>")
                    .replace("\n", "<br>")
            }

            NotificationModel(
                id = entity.id,
                queryFormatted = if (isAnnouncement) {
                    val cleanSubject = subject
                        .replace("🛡️ [Admin Only] ", "")
                        .replace("[Admin Only] ", "")
                        .replace("📣 ", "")
                        .trim()
                    android.text.Html.fromHtml("<b>Title:</b> $cleanSubject<br><br>${displayQuery}", android.text.Html.FROM_HTML_MODE_LEGACY)
                } else {
                    android.text.Html.fromHtml("<b>Subject:</b> $subject<br><b>Question:</b> $displayQuery", android.text.Html.FROM_HTML_MODE_LEGACY)
                },
                replyFormatted = if (isPending || isAnnouncement) null else android.text.Html.fromHtml("<font color='$colorTeam'><b>Response</b></font><br><font color='$colorContent'>${reply.replace("\n", "<br>")}</font>", android.text.Html.FROM_HTML_MODE_LEGACY),
                timestamp = ts,
                title = queryTitle,
                timeFormatted = if (ts > 0) sdf.format(Date(ts)) else "",
                statusColor = color,
                isPending = isPending,
                isUnread = !entity.read,
                isResolved = isResolved,
                originalSubject = subject,
                originalQuery = query,
                originalReply = reply,
                imageUrl = entity.imageUrl,
                imageUrls = entity.imageUrls?.let {
                    try { val arr = org.json.JSONArray(it); (0 until arr.length()).map { i -> arr.getString(i) } }
                    catch (e: Exception) { emptyList() }
                } ?: emptyList()
            )
        }
    }

    private fun applyFilter() {
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        val filterBar = findViewById<LinearLayout>(R.id.filterBar)

        // ALWAYS SHOW FILTER BAR AS PER USER REQUEST
        filterBar.visibility = View.VISIBLE

        if (allNotifications.isEmpty()) {
            rv.visibility = View.GONE
            tvEmpty.text = "No notifications yet.\n\nWe'll notify you when your support\nqueries are resolved!"
            tvEmpty.visibility = View.VISIBLE
            return
        }

        filteredNotifications = when (currentFilter) {
            "responded" -> allNotifications.filter { !it.isPending }
            "pending" -> allNotifications.filter { it.isPending }
            else -> allNotifications
        }

        filterBar.visibility = View.VISIBLE
        if (filteredNotifications.isEmpty()) {
            rv.visibility = View.GONE
            tvEmpty.text = if (currentFilter == "pending") "All your queries have been responded!" else "No queries have been answered yet."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
            adapter.updateList(filteredNotifications)

            if (!hasScrolledToUnread) {
                hasScrolledToUnread = true
                val firstUnreadIndex = filteredNotifications.indexOfFirst { it.isUnread }
                if (firstUnreadIndex != -1) {
                    rv.post {
                        rv.scrollToPosition(firstUnreadIndex)
                    }
                }
                markAllAsRead()
            }
        }
    }

    private fun showDeleteConfirmDialog(model: NotificationModel) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }
        
        TextView(this).apply {
            text = "Delete query?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
            box.addView(this)
        }

        TextView(this).apply {
            text = "Remove this query from your history?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
            gravity = android.view.Gravity.CENTER
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, (28 * density).toInt())
            box.addView(this)
        }

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            minHeight = (54 * density).toInt()
            setOnClickListener { 
                adapter.notifyDataSetChanged() // Reset swipe state
                dialog.dismiss() 
            }
            btnContainer.addView(this)
        }

        Button(this).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            minHeight = (54 * density).toInt()
            setOnClickListener {
                val user = FirebaseAuth.getInstance().currentUser ?: return@setOnClickListener
                val email = user.email ?: return@setOnClickListener
                
                dialog.dismiss()
                ToastHelper.showToast(this@NotificationActivity, "Query deleted")
                
                // Explicit Optimistic UI Update from RAM (Instant Execution)
                val mutList = allNotifications.toMutableList()
                mutList.removeAll { it.id == model.id }
                allNotifications = mutList
                
                if (allNotifications.isEmpty()) {
                    saveToRoom(emptyList()) // Ensures the empty state sticks
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.getDatabase(this@NotificationActivity).notificationDao().deleteById(model.id)
                    }
                }

                applyFilter() // Redraws RecyclerView instantaneously
                
                // Process the deletion with Firestore/SharedPreferences sequentially
                if (model.originalReply == "[ANNOUNCEMENT]") {
                    val prefs = getSharedPreferences("DeletedAnnouncements", MODE_PRIVATE)
                    prefs.edit().putBoolean(model.id, true).apply()
                } else {
                    FirebaseFirestore.getInstance().collection("users").document(email)
                        .collection("notifications").document(model.id).delete()
                }
            }
            btnContainer.addView(this)
        }
        
        box.addView(btnContainer)
        dialog.show()
    }

    // --- ADAPTER ---
    inner class NotificationAdapter(
        private var items: MutableList<NotificationModel>,
        private val onDelete: (NotificationModel) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

        fun updateList(newItems: List<NotificationModel>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val v = layoutInflater.inflate(R.layout.item_notification, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            
            // Reset view state (crucial for Cancel or recycling)
            holder.itemView.translationX = 0f
            holder.itemView.alpha = 1f
            holder.viewAccentBar.setBackgroundColor(item.statusColor)
            holder.tvTime.text = item.timeFormatted

            val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
            val colorTeam = if (isWhite) "#008000" else "#4ADE80"
            val colorContent = if (isWhite) "#333333" else "#E0EBF5"
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val userName = prefs.getString("user_name", "User") ?: "User"
            val userFormat = if (isWhite) "<font color='#0047AB'><b>$userName:</b></font>" else "<b>$userName:</b>"

            // Clear dynamic timeline container
            holder.layoutTimelineContainer.removeAllViews()

            // Collect all attachment URLs
            val allUrls = item.imageUrls.toMutableList()
            if (item.imageUrl != null && !allUrls.contains(item.imageUrl)) allUrls.add(0, item.imageUrl)

            val attachmentRegex = "\\[Attachment: (.*?)\\]".toRegex()

            if (item.originalReply == "[ANNOUNCEMENT]") {
                val cleanSubject = item.originalSubject.replace("🛡️ [Admin Only] ", "").replace("📣 ", "")
                val titleHtml = "<font color='$colorTeam'><b>ANNOUNCEMENT</b></font>"
                holder.tvTitle.text = android.text.Html.fromHtml(titleHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                holder.tvTitle.setTextColor(item.statusColor)

                // Add announcement body to timeline
                val tv = TextView(holder.itemView.context).apply {
                    setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                    textSize = 14f
                    setLineSpacing(0f, 1.4f)
                    setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                }
                tv.text = item.queryFormatted
                if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                    tv.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                }
                holder.layoutTimelineContainer.addView(tv)

                if (allUrls.isNotEmpty()) {
                    renderAttachments(holder.itemView.context, allUrls, holder.layoutTimelineContainer)
                }
            } else {
                holder.tvTitle.text = item.title
                holder.tvTitle.setTextColor(item.statusColor)

                val rawBlocks = item.originalQuery.split("\n\n").filter { it.isNotBlank() }

                // Parse each block's own [Attachment:] markers first
                val blocksData = rawBlocks.map { block ->
                    val urls = attachmentRegex.findAll(block).map { it.groupValues[1] }.toList()
                    val clean = block.replace(attachmentRegex, "").trim()
                    clean to urls
                }

                // Gather all inline URLs from all query blocks
                val queryInlineUrls = blocksData.flatMap { it.second }.toSet()

                // Also gather URLs already embedded in the reply field as [Attachment:] markers
                // These belong to the admin reply block and must NOT be treated as legacy user attachments
                val replyInlineUrls = attachmentRegex.findAll(item.originalReply)
                    .map { it.groupValues[1] }.toSet()

                // True legacy = URLs in imageUrls/imageUrl Firestore fields that are NOT referenced
                // anywhere in the conversation (neither query blocks nor reply)
                val allConversationUrls = queryInlineUrls + replyInlineUrls
                val legacyUrls = allUrls.filter { it !in allConversationUrls }

                // Render each block in chronological order
                for ((idx, data) in blocksData.withIndex()) {
                    val (cleanText, blockUrls) = data
                    val tv = TextView(holder.itemView.context).apply {
                        setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                        textSize = 14f
                        setLineSpacing(0f, 1.4f)
                        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                    }

                    if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                        tv.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                    }

                    if (idx == 0) {
                        val formattedHtml = "<b>Subject:</b> ${item.originalSubject}<br><b>Question:</b> ${cleanText.replace("\n", "<br>")}"
                        tv.text = android.text.Html.fromHtml(formattedHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                        holder.layoutTimelineContainer.addView(tv)

                        // For block[0]: show its own inline markers PLUS any legacy attachments
                        val firstBlockUrls = blockUrls + legacyUrls
                        if (firstBlockUrls.isNotEmpty()) {
                            val attachmentLayout = LinearLayout(holder.itemView.context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                            }
                            renderAttachments(holder.itemView.context, firstBlockUrls, attachmentLayout)
                            holder.layoutTimelineContainer.addView(attachmentLayout)
                        }
                    } else {
                        val teamTag = "Team Cashdash:"
                        val formattedSpanned = when {
                            cleanText.startsWith(teamTag) -> {
                                val content = cleanText.removePrefix(teamTag).trim()
                                val html = "<font color='$colorTeam'><b>Team Cashdash:</b></font> ${content.replace("\n", "<br>")}"
                                android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                            }
                            cleanText.startsWith("User:") -> {
                                val content = cleanText.removePrefix("User:").trim()
                                val html = "$userFormat ${content.replace("\n", "<br>")}"
                                android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                            }
                            cleanText.startsWith("$userName:") -> {
                                val content = cleanText.removePrefix("$userName:").trim()
                                val html = "$userFormat ${content.replace("\n", "<br>")}"
                                android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                            }
                            else -> {
                                val firstColon = cleanText.indexOf(":")
                                if (firstColon > 0 && firstColon < 30) {
                                    val potentialName = cleanText.substring(0, firstColon)
                                    val content = cleanText.substring(firstColon + 1).trim()
                                    val html = if (potentialName == "Team Cashdash") {
                                        "<font color='$colorTeam'><b>Team Cashdash:</b></font> ${content.replace("\n", "<br>")}"
                                    } else {
                                        "<font color='#0047AB'><b>$potentialName:</b></font> ${content.replace("\n", "<br>")}"
                                    }
                                    android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                                } else {
                                    android.text.Html.fromHtml(cleanText.replace("\n", "<br>"), android.text.Html.FROM_HTML_MODE_LEGACY)
                                }
                            }
                        }
                        tv.text = formattedSpanned
                        holder.layoutTimelineContainer.addView(tv)

                        // Each block shows ONLY its own attachments — no cross-block leakage
                        if (blockUrls.isNotEmpty()) {
                            val attachmentLayout = LinearLayout(holder.itemView.context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                            }
                            renderAttachments(holder.itemView.context, blockUrls, attachmentLayout)
                            holder.layoutTimelineContainer.addView(attachmentLayout)
                        }
                    }
                }

                // If there's a latest admin reply block that is not announcement/pending, render it
                val originalReply = item.originalReply
                if (originalReply.isNotEmpty() && originalReply != "Waiting for reply...") {
                    val replyUrls = attachmentRegex.findAll(originalReply).map { it.groupValues[1] }.toList()
                    val cleanReply = originalReply.replace(attachmentRegex, "").trim()

                    val tv = TextView(holder.itemView.context).apply {
                        setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                        textSize = 14f
                        setLineSpacing(0f, 1.4f)
                        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                    }
                    if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                        tv.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                    }

                    val replyHtml = "<font color='$colorTeam'><b>Team Cashdash:</b></font> ${cleanReply.replace("\n", "<br>")}"
                    tv.text = android.text.Html.fromHtml(replyHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                    holder.layoutTimelineContainer.addView(tv)

                    if (replyUrls.isNotEmpty()) {
                        val attachmentLayout = LinearLayout(holder.itemView.context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                        }
                        renderAttachments(holder.itemView.context, replyUrls, attachmentLayout)
                        holder.layoutTimelineContainer.addView(attachmentLayout)
                    }
                }
            }

            // 🔥 Eliminate smudge glow in Blue Theme explicitly for title
            if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                holder.tvTitle.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvTime.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvResolvedStatus.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
            }

            // Status & Action Visibility Logic
            if (item.originalReply == "[ANNOUNCEMENT]") {
                holder.layoutReplyBox.visibility = View.GONE
                holder.tvResolvedStatus.visibility = View.GONE
            } else if (item.isResolved) {
                holder.layoutReplyBox.visibility = View.GONE
                holder.tvResolvedStatus.visibility = View.VISIBLE
            } else {
                holder.tvResolvedStatus.visibility = View.GONE
                // Reply box is always visible when not resolved (unlocked)
                holder.layoutReplyBox.visibility = View.VISIBLE
            }

            // Setup Media Gallery Attachments button inside the reply box
            holder.btnAttachMedia.setOnClickListener {
                activePickerQueryId = item.id
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                }
                replyPickerLauncher.launch(intent)
            }

            // Render selected reply attachments previews
            updateReplyPreviews(holder, item.id)
            updateViewHolderUploadProgress(holder, item.id)

            holder.btnSendReply.setOnClickListener {
                val replyText = holder.edtReply.text.toString().trim()
                if (replyText.isEmpty() && (selectedReplyImages[item.id] ?: mutableListOf()).isEmpty()) return@setOnClickListener

                val progressMap = replyUploadProgress[item.id] ?: emptyMap()
                if (progressMap.isNotEmpty()) {
                    ToastHelper.showToast(this@NotificationActivity, "Please wait till images get uploaded")
                    return@setOnClickListener
                }

                holder.btnSendReply.isEnabled = false
                submitUserReply(item, replyText, holder)
            }

            // 🔥 Auto-scroll to item when keyboard opens/focused
            holder.edtReply.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.itemView.postDelayed({
                        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
                        val pos = holder.adapterPosition
                        if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            rv.smoothScrollToPosition(pos)
                        }
                    }, 500) // Slightly longer to ensure keyboard is up
                }
            }

            // --- High-Sensitivity Swipe Logic ---
            var startRawX = 0f
            var startRawY = 0f
            var startTranslationX = 0f
            var isSwiping = false

            val swipeTouch = View.OnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startTranslationX = holder.itemView.translationX
                        isSwiping = false
                        v == holder.itemView || (v !is EditText && v !is Button && v !is ImageButton)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dX = event.rawX - startRawX
                        val absDX = Math.abs(dX)
                        val absDY = Math.abs(event.rawY - startRawY)

                        if (!isSwiping && absDX > 5 && absDX > absDY) { // Ultra-low threshold + horizontal bias
                            isSwiping = true
                            holder.itemView.parent?.requestDisallowInterceptTouchEvent(true)
                        }

                        if (isSwiping) {
                            // Only allow left swiping (negative translation)
                            if (dX < 0) {
                                holder.itemView.translationX = dX
                                holder.itemView.alpha = 1f - (absDX / holder.itemView.width.toFloat())
                            }
                        }
                        isSwiping
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isSwiping) {
                            val dX = event.rawX - startRawX
                            if (Math.abs(dX) > holder.itemView.width * 0.3) {
                                // Trigger delete
                                isSwiping = false // Reset
                                holder.itemView.animate()
                                    .translationX(-holder.itemView.width.toFloat())
                                    .alpha(0f)
                                    .setDuration(200)
                                    .withEndAction { onDelete(item) }
                                    .start()
                            } else {
                                // Snap back
                                isSwiping = false // Reset
                                holder.itemView.animate()
                                    .translationX(0f)
                                    .alpha(1f)
                                    .setDuration(200)
                                    .start()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }

            // Ensure root is clickable to receive ACTION_DOWN
            holder.itemView.isClickable = true
            // Apply recursively to all children so swipes starting on text/replies work
            applySwipeRecursively(holder.itemView, swipeTouch)

            // Animation for unread
            val isAnnouncement = item.originalReply == "[ANNOUNCEMENT]"
            val shouldAnimate = if (isAnnouncement) {
                !initiallyReadAnnouncements.contains(item.id)
            } else {
                initiallyUnreadNotifications.contains(item.id)
            }

            if (shouldAnimate && !item.isPending && !animatedItems.contains(item.id)) {
                animatedItems.add(item.id)
                val anim = android.view.animation.ScaleAnimation(1f, 1.05f, 1f, 1.05f, 
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, 
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 300
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = 3
                }
                holder.itemView.postDelayed({ holder.itemView.startAnimation(anim) }, 300)
            } else {
                holder.itemView.clearAnimation()
            }
        }

        private fun renderAttachments(context: Context, urls: List<String>, container: LinearLayout) {
            if (urls.isEmpty()) return
            val density = context.resources.displayMetrics.density
            val cardW = (94 * density).toInt()
            val cardH = (136 * density).toInt()
            val gap = (8 * density).toInt()
            val rowGap = (8 * density).toInt()

            fun makeCard(url: String): androidx.cardview.widget.CardView {
                val card = androidx.cardview.widget.CardView(context).apply {
                    radius = (8 * density)
                    cardElevation = 0f
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    tag = "attachment_card"
                    layoutParams = android.widget.LinearLayout.LayoutParams(cardW, cardH).apply {
                        setMargins(0, 0, gap, 0)
                    }
                }
                val imgView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                com.bumptech.glide.Glide.with(context).load(url).into(imgView)
                card.addView(imgView)
                card.setOnClickListener {
                    val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    val fullImgContainer = android.widget.FrameLayout(context)
                    fullImgContainer.layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val fullImg = com.github.chrisbanes.photoview.PhotoView(context)
                    fullImg.layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    fullImg.scaleType = ImageView.ScaleType.FIT_CENTER
                    com.bumptech.glide.Glide.with(context).load(url).into(fullImg)
                    val closeBtn = ImageButton(context)
                    val btnParams = android.widget.FrameLayout.LayoutParams(
                        (56 * density).toInt(),
                        (56 * density).toInt()
                    )
                    btnParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    btnParams.topMargin = (24 * density).toInt()
                    btnParams.marginEnd = (24 * density).toInt()
                    closeBtn.layoutParams = btnParams
                    val glassBg = android.graphics.drawable.GradientDrawable()
                    glassBg.shape = android.graphics.drawable.GradientDrawable.OVAL
                    glassBg.setColor(android.graphics.Color.parseColor("#66000000"))
                    glassBg.setStroke(3, android.graphics.Color.parseColor("#80FFFFFF"))
                    closeBtn.background = glassBg
                    closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    closeBtn.setColorFilter(android.graphics.Color.RED)
                    closeBtn.scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = (14 * density).toInt()
                    closeBtn.setPadding(p, p, p, p)
                    closeBtn.setOnClickListener { dialog.dismiss() }
                    fullImgContainer.addView(fullImg)
                    fullImgContainer.addView(closeBtn)
                    dialog.setContentView(fullImgContainer)
                    dialog.show()
                }
                return card
            }

            val chunks = urls.chunked(2)
            for ((rowIdx, chunk) in chunks.withIndex()) {
                val row = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { if (rowIdx > 0) topMargin = rowGap }
                }
                for (url in chunk) row.addView(makeCard(url))
                container.addView(row)
            }
        }

        private fun updateReplyPreviews(holder: ViewHolder, queryId: String) {
            val uris = selectedReplyImages[queryId] ?: mutableListOf()
            holder.layoutReplyPreviews.removeAllViews()
            if (uris.isEmpty()) {
                holder.scrollReplyPreviews.visibility = View.GONE
                return
            }
            holder.scrollReplyPreviews.visibility = View.VISIBLE
            val density = holder.itemView.context.resources.displayMetrics.density
            val size = (60 * density).toInt()
            val margin = (6 * density).toInt()
            for ((idx, uri) in uris.withIndex()) {
                val frame = FrameLayout(holder.itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        rightMargin = margin
                    }
                }
                val img = ImageView(holder.itemView.context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    clipToOutline = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8f * density
                    }
                }
                img.setImageURI(uri)
                val deleteBtn = ImageButton(holder.itemView.context).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setColorFilter(Color.RED)
                    background = android.graphics.drawable.ColorDrawable(Color.parseColor("#80000000"))
                    layoutParams = FrameLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    }
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        val uriToRemove = uris[idx]
                        uris.removeAt(idx)
                        replyUploadedUrls[queryId]?.remove(uriToRemove)
                        replyUploadProgress[queryId]?.remove(uriToRemove)
                        updateReplyPreviews(holder, queryId)
                        updateViewHolderUploadProgress(holder, queryId)
                    }
                }
                frame.addView(img)
                frame.addView(deleteBtn)
                holder.layoutReplyPreviews.addView(frame)
            }
        }

        private fun submitUserReply(model: NotificationModel, replyText: String, holder: ViewHolder) {
            val user = FirebaseAuth.getInstance().currentUser ?: return
            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val userName = prefs.getString("user_name", "User") ?: "User"
            val email = user.email ?: return
            
            val uploadedUrls = selectedReplyImages[model.id]?.mapNotNull { replyUploadedUrls[model.id]?.get(it) } ?: emptyList()

            fun sendReplyWithAttachments() {
                val cleanReplyText = if (replyText.isEmpty()) "(Sent an Attachment)" else replyText
                var textWithAttachments = cleanReplyText
                for (url in uploadedUrls) {
                    textWithAttachments += "\n[Attachment: $url]"
                }

                val lastTeamReply = model.originalReply
                val currentHistory = model.originalQuery
                
                val updatedQuery = if (lastTeamReply.isNotEmpty() && lastTeamReply != "Waiting for reply...") {
                    "$currentHistory\n\nTeam Cashdash: $lastTeamReply\n\n$userName: $textWithAttachments"
                } else {
                    "$currentHistory\n\n$userName: $textWithAttachments"
                }

                val finalImageUrls = model.imageUrls.toMutableList()
                finalImageUrls.addAll(uploadedUrls)
                
                val updateData = hashMapOf(
                    "query" to updatedQuery,
                    "reply" to "Waiting for reply...",
                    "status" to "pending",
                    "timestamp" to timestamp,
                    "read" to true,
                    "needs_admin_email" to true,
                    "is_reply" to true,
                    "imageUrls" to finalImageUrls
                )

                db.collection("users").document(email).collection("notifications").document(model.id)
                    .update(updateData as Map<String, Any>)
                    .addOnSuccessListener {
                        selectedReplyImages.remove(model.id)
                        replyUploadedUrls.remove(model.id)
                        replyUploadProgress.remove(model.id)
                        holder.edtReply.text.clear()
                        holder.btnSendReply.isEnabled = true
                        loadNotifications()

                        triggerImmediateWebhook(
                            uid = user.uid,
                            id = model.id,
                            name = userName,
                            email = email,
                            subject = model.originalSubject,
                            updatedQuery = updatedQuery,
                            originalQuery = model.originalQuery,
                            teamReply = model.originalReply,
                            userFollowup = textWithAttachments,
                            timestamp = timestamp
                        )
                    }
                    .addOnFailureListener {
                        holder.btnSendReply.isEnabled = true
                        ToastHelper.showToast(this@NotificationActivity, "Failed to send reply")
                    }
            }

            sendReplyWithAttachments()
        }

        private fun triggerImmediateWebhook(
            uid: String, id: String, name: String, email: String, subject: String, 
            updatedQuery: String, originalQuery: String, teamReply: String, userFollowup: String, timestamp: Long
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val webhookUrl = "https://cashdashwebhook-khhfw7mtba-uc.a.run.app"
                    val url = URL(webhookUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; utf-8")
                    conn.doOutput = true

                    val payload = JSONObject().apply {
                        put("uid", uid)
                        put("id", id)
                        put("name", name)
                        put("email", email)
                        put("subject", subject)
                        put("query", updatedQuery) // Unified history format for DB backward compat
                        put("originalQuery", originalQuery) // Thread separation for cleaner email render
                        put("teamReply", teamReply)
                        put("userFollowup", userFollowup)
                        put("is_reply", true)
                        put("timestamp", timestamp)
                    }

                    val os = conn.outputStream
                    val writer = OutputStreamWriter(os, "UTF-8")
                    writer.write(payload.toString())
                    writer.flush()
                    writer.close()
                    os.close()

                    val responseCode = conn.responseCode
                    Log.d("NotificationActivity", "⚡ Reply Webhook Sent. Response: $responseCode")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("NotificationActivity", "❌ Reply Webhook Failed: ${e.message}")
                }
            }
        }



        private fun applySwipeRecursively(view: View, listener: View.OnTouchListener) {
            // Skip attachment cards and the reply input container (which needs scroll and edit focus)
            if (view.tag == "attachment_card" || view.id == R.id.layoutReplyBox) return
            view.setOnTouchListener(listener)
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    applySwipeRecursively(view.getChildAt(i), listener)
                }
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val tvQuery = v.findViewById<TextView>(R.id.tvNotificationQuery)
            val tvReply = v.findViewById<TextView>(R.id.tvNotificationReply)
            val tvTime = v.findViewById<TextView>(R.id.tvNotificationTime)
            val tvTitle = v.findViewById<TextView>(R.id.tvNotificationTitle)
            val viewAccentBar = v.findViewById<View>(R.id.viewAccentBar)
            val layoutReplyBox = v.findViewById<LinearLayout>(R.id.layoutReplyBox)
            val edtReply = v.findViewById<EditText>(R.id.edtReply)
            val btnSendReply = v.findViewById<ImageButton>(R.id.btnSendReply)
            val tvResolvedStatus = v.findViewById<TextView>(R.id.tvResolvedStatus)
            val tvAttachmentLabel = v.findViewById<TextView>(R.id.tvAttachmentLabel)
            val layoutAttachments = v.findViewById<android.widget.LinearLayout>(R.id.layoutAttachments)
            val layoutTimelineContainer = v.findViewById<LinearLayout>(R.id.layoutTimelineContainer)
            val btnAttachMedia = v.findViewById<ImageButton>(R.id.btnAttachMedia)
            val scrollReplyPreviews = v.findViewById<HorizontalScrollView>(R.id.scrollReplyPreviews)
            val layoutReplyPreviews = v.findViewById<LinearLayout>(R.id.layoutReplyPreviews)
            // Upload progress views
            val layoutUploadProgress = v.findViewById<LinearLayout>(R.id.layoutUploadProgress)
            val tvUploadStatus = v.findViewById<TextView>(R.id.tvUploadStatus)
            val progressUpload = v.findViewById<android.widget.ProgressBar>(R.id.progressUpload)
        }
    }
}
