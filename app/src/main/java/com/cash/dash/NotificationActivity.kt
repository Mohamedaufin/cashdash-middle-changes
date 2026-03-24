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

class NotificationActivity : AppCompatActivity() {

    private var allNotifications = listOf<NotificationModel>()
    private var filteredNotifications = listOf<NotificationModel>()
    private var currentFilter = "all" // "all", "responded", "pending"
    private lateinit var adapter: NotificationAdapter
    private var emailListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var uidListener: com.google.firebase.firestore.ListenerRegistration? = null
    
    // Concurrent data storage to handle dual-stream updates
    private var emailDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
    private var uidDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

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

    override fun onDestroy() {
        emailListener?.remove()
        uidListener?.remove()
        super.onDestroy()
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
        val uid = user.uid
        val db = FirebaseFirestore.getInstance()

        // 1. Listen to New (Email) Path
        emailListener?.remove()
        emailListener = db.collection("users").document(email).collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { docs, e ->
                if (e == null && docs != null) {
                    emailDocs = docs.documents.toMutableList()
                    mergeAndProcessNotifications()
                }
            }

        // 2. Listen to Legacy (UID) Path
        uidListener?.remove()
        uidListener = db.collection("users").document(uid).collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { docs, e ->
                if (e == null && docs != null) {
                    uidDocs = docs.documents.toMutableList()
                    mergeAndProcessNotifications()
                }
            }
    }

    private fun mergeAndProcessNotifications() {
        val db = FirebaseFirestore.getInstance()
        val email = FirebaseAuth.getInstance().currentUser?.email ?: return
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)

        // Combine all raw docs
        val allRawDocs = (emailDocs + uidDocs)
        if (allRawDocs.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
            allNotifications = emptyList()
            applyFilter()
            return
        }

        // 1. Deduplicate across files based on (subject, query) or ID
        // Priority: Keeper is the one with a non-default reply, OR the newest one.
        val grouped = allRawDocs.groupBy { "${it.getString("subject")?.trim()}|${it.getString("query")?.trim()}" }
        val finalDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

        for (group in grouped.values) {
            val keeper = group.find { (it.getString("reply") ?: "Waiting for reply...") != "Waiting for reply..." }
                ?: group.maxByOrNull { it.getLong("timestamp") ?: 0L } ?: group.first()
            
            finalDocs.add(keeper)
        }

        // 2. Map to performant UI models
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val now = System.currentTimeMillis()
        val fortyEightHours = 48 * 60 * 60 * 1000L

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val userName = prefs.getString("user_name", "User") ?: "User"

        allNotifications = finalDocs.map { doc ->
            val query = doc.getString("query") ?: "No query"
            val reply = doc.getString("reply") ?: "Waiting for reply..."
            val subject = doc.getString("subject") ?: "General Help"
            val ts = doc.getLong("timestamp") ?: 0L
            val status = doc.getString("status") ?: (if (reply == "Waiting for reply...") "pending" else "responded")
            
            var isResolved = status == "resolved" || 
                             reply.contains("[RESOLVED]", ignoreCase = true) || 
                             reply.contains("[DONE]", ignoreCase = true)
            
            // Auto-resolve check (UI-only during listener to prevent Firestore loops)
            if (!isResolved && status == "responded" && (now - ts) > fortyEightHours) {
                isResolved = true
            }

            val isPending = (reply == "Waiting for reply...")
            
            val queryTitle = when {
                isResolved -> "Query resolved"
                isPending && query.contains("$userName:") -> "Replied (Waiting for Team)"
                isPending -> "Query Sent"
                else -> "Team Responded"
            }
            val color = Color.parseColor(when {
                isResolved -> "#606880"
                isPending -> "#FFD93D"
                else -> "#4ADE80"
            })

            val displayQuery = query
                .replace("User Reply \\(\\d+\\):".toRegex(), "<font color='#B0C8FF'><b>$userName:</b></font>")
                .replace("User:".toRegex(), "<font color='#B0C8FF'><b>$userName:</b></font>")
                .replace("Team Cashdash:".toRegex(), "<font color='#4ADE80'><b>Team Cashdash:</b></font>")
                .replace("\n", "<br>")

            NotificationModel(
                id = doc.id,
                queryFormatted = android.text.Html.fromHtml("<b>Subject:</b> $subject<br><b>Question:</b> $displayQuery", android.text.Html.FROM_HTML_MODE_LEGACY),
                replyFormatted = if (isPending) null else android.text.Html.fromHtml("<font color='#4ADE80'><b>Response</b></font><br><font color='#E0EBF5'>${reply.replace("\n", "<br>")}</font>", android.text.Html.FROM_HTML_MODE_LEGACY),
                timestamp = ts,
                title = queryTitle,
                timeFormatted = if (ts > 0) sdf.format(Date(ts)) else "",
                statusColor = color,
                isPending = isPending,
                isUnread = doc.getBoolean("read") == false,
                isResolved = isResolved,
                originalSubject = subject,
                originalQuery = query,
                originalReply = reply,
                refPath = doc.reference.path
            )
        }.sortedByDescending { it.timestamp }

