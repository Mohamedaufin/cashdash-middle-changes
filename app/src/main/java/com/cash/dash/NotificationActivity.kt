package com.cash.dash

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FirebaseFirestore

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

    override fun onDestroy() {
        super.onDestroy()
        notificationListener?.remove()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)


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
                val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
                val isAdmin = adminEmails.contains(email)

                val deletedPrefs = getSharedPreferences("DeletedAnnouncements", MODE_PRIVATE)
                val readAnnPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)

                rawAnnouncements = adminDocs.mapNotNull { doc ->
                    if (deletedPrefs.contains(doc.id)) {
                        null
                    } else {
                        val adminOnly = doc.getBoolean("adminOnly") ?: false
                        if (adminOnly && !isAdmin) {
                            null
                        } else {
                            val isRead = readAnnPrefs.contains(doc.id)
                            NotificationEntity(
                                id = doc.id,
                                subject = doc.getString("subject") ?: "Announcement",
                                query = doc.getString("query") ?: "No query",
                                reply = doc.getString("reply") ?: "[ANNOUNCEMENT]",
                                timestamp = doc.getLong("timestamp") ?: 0L,
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
                        NotificationEntity(
                            id = doc.id,
                            subject = doc.getString("subject") ?: "General Help",
                            query = doc.getString("query") ?: "No query",
                            reply = doc.getString("reply") ?: "Waiting for reply...",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            status = doc.getString("status") ?: "",
                            read = doc.getBoolean("read") ?: true,
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
            val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
            val isAdmin = adminEmails.contains(email)

            val filteredEntities = entities.filter { !deletedPrefs.contains(it.id) }.mapNotNull { entity ->
                if (entity.reply == "[ANNOUNCEMENT]") {
                    val isSubjectAdminOnly = entity.subject.startsWith("🛡️ [Admin Only]")
                    if (isSubjectAdminOnly && !isAdmin) {
                        null
                    } else {
                        entity.copy(read = readAnnPrefs.contains(entity.id))
                    }
                } else {
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
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
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

            if (item.originalReply == "[ANNOUNCEMENT]") {
                val cleanSubject = item.originalSubject.replace("🛡️ [Admin Only] ", "").replace("📣 ", "")
                val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
                val colorTeam = if (isWhite) "#008000" else "#4ADE80"
                val colorPrimary = ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textPrimaryColor)
                val colorTitle = String.format("#%06X", 0xFFFFFF and colorPrimary)

                val titleHtml = "<font color='$colorTeam'><b>ANNOUNCEMENT</b></font>"
                holder.tvTitle.text = android.text.Html.fromHtml(titleHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                holder.tvQuery.text = item.queryFormatted
            } else {
                holder.tvTitle.text = item.title
                holder.tvTitle.setTextColor(item.statusColor)
                holder.tvQuery.text = item.queryFormatted
            }

            // 🔥 Eliminate smudge glow in Blue Theme explicitly
            if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                holder.tvQuery.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvTitle.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvReply.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvTime.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvResolvedStatus.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
            }

            if (item.replyFormatted != null) {
                holder.tvReply.visibility = View.VISIBLE
                holder.tvReply.text = item.replyFormatted
            } else {
                holder.tvReply.visibility = View.GONE
            }

            // Collect all attachment URLs: merge imageUrls list + legacy single imageUrl
            val allUrls = item.imageUrls.toMutableList()
            if (item.imageUrl != null && !allUrls.contains(item.imageUrl)) allUrls.add(0, item.imageUrl)

            if (allUrls.isNotEmpty()) {
                holder.tvAttachmentLabel.visibility = View.VISIBLE
                holder.layoutAttachments.visibility = View.VISIBLE
                holder.layoutAttachments.removeAllViews()

                val density = holder.itemView.context.resources.displayMetrics.density
                // Card size: 110dp × 160dp reduced by 15% → 94dp × 136dp
                val cardW = (94 * density).toInt()
                val cardH = (136 * density).toInt()
                val gap = (8 * density).toInt()
                val rowGap = (8 * density).toInt()

                fun makeCard(url: String): androidx.cardview.widget.CardView {
                    val card = androidx.cardview.widget.CardView(holder.itemView.context).apply {
                        radius = (8 * density)
                        cardElevation = 0f
                        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                        tag = "attachment_card"  // ← exclude from swipe listener
                        layoutParams = android.widget.LinearLayout.LayoutParams(cardW, cardH).apply {
                            setMargins(0, 0, gap, 0)
                        }
                    }
                    val imgView = ImageView(holder.itemView.context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    com.bumptech.glide.Glide.with(holder.itemView.context).load(url).into(imgView)
                    card.addView(imgView)
                    card.setOnClickListener {
                        val dialog = android.app.Dialog(this@NotificationActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        val container = android.widget.FrameLayout(this@NotificationActivity)
                        container.layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val fullImg = com.github.chrisbanes.photoview.PhotoView(this@NotificationActivity)
                        fullImg.layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        fullImg.scaleType = ImageView.ScaleType.FIT_CENTER
                        com.bumptech.glide.Glide.with(this@NotificationActivity).load(url).into(fullImg)
                        val closeBtn = ImageButton(this@NotificationActivity)
                        val btnParams = android.widget.FrameLayout.LayoutParams(
                            (56 * resources.displayMetrics.density).toInt(),
                            (56 * resources.displayMetrics.density).toInt()
                        )
                        btnParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                        btnParams.topMargin = (24 * resources.displayMetrics.density).toInt()
                        btnParams.marginEnd = (24 * resources.displayMetrics.density).toInt()
                        closeBtn.layoutParams = btnParams
                        val glassBg = android.graphics.drawable.GradientDrawable()
                        glassBg.shape = android.graphics.drawable.GradientDrawable.OVAL
                        glassBg.setColor(android.graphics.Color.parseColor("#66000000"))
                        glassBg.setStroke(3, android.graphics.Color.parseColor("#80FFFFFF"))
                        closeBtn.background = glassBg
                        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        closeBtn.setColorFilter(android.graphics.Color.RED)
                        closeBtn.scaleType = ImageView.ScaleType.FIT_CENTER
                        val p = (14 * resources.displayMetrics.density).toInt()
                        closeBtn.setPadding(p, p, p, p)
                        closeBtn.setOnClickListener { dialog.dismiss() }
                        container.addView(fullImg)
                        container.addView(closeBtn)
                        dialog.setContentView(container)
                        dialog.show()
                    }
                    return card
                }

                // Build 2-per-row grid
                val chunks = allUrls.chunked(2)
                for ((rowIdx, chunk) in chunks.withIndex()) {
                    val row = android.widget.LinearLayout(holder.itemView.context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { if (rowIdx > 0) topMargin = rowGap }
                    }
                    for (url in chunk) row.addView(makeCard(url))
                    holder.layoutAttachments.addView(row)
                }
            } else {
                holder.tvAttachmentLabel.visibility = View.GONE
                holder.layoutAttachments.visibility = View.GONE
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
                // Reply box only shows if there's a response to reply TO
                holder.layoutReplyBox.visibility = if (item.isPending) View.GONE else View.VISIBLE
            }

            holder.btnSendReply.setOnClickListener {
                val replyText = holder.edtReply.text.toString().trim()
                if (replyText.isEmpty()) return@setOnClickListener

                holder.btnSendReply.isEnabled = false
                submitUserReply(item, replyText)
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
                        // If it's the root item, claim the touch stream so we get MOVE events
                        // If it's a child button/input, return false to let it handle its own click stream
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
            if (item.isUnread && !item.isPending) {
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

        private fun submitUserReply(model: NotificationModel, replyText: String) {
            val user = FirebaseAuth.getInstance().currentUser ?: return
            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val userName = prefs.getString("user_name", "User") ?: "User"
            
            // 🔥 PRESERVE HISTORY: keep original question as-is, only label new follow-up replies
            val lastTeamReply = model.originalReply
            val currentHistory = model.originalQuery
            
            val updatedQuery = if (lastTeamReply.isNotEmpty() && lastTeamReply != "Waiting for reply...") {
                "$currentHistory\n\nTeam Cashdash: $lastTeamReply\n\n$userName: $replyText"
            } else {
                "$currentHistory\n\n$userName: $replyText"
            }
            
            // 🔥 offline-first queueing: we set needs_admin_email to true so the cloud function catches it when internet returns
            val updateData = hashMapOf(
                "query" to updatedQuery,
                "reply" to "Waiting for reply...",
                "status" to "pending",
                "timestamp" to timestamp,
                "read" to true,
                "needs_admin_email" to true,
                "is_reply" to true
            )

            val email = user.email ?: return
            db.collection("users").document(email).collection("notifications").document(model.id)
                .update(updateData as Map<String, Any>)
                .addOnSuccessListener {
                    // 🚀 IMMEDIATE SUCCESS FEEDBACK
                    ToastHelper.showToast(this@NotificationActivity, "Reply sent!")
                    loadNotifications()

                    // 🚀 ASYNC DIRECT WEBHOOK INJECTION:
                    // Direct fire to the project's Cloud Run endpoint to avoid Firestore trigger delays.
                    triggerImmediateWebhook(
                        uid = user.uid,
                        id = model.id,
                        name = userName,
                        email = email,
                        subject = model.originalSubject,
                        updatedQuery = updatedQuery,
                        originalQuery = model.originalQuery,
                        teamReply = model.originalReply,
                        userFollowup = replyText,
                        timestamp = timestamp
                    )
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this@NotificationActivity, "Failed to send reply")
                }
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
            // Skip attachment cards and their children — they need their own click listeners
            if (view.tag == "attachment_card") return
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
        }
    }
}
