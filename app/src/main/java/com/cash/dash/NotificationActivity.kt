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

class NotificationActivity : ThemedActivity() {

    private var allNotifications = listOf<NotificationModel>()
    private var filteredNotifications = listOf<NotificationModel>()
    private var currentFilter = "all" // "all", "responded", "pending"
    private lateinit var adapter: NotificationAdapter
    private var notificationListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onDestroy() {
        super.onDestroy()
        notificationListener?.remove()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)


        setupRecyclerView()
        setupFilters()
        
        markAllAsRead()
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
        db.collection("users").document(email).collection("notifications")
            .whereEqualTo("read", false)
            .get()
            .addOnSuccessListener { docs ->
                if (!docs.isEmpty) {
                    val batch = db.batch()
                    for (doc in docs) batch.update(doc.reference, "read", true)
                    batch.commit()
                }
            }
    }

    private fun loadNotifications() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        val db = FirebaseFirestore.getInstance()
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)

        loadFromCacheAndRender()

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
                    if (!prefs.getBoolean("migrated_notifications_uid", false)) {
                        // 🔄 LEGACY MIGRATION: Check if there are old notifications under UID and copy them over
                        db.collection("users").document(user.uid).collection("notifications")
                            .get()
                            .addOnSuccessListener { legacyDocs ->
                                if (!legacyDocs.isEmpty) {
                                    val batch = db.batch()
                                    for (legacyDoc in legacyDocs) {
                                        val newRef = db.collection("users").document(email).collection("notifications").document(legacyDoc.id)
                                        batch.set(newRef, legacyDoc.data)
                                        batch.delete(legacyDoc.reference) // Permanently remove old ones to prevent resurrection
                                    }
                                    batch.commit().addOnSuccessListener {
                                        prefs.edit().putBoolean("migrated_notifications_uid", true).apply()
                                        // The addSnapshotListener will automatically trigger when the batch commit pushes the new docs
                                    }
                                } else {
                                    prefs.edit().putBoolean("migrated_notifications_uid", true).apply()
                                    tvEmpty.text = "No notifications yet.\n\nWe'll notify you when your support\nqueries are resolved!"
                                    tvEmpty.visibility = View.VISIBLE
                                    findViewById<LinearLayout>(R.id.filterBar).visibility = View.VISIBLE
                                    rv.visibility = View.GONE
                                    allNotifications = emptyList()
                                    cacheNotifications(emptyList())
                                }
                            }
                        return@addSnapshotListener
                    } else {
                        tvEmpty.text = "No notifications yet.\n\nWe'll notify you when your support\nqueries are resolved!"
                        tvEmpty.visibility = View.VISIBLE
                        findViewById<LinearLayout>(R.id.filterBar).visibility = View.VISIBLE
                        rv.visibility = View.GONE
                        allNotifications = emptyList()
                        cacheNotifications(emptyList())
                        return@addSnapshotListener
                    }
                }

                // 1. Silent cleanup for duplicates (improved)
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

                // 2. Map to performant models
                val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                val now = System.currentTimeMillis()
                                val fortyEightHours = 48 * 60 * 60 * 1000L // 48 hours for production

                allNotifications = rawDocs.filter { d -> !toDelete.any { it.id == d.id } }.map { doc ->
                    val query = doc.getString("query") ?: "No query"
                    val reply = doc.getString("reply") ?: "Waiting for reply..."
                    val subject = doc.getString("subject") ?: "General Help"
                    val ts = doc.getLong("timestamp") ?: 0L
                    val status = doc.getString("status") ?: (if (reply == "Waiting for reply...") "pending" else "responded")
                    
                    var isResolved = status == "resolved" || 
                                     reply.contains("[RESOLVED]", ignoreCase = true) || 
                                     reply.contains("[DONE]", ignoreCase = true)
                    
                    // Auto-resolve check (Trigger only after team responds if user is silent for 48h)
                    if (!isResolved && status == "responded" && (now - ts) > fortyEightHours) {
                        isResolved = true
                        db.collection("users").document(email).collection("notifications").document(doc.id).update("status", "resolved")
                    }

                    val isPending = (reply == "Waiting for reply...")
                    
                    val queryTitle = when {
                        isResolved -> "Query resolved"
                        isPending -> "Waiting for response"
                        else -> "Query responded"
                    }
                    val color = Color.parseColor(when {
                        isResolved -> "#606880"
                        isPending -> "#FFD93D"
                        else -> "#4ADE80"
                    })

                    val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    val userName = prefs.getString("user_name", "User") ?: "User"

                    // Clean up extra spaces to make it even and premium
                    // --- THEME AWARE HTML COLORING ---
                    val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
                    val colorUser = if (isWhite) "#0047AB" else "#B0C8FF"
                    val colorTeam = if (isWhite) "#008000" else "#4ADE80"
                    val colorContent = if (isWhite) "#333333" else "#E0EBF5"

                    val displayQuery = query
                        .replace("User Reply \\(\\d+\\):".toRegex(), "<font color='$colorUser'><b>$userName:</b></font>")
                        .replace("User:".toRegex(), "<font color='$colorUser'><b>$userName:</b></font>")
                        .replace("$userName:".toRegex(), "<font color='$colorUser'><b>$userName:</b></font>")
                        .replace("Team Cashdash:".toRegex(), "<font color='$colorTeam'><b>Team Cashdash:</b></font>")
                        .replace("\n", "<br>")

                    NotificationModel(
                        id = doc.id,
                        queryFormatted = android.text.Html.fromHtml("<b>Subject:</b> $subject<br><b>Question:</b> $displayQuery", android.text.Html.FROM_HTML_MODE_LEGACY),
                        replyFormatted = if (isPending) null else android.text.Html.fromHtml("<font color='$colorTeam'><b>Response</b></font><br><font color='$colorContent'>${reply.replace("\n", "<br>")}</font>", android.text.Html.FROM_HTML_MODE_LEGACY),
                        timestamp = ts,
                        title = queryTitle,
                        timeFormatted = if (ts > 0) sdf.format(Date(ts)) else "",
                        statusColor = color,
                        isPending = isPending,
                        isUnread = doc.getBoolean("read") == false,
                        isResolved = isResolved,
                        originalSubject = subject,
                        originalQuery = query,
                        originalReply = reply
                    )
                }

                val keepers = rawDocs.filter { d -> !toDelete.any { it.id == d.id } }
                cacheNotifications(keepers)
                applyFilter()
            }
    }

    private fun cacheNotifications(docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        try {
            val jsonArray = org.json.JSONArray()
            for (doc in docs) {
                val obj = org.json.JSONObject()
                obj.put("id", doc.id)
                obj.put("query", doc.getString("query") ?: "")
                obj.put("reply", doc.getString("reply") ?: "")
                obj.put("subject", doc.getString("subject") ?: "")
                obj.put("timestamp", doc.getLong("timestamp") ?: 0L)
                obj.put("status", doc.getString("status") ?: "")
                obj.put("read", doc.getBoolean("read") ?: true)
                jsonArray.put(obj)
            }
            getSharedPreferences("NotificationCache", MODE_PRIVATE).edit()
                .putString("cache_data", jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            // e.printStackTrace()
        }
    }

    private fun loadFromCacheAndRender() {
        val prefs = getSharedPreferences("NotificationCache", MODE_PRIVATE)
        val jsonStr = prefs.getString("cache_data", null)
        if (jsonStr == null) {
            val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
            val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
            tvEmpty.text = "Loading notifications..."
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
            findViewById<LinearLayout>(R.id.filterBar).visibility = View.GONE
            return
        }
        try {
            val array = org.json.JSONArray(jsonStr)
            val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            val now = System.currentTimeMillis()
            val fortyEightHours = 48 * 60 * 60 * 1000L // 48 hours for production

            val userPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val userName = userPrefs.getString("user_name", "User") ?: "User"

            val list = mutableListOf<NotificationModel>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val query = obj.optString("query", "No query")
                val reply = obj.optString("reply", "Waiting for reply...")
                val subject = obj.optString("subject", "General Help")
                val ts = obj.optLong("timestamp", 0L)
                val status = obj.optString("status", if (reply == "Waiting for reply...") "pending" else "responded")
                val read = obj.optBoolean("read", true)

                var isResolved = status == "resolved" || 
                                 reply.contains("[RESOLVED]", ignoreCase = true) || 
                                 reply.contains("[DONE]", ignoreCase = true)
                
                if (!isResolved && status == "responded" && (now - ts) > fortyEightHours) {
                    isResolved = true
                }

                val isPending = (reply == "Waiting for reply...")
                val queryTitle = when {
                    isResolved -> "Query resolved"
                    isPending -> "Waiting for response"
                    else -> "Query responded"
                }
                val color = Color.parseColor(when {
                    isResolved -> "#606880"
                    isPending -> "#FFD93D"
                    else -> "#4ADE80"
                })

                // --- THEME AWARE HTML COLORING ---
                val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
                val colorUser = if (isWhite) "#0047AB" else "#B0C8FF"
                val colorTeam = if (isWhite) "#008000" else "#4ADE80"
                val colorContent = if (isWhite) "#333333" else "#E0EBF5"

                val displayQuery = query
                    .replace("User Reply \\(\\d+\\):".toRegex(), "<font color='$colorUser'><b>$userName:</b></font>")
                    .replace("User:".toRegex(), "<font color='$colorUser'><b>$userName:</b></font>")
                    .replace("Team Cashdash:".toRegex(), "<font color='$colorTeam'><b>Team Cashdash:</b></font>")
                    .replace("\n", "<br>")

                list.add(NotificationModel(
                    id = id,
                    queryFormatted = android.text.Html.fromHtml("<b>Subject:</b> $subject<br><b>Question:</b> $displayQuery", android.text.Html.FROM_HTML_MODE_LEGACY),
                    replyFormatted = if (isPending) null else android.text.Html.fromHtml("<font color='$colorTeam'><b>Response</b></font><br><font color='$colorContent'>${reply.replace("\n", "<br>")}</font>", android.text.Html.FROM_HTML_MODE_LEGACY),
                    timestamp = ts,
                    title = queryTitle,
                    timeFormatted = if (ts > 0) sdf.format(Date(ts)) else "",
                    statusColor = color,
                    isPending = isPending,
                    isUnread = !read,
                    isResolved = isResolved,
                    originalSubject = subject,
                    originalQuery = query,
                    originalReply = reply
                ))
            }
            allNotifications = list
            applyFilter()
        } catch (e: Exception) {
            // e.printStackTrace()
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
                
                // Force sync the offline storage JSON instantly
                val prefs = getSharedPreferences("NotificationCache", MODE_PRIVATE)
                val jsonStr = prefs.getString("cache_data", null)
                if (jsonStr != null) {
                    try {
                        val arr = org.json.JSONArray(jsonStr)
                        val newArr = org.json.JSONArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            if (obj.getString("id") != model.id) {
                                newArr.put(obj)
                            }
                        }
                        prefs.edit().putString("cache_data", newArr.toString()).apply()
                    } catch (e: Exception) {}
                }

                if (allNotifications.isEmpty()) {
                    cacheNotifications(emptyList()) // Ensures the empty state sticks
                }
                applyFilter() // Redraws RecyclerView instantaneously
                
                // Process the deletion with Firestore sequentially
                FirebaseFirestore.getInstance().collection("users").document(email)
                    .collection("notifications").document(model.id).delete()
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
            holder.tvTitle.text = item.title
            holder.tvTitle.setTextColor(item.statusColor)
            holder.viewAccentBar.setBackgroundColor(item.statusColor)
            holder.tvQuery.text = item.queryFormatted
            holder.tvTime.text = item.timeFormatted

            if (item.replyFormatted != null) {
                holder.tvReply.visibility = View.VISIBLE
                holder.tvReply.text = item.replyFormatted
            } else {
                holder.tvReply.visibility = View.GONE
            }

            // Status & Action Visibility Logic
            if (item.isResolved) {
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
                    // Removed HTTP webhook trigger - Firestore handles it silently now
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this@NotificationActivity, "Failed to send reply")
                }
        }



        private fun applySwipeRecursively(view: View, listener: View.OnTouchListener) {
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
        }
    }
}