        applyFilter()
    }

    private fun applyFilter() {
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        val filterBar = findViewById<LinearLayout>(R.id.filterBar)

        if (allNotifications.isEmpty()) {
            filterBar.visibility = View.GONE
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            setBackgroundResource(R.drawable.bg_transaction)
        }
        
        TextView(this).apply {
            text = "Delete query?"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 20)
            box.addView(this)
        }

        TextView(this).apply {
            text = "Remove this query from your history?"
            textSize = 16f
            setTextColor(Color.parseColor("#A0A0A0"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 50)
            box.addView(this)
        }

        val btnContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(0, 0, 15, 0) }
            setOnClickListener { 
                adapter.notifyDataSetChanged() // Reset swipe state
                dialog.dismiss() 
            }
            btnContainer.addView(this)
        }

        Button(this).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(15, 0, 0, 0) }
            setOnClickListener {
                val user = FirebaseAuth.getInstance().currentUser ?: return@setOnClickListener
                FirebaseFirestore.getInstance().document(model.refPath).delete()
                    .addOnSuccessListener {
                        ToastHelper.showToast(this@NotificationActivity, "Query deleted")
                        loadNotifications()
                        dialog.dismiss()
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
            
            // 🔥 PRESERVE HISTORY: Append previous team response to query field before adding new user follow-up
            val lastTeamReply = model.originalReply
            val currentHistory = model.originalQuery
            
            val updatedQuery = if (lastTeamReply.isNotEmpty() && lastTeamReply != "Waiting for reply...") {
                "$currentHistory\n\nTeam Cashdash:\n$lastTeamReply\n\n$userName:\n$replyText"
            } else {
                "$currentHistory\n\n$userName:\n$replyText"
            }
            
            val updateData = hashMapOf(
                "query" to updatedQuery,
                "reply" to "Waiting for reply...",
                "status" to "pending",
                "timestamp" to timestamp,
                "read" to true
            )

            db.document(model.refPath)
                .update(updateData as Map<String, Any>)
                .addOnSuccessListener {
                    triggerPipedreamForReply(user.uid, model.originalSubject, model.originalQuery, model.originalReply, timestamp, replyText, model.id)
                    ToastHelper.showToast(this@NotificationActivity, "Reply sent!")
                    loadNotifications()
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this@NotificationActivity, "Failed to send reply")
                }
        }

        private fun triggerPipedreamForReply(uid: String, subject: String, originalQuery: String, teamReply: String, timestamp: Long, userFollowup: String, id: String) {
            val pipedreamUrl = "https://us-central1-cashdash-8cd8b.cloudfunctions.net/cashdashWebhook"
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val name = prefs.getString("user_name", "User") ?: "User"
            val email = prefs.getString("user_email", "No Email") ?: "No Email"
            
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            val dateStr = sdf.format(java.util.Date(timestamp))

            // Keep query as raw sequence for the backend to format
            val payload = """
                {
                  "uid": "$uid",
                  "id": "$id",
                  "name": "${name.replace("\"", "\\\"")}",
                  "email": "${email.replace("\"", "\\\"")}",
                  "time": "$dateStr",
                  "subject": "${subject.replace("\"", "\\\"")}",
                  "originalQuery": "${originalQuery.replace("\n", "\\n").replace("\"", "\\\"")}",
                  "teamReply": "${teamReply.replace("\n", "\\n").replace("\"", "\\\"")}",
                  "userFollowup": "${userFollowup.replace("\n", "\\n").replace("\"", "\\\"")}",
                  "timestamp": $timestamp,
                  "is_reply": true
                }
            """.trimIndent()
            

            Thread {
                try {
                    val url = java.net.URL(pipedreamUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toByteArray(charset("utf-8"))) }
                    conn.responseCode
                } catch (e: Exception) {
                    android.util.Log.e("NotificationActivity", "Webhook error", e)
                }
            }.start()
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
