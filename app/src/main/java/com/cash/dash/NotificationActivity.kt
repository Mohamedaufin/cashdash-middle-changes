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
    
    // Maintain state of typed replies across theme changes / rotation
    private var replyDrafts = HashMap<String, String>()

    data class MatchPosition(val itemId: String, val localIndex: Int)
    private var searchQuery: String = ""
    private var searchMatches = mutableListOf<MatchPosition>()
    private var currentMatchIndex: Int = -1

    private val replyPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selectedUri = result.data?.data
            val id = activePickerQueryId
            if (id == null) return@registerForActivityResult

            if (selectedUri != null) {
                val item = allNotifications.find { it.id == id }
                val uploadedCount = getRecentUserAttachmentCount(item)
                val list = selectedReplyImages.getOrPut(id) { mutableListOf() }
                if (list.size + uploadedCount < 4) {
                    list.add(selectedUri)
                    uploadReplyImage(id, selectedUri)
                    adapter.notifyDataSetChanged()
                }
            } else {
                val bitmap = result.data?.extras?.get("data") as? android.graphics.Bitmap
                if (bitmap != null) {
                    try {
                        val file = java.io.File(externalCacheDir, "reply_img_${System.currentTimeMillis()}.jpg")
                        val out = java.io.FileOutputStream(file)
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                        out.flush()
                        out.close()
                        val uri = Uri.fromFile(file)
                        val item = allNotifications.find { it.id == id }
                        val uploadedCount = getRecentUserAttachmentCount(item)
                        val list = selectedReplyImages.getOrPut(id) { mutableListOf() }
                        if (list.size + uploadedCount < 4) {
                            list.add(uri)
                            uploadReplyImage(id, uri)
                            adapter.notifyDataSetChanged()
                        }
                    } catch (e: Exception) {
                        ToastHelper.showToast(this, "Failed to save camera image: ${e.message}")
                    }
                }
            }
        }
    }

    private fun getRecentUserAttachmentCount(item: NotificationModel?): Int {
        if (item == null) return 0
        if (item.originalReply.isNotEmpty() && item.originalReply != "Waiting for reply..." && item.originalReply != "[ANNOUNCEMENT]") {
            return 0
        }
        val query = item.originalQuery
        val parts = query.split("(?i)Team CashDash:".toRegex())
        val lastPart = parts.last()
        val regex = "\\[Attachment: .*?\\]".toRegex()
        return regex.findAll(lastPart).count()
    }

    private fun updateViewHolderUploadProgress(holder: NotificationAdapter.ViewHolder, queryId: String) {
        holder.layoutUploadProgress.visibility = android.view.View.GONE
        holder.progressUpload.progress = 0
        
        val progressMap = replyUploadProgress[queryId] ?: emptyMap()
        val uris = selectedReplyImages[queryId] ?: emptyList()
        
        for (uri in uris) {
            val tvProgress = holder.layoutReplyPreviews.findViewWithTag<android.widget.TextView>("progress_${queryId}_$uri")
            val progress = progressMap[uri]
            if (tvProgress != null) {
                if (progress != null && progress < 100) {
                    tvProgress.visibility = android.view.View.VISIBLE
                    tvProgress.text = "$progress%"
                } else {
                    tvProgress.visibility = android.view.View.GONE
                }
            }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentFilter", currentFilter)
        outState.putString("activePickerQueryId", activePickerQueryId)
        outState.putSerializable("replyDrafts", replyDrafts)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentFilter = savedInstanceState.getString("currentFilter", "all")
        activePickerQueryId = savedInstanceState.getString("activePickerQueryId", activePickerQueryId)
        
        val drafts = savedInstanceState.getSerializable("replyDrafts") as? HashMap<String, String>
        if (drafts != null) {
            replyDrafts.putAll(drafts)
        }
        
        updateChipAppearance()
        applyFilter()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        val rootLayout = findViewById<View>(R.id.rootNotificationLayout)
        
        setupSearch()

        // Sync Firestore → SharedPreferences BEFORE capturing initiallyReadAnnouncements.
        // This survives uninstall/reinstall and re-login because read state is stored
        // in the user's Firestore document, not just locally.
        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email
        val readPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)

        if (email != null) {
            FirebaseFirestore.getInstance()
                .collection("users").document(email)
                .get()
                .addOnSuccessListener { doc ->
                    @Suppress("UNCHECKED_CAST")
                    val cloudReadIds = doc.get("readAnnouncementIds") as? List<String> ?: emptyList()
                    if (cloudReadIds.isNotEmpty()) {
                        val editor = readPrefs.edit()
                        for (id in cloudReadIds) editor.putBoolean(id, true)
                        editor.apply()
                    }
                    initiallyReadAnnouncements.addAll(readPrefs.all.keys)
                    setupRecyclerView()
                    setupFilters()
                    loadNotifications()
                }
                .addOnFailureListener {
                    // Firestore failed — fall back to whatever is in SharedPreferences
                    initiallyReadAnnouncements.addAll(readPrefs.all.keys)
                    setupRecyclerView()
                    setupFilters()
                    loadNotifications()
                }
        } else {
            initiallyReadAnnouncements.addAll(readPrefs.all.keys)
            setupRecyclerView()
            setupFilters()
            loadNotifications()
        }
    }


    private fun setupRecyclerView() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        adapter = NotificationAdapter(mutableListOf(),
            onDelete = { model -> showDeleteConfirmDialog(model) }
        )
        rv.adapter = adapter
    }

    private fun setupSearch() {
        val etSearch = findViewById<EditText>(R.id.etSearch)
        val searchControls = findViewById<View>(R.id.searchControls)
        val tvSearchCount = findViewById<TextView>(R.id.tvSearchCount)
        val btnSearchPrev = findViewById<View>(R.id.btnSearchPrev)
        val btnSearchNext = findViewById<View>(R.id.btnSearchNext)
        val btnExitSearch = findViewById<View>(R.id.btnExitSearch)

        btnExitSearch.setOnClickListener {
            etSearch.text.clear()
            etSearch.clearFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString() ?: ""
                performSearch()
            }
        })

        btnSearchPrev.setOnClickListener {
            if (searchMatches.isNotEmpty()) {
                currentMatchIndex = if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
                updateSearchUIAndScroll()
                adapter.notifyDataSetChanged()
            }
        }

        btnSearchNext.setOnClickListener {
            if (searchMatches.isNotEmpty()) {
                currentMatchIndex = if (currentMatchIndex + 1 >= searchMatches.size) 0 else currentMatchIndex + 1
                updateSearchUIAndScroll()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun performSearch() {
        searchMatches.clear()
        val searchControls = findViewById<View>(R.id.searchControls)
        val tvSearchCount = findViewById<TextView>(R.id.tvSearchCount)

        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase()
            for (item in filteredNotifications) {
                val textParts = mutableListOf<String>()
                textParts.add(item.title)
                
                val attachmentRegex = "\\[Attachment: (.*?)\\]".toRegex()
                
                if (item.originalReply == "[ANNOUNCEMENT]") {
                    val cleanSubject = item.originalSubject.replace("🛡️ [Admin Only] ", "").replace("📣 ", "")
                    val html = "<b>${obscureText("ANNOUNCEMENT")}</b>"
                    textParts.add(android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                    
                    val bodyHtml = "<b>$cleanSubject</b><br>${item.originalQuery}"
                    textParts.add(android.text.Html.fromHtml(bodyHtml, android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                } else {
                    val rawBlocks = item.originalQuery.split("\n\n").filter { it.isNotBlank() }
                    for ((idx, block) in rawBlocks.withIndex()) {
                        val cleanText = block.replace(attachmentRegex, "").trim()
                        if (idx == 0) {
                            val formattedHtml = "<b>${obscureText("Subject:")}</b> ${item.originalSubject}<br><b>${obscureText("Question:")}</b> ${cleanText.replace("\n", "<br>")}"
                            textParts.add(android.text.Html.fromHtml(formattedHtml, android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                        } else {
                            val teamTagPattern = "(?i)^Team Cashdash:".toRegex()
                            val formattedSpanned = when {
                                teamTagPattern.containsMatchIn(cleanText) -> {
                                    val content = cleanText.replaceFirst(teamTagPattern, "").trim()
                                    val html = "<b>${obscureText("Team CashDash:")}</b> ${content.replace("\n", "<br>")}"
                                    android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                                }
                                cleanText.startsWith("User:") -> {
                                    val content = cleanText.removePrefix("User:").trim()
                                    val html = "<b>${obscureText("User:")}</b> ${content.replace("\n", "<br>")}"
                                    android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                                }
                                else -> {
                                    val firstColon = cleanText.indexOf(":")
                                    if (firstColon > 0 && firstColon < 30) {
                                        val potentialName = cleanText.substring(0, firstColon)
                                        val content = cleanText.substring(firstColon + 1).trim()
                                        val html = if (potentialName.equals("Team Cashdash", ignoreCase = true) || potentialName.equals("Team CashDash", ignoreCase = true)) {
                                            "<b>${obscureText("Team CashDash:")}</b> ${content.replace("\n", "<br>")}"
                                        } else {
                                            "<b>${obscureText(potentialName + ":")}</b> ${content.replace("\n", "<br>")}"
                                        }
                                        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                                    } else {
                                        android.text.Html.fromHtml(cleanText.replace("\n", "<br>"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                                    }
                                }
                            }
                            textParts.add(formattedSpanned)
                        }
                    }
                    val originalReply = item.originalReply
                    if (originalReply.isNotEmpty() && originalReply != "Waiting for reply...") {
                        val cleanReply = originalReply.replace(attachmentRegex, "").trim()
                        val replyHtml = "<b>${obscureText("Team CashDash:")}</b> ${cleanReply.replace("\n", "<br>")}"
                        textParts.add(android.text.Html.fromHtml(replyHtml, android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                    }
                }
                
                if (!item.linksJson.isNullOrEmpty()) {
                    try {
                        val arr = org.json.JSONArray(item.linksJson)
                        for (i in 0 until arr.length()) {
                            textParts.add(arr.getJSONObject(i).optString("text", "Link"))
                        }
                    } catch (e: Exception) {}
                }
                
                var count = 0
                for (part in textParts) {
                    val textStr = part.lowercase()
                    var startIndex = textStr.indexOf(query)
                    while (startIndex >= 0) {
                        searchMatches.add(MatchPosition(item.id, count))
                        count++
                        startIndex = textStr.indexOf(query, startIndex + query.length)
                    }
                }
            }
        }

        if (searchQuery.isEmpty()) {
            searchControls.visibility = View.GONE
            currentMatchIndex = -1
        } else if (searchMatches.isEmpty()) {
            searchControls.visibility = View.VISIBLE
            tvSearchCount.text = "0/0"
            currentMatchIndex = -1
            ToastHelper.showToast(this, "No query found")
        } else {
            searchControls.visibility = View.VISIBLE
            if (currentMatchIndex >= searchMatches.size) currentMatchIndex = searchMatches.size - 1
            if (currentMatchIndex < 0) currentMatchIndex = 0
            updateSearchUIAndScroll()
        }
        
        adapter.notifyDataSetChanged()
    }

    private fun updateSearchUIAndScroll() {
        if (searchMatches.isEmpty() || currentMatchIndex < 0) return
        val tvSearchCount = findViewById<TextView>(R.id.tvSearchCount)
        tvSearchCount.text = "${currentMatchIndex + 1}/${searchMatches.size}"
        
        val activeMatch = searchMatches[currentMatchIndex]
        val position = filteredNotifications.indexOfFirst { it.id == activeMatch.itemId }
        if (position >= 0) {
            val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
            (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.scrollToPositionWithOffset(position, 100)
        }
    }

    private fun setupFilters() {
        findViewById<TextView>(R.id.chipAll).setOnClickListener { setFilter("all") }
        findViewById<TextView>(R.id.chipResponded).setOnClickListener { setFilter("responded") }
        findViewById<TextView>(R.id.chipPending).setOnClickListener { setFilter("pending") }
        
        findViewById<View>(R.id.btnDeleteCategory)?.setOnClickListener {
            if (filteredNotifications.isNotEmpty()) {
                showBulkDeleteConfirmation()
            } else {
                ToastHelper.showToast(this, "No notifications to delete")
            }
        }
        
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

        val isWhite = ThemeHelper.isWhiteTheme(this)
        val activeColor = if (isWhite) Color.BLACK else Color.WHITE
        val inactiveColor = Color.parseColor("#606880")

        chipAll.setTextColor(if (currentFilter == "all") activeColor else inactiveColor)
        chipResponded.setTextColor(if (currentFilter == "responded") activeColor else inactiveColor)
        chipPending.setTextColor(if (currentFilter == "pending") activeColor else inactiveColor)

        chipAll.isSelected = (currentFilter == "all")
        chipResponded.isSelected = (currentFilter == "responded")
        chipPending.isSelected = (currentFilter == "pending")
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

        // 2. Mark announcements as read — locally AND in Firestore (survives reinstall/re-login)
        db.collection("announcements")
            .get()
            .addOnSuccessListener { adminDocs ->
                val readPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)
                val editor = readPrefs.edit()
                val readIds = mutableListOf<String>()
                for (doc in adminDocs) {
                    editor.putBoolean(doc.id, true)
                    readIds.add(doc.id)
                }
                editor.apply()

                // Persist to Firestore so it survives uninstall/reinstall and re-login
                if (readIds.isNotEmpty()) {
                    db.collection("users").document(email)
                        .update("readAnnouncementIds", com.google.firebase.firestore.FieldValue.arrayUnion(*readIds.toTypedArray()))
                        .addOnFailureListener {
                            // If doc doesn't have the field yet, set it
                            db.collection("users").document(email)
                                .set(mapOf("readAnnouncementIds" to readIds), com.google.firebase.firestore.SetOptions.merge())
                        }
                }

                // Sync to Room so they persist locally too
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

    private fun obscureText(text: String): String {
        return text.toCharArray().joinToString("\u200B")
    }

    private fun fetchAnnouncementsAndListen() {
        val db = FirebaseFirestore.getInstance()
        db.collection("announcements")
            .get()
            .addOnSuccessListener { adminDocs ->
                val user = FirebaseAuth.getInstance().currentUser
                val email = user?.email?.lowercase() ?: ""
                val registrationTime = user?.metadata?.creationTimestamp ?: 0L
                val isAdmin = AdminManager.isCurrentUserAdmin()

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
                                imageUrls = (doc.get("imageUrls") as? List<*>)?.mapNotNull { it as? String }?.let { org.json.JSONArray(it).toString() },
                                triggerText = doc.getString("triggerText"),
                                triggerUrl = doc.getString("triggerUrl"),
                                linksJson = doc.getString("linksJson")
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
                    val filterBar = findViewById<View>(R.id.filterBar)
                    filterBar.visibility = View.VISIBLE
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
                    val userPrefs = getSharedPreferences("DeletedUserNotifications", MODE_PRIVATE)
                    val finalDocs = rawDocs.filter { d -> !toDelete.any { it.id == d.id } && d.getBoolean("isPush") != true }
                    val entities = finalDocs.mapNotNull { doc ->
                        val readVal = doc.getBoolean("read") ?: true
                        if (!readVal) {
                            initiallyUnreadNotifications.add(doc.id)
                            userPrefs.edit().remove(doc.id).apply() // Revive on response
                        }
                        
                        if (userPrefs.contains(doc.id)) return@mapNotNull null
                        
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
            val userPrefs = getSharedPreferences("DeletedUserNotifications", MODE_PRIVATE)
            val readAnnPrefs = getSharedPreferences("ReadAnnouncements", MODE_PRIVATE)

            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email?.lowercase() ?: ""
            val registrationTime = user?.metadata?.creationTimestamp ?: 0L
            val isAdmin = AdminManager.isCurrentUserAdmin()

            val filteredEntities = entities.filter { !deletedPrefs.contains(it.id) && !userPrefs.contains(it.id) }.mapNotNull { entity ->
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
        val colorPending = if (isWhite) "#CD8500" else "#FFA500"
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
                isAnnouncement -> obscureText("ANNOUNCEMENT")
                isResolved -> obscureText("Query resolved")
                isPending -> obscureText("Waiting for response")
                else -> obscureText("Query responded")
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
                    .replace("Team CashDash:".toRegex(), "<font color='$colorTeam'><b>${obscureText("Team CashDash:")}</b></font>")
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
                    android.text.Html.fromHtml(
                        "<b>Title:</b> $cleanSubject<br><br>${displayQuery}",
                        android.text.Html.FROM_HTML_MODE_LEGACY,
                        { source ->
                            if (source == "ic_external_link") {
                                val d = androidx.core.content.ContextCompat.getDrawable(this@NotificationActivity, R.drawable.ic_external_link)
                                val size = (13 * resources.displayMetrics.density).toInt()
                                d?.setBounds(0, 0, size, size)
                                val linkColorStr = if (ThemeHelper.isWhiteTheme(this@NotificationActivity)) "#0047AB" else "#2196F3"
                                d?.setTint(android.graphics.Color.parseColor(linkColorStr))
                                d
                            } else null
                        },
                        null
                    )
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
                } ?: emptyList(),
                triggerText = entity.triggerText,
                triggerUrl = entity.triggerUrl,
                linksJson = entity.linksJson
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
                        val offsetPx = (12 * resources.displayMetrics.density).toInt()
                        (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                            ?.scrollToPositionWithOffset(firstUnreadIndex, offsetPx)
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
            setBackgroundResource(ThemeHelper.getDrawable(this@NotificationActivity, R.drawable.bg_transaction))
        }

        TextView(this).apply {
            text = "Delete query?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
            box.addView(this)
        }

        TextView(this).apply {
            text = "Remove this query from your history?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textMutedColor))
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
            stateListAnimator = null
            elevation = 0f
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@NotificationActivity,
                android.util.TypedValue().apply {
                    theme.resolveAttribute(R.attr.cardBackground, this, true)
                }.resourceId
            )
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
            stateListAnimator = null
            elevation = 0f
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@NotificationActivity,
                android.util.TypedValue().apply {
                    theme.resolveAttribute(R.attr.cardBackground, this, true)
                }.resourceId
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            minHeight = (54 * density).toInt()
            setOnClickListener {
                val user = FirebaseAuth.getInstance().currentUser ?: return@setOnClickListener
                val email = user.email ?: return@setOnClickListener

                dialog.dismiss()
                ToastHelper.showToast(this@NotificationActivity, "Query deleted")

                // Remove from in-memory list
                val mutList = allNotifications.toMutableList()
                mutList.removeAll { it.id == model.id }
                allNotifications = mutList

                if (allNotifications.isEmpty()) {
                    saveToRoom(emptyList())
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.getDatabase(this@NotificationActivity).notificationDao().deleteById(model.id)
                    }
                }

                applyFilter()

                // Persist deletion
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

    private fun showBulkDeleteConfirmation() {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(ThemeHelper.getDrawable(this@NotificationActivity, R.drawable.bg_transaction))
        }

        TextView(this).apply {
            text = "Delete Queries?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
            box.addView(this)
        }

        TextView(this).apply {
            text = "Do you want to delete all your queries in this category?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textMutedColor))
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
            stateListAnimator = null
            elevation = 0f
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@NotificationActivity,
                android.util.TypedValue().apply {
                    theme.resolveAttribute(R.attr.cardBackground, this, true)
                }.resourceId
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            minHeight = (54 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            btnContainer.addView(this)
        }

        Button(this).apply {
            text = "Delete"
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            setTextColor(ThemeHelper.resolveColorAttr(this@NotificationActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@NotificationActivity,
                android.util.TypedValue().apply {
                    theme.resolveAttribute(R.attr.cardBackground, this, true)
                }.resourceId
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            minHeight = (54 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                executeBulkDelete()
            }
            btnContainer.addView(this)
        }

        box.addView(btnContainer)
        dialog.show()
    }

    private fun executeBulkDelete() {
        val itemsToDelete = filteredNotifications.toList()
        if (itemsToDelete.isEmpty()) return

        val annPrefs = getSharedPreferences("DeletedAnnouncements", MODE_PRIVATE)
        val userPrefs = getSharedPreferences("DeletedUserNotifications", MODE_PRIVATE)
        val annEditor = annPrefs.edit()
        val userEditor = userPrefs.edit()

        for (model in itemsToDelete) {
            if (model.originalReply == "[ANNOUNCEMENT]") {
                annEditor.putBoolean(model.id, true)
            } else {
                userEditor.putBoolean(model.id, true)
            }
        }
        annEditor.apply()
        userEditor.apply()

        val toDeleteIds = itemsToDelete.map { it.id }.toSet()
        val mutList = allNotifications.toMutableList()
        mutList.removeAll { toDeleteIds.contains(it.id) }
        allNotifications = mutList
        applyFilter()

        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content),
            "Notifications cleared",
            5000
        )
        // Fix for snackbar: use solid theme color with rounded corners
        val bgDrawable = android.graphics.drawable.GradientDrawable()
        val snackbarColor = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#1C1C1E")
        bgDrawable.setColor(snackbarColor)
        bgDrawable.cornerRadius = 16f * resources.displayMetrics.density
        snackbar.view.backgroundTintList = null
        snackbar.view.background = bgDrawable
        snackbar.view.elevation = 8f * resources.displayMetrics.density

        // Adjust margins to make it float nicely
        val params = snackbar.view.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (params != null) {
            params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            params.setMargins(16, 0, 16, 16)
            snackbar.view.layoutParams = params
        } else if (snackbar.view.layoutParams is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
            val coordParams = snackbar.view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            coordParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            coordParams.setMargins(16, 0, 16, 16)
            snackbar.view.layoutParams = coordParams
        }

        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor))
        snackbar.setActionTextColor(android.graphics.Color.parseColor("#FF5252"))

        var timer: android.os.CountDownTimer? = null

        snackbar.setAction("Undo (5)") {
            timer?.cancel()
            val annUndoEditor = annPrefs.edit()
            val userUndoEditor = userPrefs.edit()
            for (model in itemsToDelete) {
                if (model.originalReply == "[ANNOUNCEMENT]") {
                    annUndoEditor.remove(model.id)
                } else {
                    userUndoEditor.remove(model.id)
                }
            }
            annUndoEditor.apply()
            userUndoEditor.apply()

            val revertList = allNotifications.toMutableList()
            revertList.addAll(itemsToDelete)
            revertList.sortByDescending { it.timestamp }
            allNotifications = revertList
            applyFilter()
        }

        timer = object : android.os.CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                snackbar.setAction("Undo ($sec)") {
                    timer?.cancel()
                    val annUndoEditor = annPrefs.edit()
                    val userUndoEditor = userPrefs.edit()
                    for (model in itemsToDelete) {
                        if (model.originalReply == "[ANNOUNCEMENT]") {
                            annUndoEditor.remove(model.id)
                        } else {
                            userUndoEditor.remove(model.id)
                        }
                    }
                    annUndoEditor.apply()
                    userUndoEditor.apply()

                    val revertList = allNotifications.toMutableList()
                    revertList.addAll(itemsToDelete)
                    revertList.sortByDescending { it.timestamp }
                    allNotifications = revertList
                    applyFilter()
                }
            }
            override fun onFinish() {}
        }
        timer.start()

        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar?, event: Int) {
                timer?.cancel()
            }
        })
        
        snackbar.show()
    }

    // --- ADAPTER ---
    inner class NotificationAdapter(
        private var items: MutableList<NotificationModel>,
        private val onDelete: (NotificationModel) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

        private fun highlightMatches(holder: ViewHolder, item: NotificationModel) {
            if (searchQuery.isEmpty()) return
            val query = searchQuery.lowercase()
            val textViewsToHighlight = mutableListOf<TextView>(holder.tvTitle)
            
            fun collectTextViews(parent: android.view.ViewGroup) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is TextView) {
                        textViewsToHighlight.add(child)
                    } else if (child is android.view.ViewGroup) {
                        collectTextViews(child)
                    }
                }
            }
            collectTextViews(holder.layoutTimelineContainer)
            
            var localMatchCounter = 0
            for (tv in textViewsToHighlight) {
                if (tv.text.isEmpty()) continue
                val spannable = tv.text as? android.text.Spannable ?: android.text.SpannableString(tv.text)
                val textStr = spannable.toString().lowercase()
                
                // Remove old spans
                val oldSpans = spannable.getSpans(0, spannable.length, android.text.style.BackgroundColorSpan::class.java)
                for (span in oldSpans) spannable.removeSpan(span)
                
                var startIndex = textStr.indexOf(query)
                var highlighted = false
                while (startIndex >= 0) {
                    val isGlobalActive = searchMatches.isNotEmpty() && currentMatchIndex in searchMatches.indices &&
                            searchMatches[currentMatchIndex].itemId == item.id &&
                            searchMatches[currentMatchIndex].localIndex == localMatchCounter
                            
                    val color = if (isGlobalActive) android.graphics.Color.parseColor("#80FF9800") else android.graphics.Color.parseColor("#40FFEB3B")
                    spannable.setSpan(android.text.style.BackgroundColorSpan(color), startIndex, startIndex + query.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    
                    highlighted = true
                    localMatchCounter++
                    startIndex = textStr.indexOf(query, startIndex + query.length)
                }
                if (highlighted) tv.text = spannable
            }
        }

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

            // Show swipe hint only for the very first item
            holder.tvSwipeHintItem.visibility = if (position == 0) View.VISIBLE else View.GONE

            // Reset view state (crucial for Cancel or recycling)
            holder.mainCardContainer.translationX = 0f
            holder.mainCardContainer.alpha = 1f
            holder.viewAccentBar.setBackgroundColor(item.statusColor)
            holder.tvTime.text = item.timeFormatted

            val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
            val colorTeam = if (isWhite) "#008000" else "#4ADE80"
            val colorContent = if (isWhite) "#333333" else "#E0EBF5"
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val userName = prefs.getString("user_name", "User") ?: "User"
            val userFormat = if (isWhite) "<font color='#0047AB'><b>${obscureText(userName + ":")}</b></font>" else "<b>${obscureText(userName + ":")}</b>"

            // Clear dynamic timeline container
            holder.layoutTimelineContainer.removeAllViews()

            // Collect all attachment URLs
            val allUrls = item.imageUrls.toMutableList()
            if (!item.imageUrl.isNullOrEmpty() && !allUrls.contains(item.imageUrl)) allUrls.add(0, item.imageUrl!!)

            val attachmentRegex = "\\[Attachment: (.*?)\\]".toRegex()

            if (item.originalReply == "[ANNOUNCEMENT]") {
                val cleanSubject = item.originalSubject.replace("🛡️ [Admin Only] ", "").replace("📣 ", "")
                val titleHtml = "<font color='$colorTeam'><b>${obscureText("ANNOUNCEMENT")}</b></font>"
                holder.tvTitle.text = android.text.Html.fromHtml(titleHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                holder.tvTitle.setTextColor(item.statusColor)

                // Add announcement body to timeline
                val tv = TextView(holder.itemView.context).apply {
                    setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                    val linkColorStr = if (ThemeHelper.isWhiteTheme(context)) "#0047AB" else "#2196F3"
                    setLinkTextColor(android.graphics.Color.parseColor(linkColorStr))
                    textSize = 14f
                    setLineSpacing(0f, 1.4f)
                    setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                tv.setOnTouchListener { v, event ->
                    val widget = v as TextView
                    val text = widget.text as? android.text.Spannable ?: return@setOnTouchListener false
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        var x = event.x.toInt()
                        var y = event.y.toInt()
                        x -= widget.totalPaddingLeft
                        y -= widget.totalPaddingTop
                        x += widget.scrollX
                        y += widget.scrollY
                        val layout = widget.layout
                        val line = layout.getLineForVertical(y)
                        val off = layout.getOffsetForHorizontal(line, x.toFloat())
                        val links = text.getSpans(off, off, android.text.style.URLSpan::class.java)
                        if (links.isNotEmpty()) {
                            links[0].onClick(widget)
                            return@setOnTouchListener true
                        } else {
                            val startSearch = (off - 4).coerceAtLeast(0)
                            val endSearch = (off + 4).coerceAtMost(text.length)
                            val nearbyLinks = text.getSpans(startSearch, endSearch, android.text.style.URLSpan::class.java)
                            if (nearbyLinks.isNotEmpty()) {
                                nearbyLinks[0].onClick(widget)
                                return@setOnTouchListener true
                            }
                        }
                    }
                    false
                }
                tv.text = item.queryFormatted
                if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                    tv.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                }
                holder.layoutTimelineContainer.addView(tv)

                if (allUrls.isNotEmpty()) {
                    renderAttachments(holder.itemView.context, allUrls, holder.layoutTimelineContainer, item.originalReply == "[ANNOUNCEMENT]")
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
                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    }

                    if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                        tv.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                    }

                    if (idx == 0) {
                        val formattedHtml = "<b>${obscureText("Subject:")}</b> ${item.originalSubject}<br><b>${obscureText("Question:")}</b> ${cleanText.replace("\n", "<br>")}"
                        tv.text = android.text.Html.fromHtml(formattedHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                        holder.layoutTimelineContainer.addView(tv)

                        // For block[0]: show its own inline markers PLUS any legacy attachments
                        val firstBlockUrls = blockUrls + legacyUrls
                        if (firstBlockUrls.isNotEmpty()) {
                            val attachmentLayout = LinearLayout(holder.itemView.context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                            }
                            renderAttachments(holder.itemView.context, firstBlockUrls, attachmentLayout, false)
                            holder.layoutTimelineContainer.addView(attachmentLayout)
                        }
                    } else {
                        val teamTagPattern = "(?i)^Team Cashdash:".toRegex()
                        val formattedSpanned = when {
                            teamTagPattern.containsMatchIn(cleanText) -> {
                                val content = cleanText.replaceFirst(teamTagPattern, "").trim()
                                val html = "<font color='$colorTeam'><b>${obscureText("Team CashDash:")}</b></font> ${content.replace("\n", "<br>")}"
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
                                    val html = if (potentialName.equals("Team Cashdash", ignoreCase = true) || potentialName.equals("Team CashDash", ignoreCase = true)) {
                                        "<font color='$colorTeam'><b>${obscureText("Team CashDash:")}</b></font> ${content.replace("\n", "<br>")}"
                                    } else {
                                        "<font color='#0047AB'><b>${obscureText(potentialName + ":")}</b></font> ${content.replace("\n", "<br>")}"
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
                            renderAttachments(holder.itemView.context, blockUrls, attachmentLayout, false)
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
                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    }
                    if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                        tv.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                    }

                    val replyHtml = "<font color='$colorTeam'><b>Team CashDash:</b></font> ${cleanReply.replace("\n", "<br>")}"
                    tv.text = android.text.Html.fromHtml(replyHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                    holder.layoutTimelineContainer.addView(tv)

                    if (replyUrls.isNotEmpty()) {
                        val attachmentLayout = LinearLayout(holder.itemView.context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                        }
                        renderAttachments(holder.itemView.context, replyUrls, attachmentLayout, false)
                        holder.layoutTimelineContainer.addView(attachmentLayout)
                    }
                }
            }

            // Render additional embedded link chips
            if (!item.linksJson.isNullOrEmpty()) {
                try {
                    val arr = org.json.JSONArray(item.linksJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val linkText = obj.optString("text", "Link")
                        val linkUrl = obj.optString("url", "")
                        if (linkUrl.isEmpty()) continue
                        val safeUrl = if (!linkUrl.startsWith("http://") && !linkUrl.startsWith("https://")) "https://$linkUrl" else linkUrl
                        val tvLink = TextView(holder.itemView.context).apply {
                            val spannable = android.text.SpannableString(linkText)
                            val clickSpan = object : android.text.style.ClickableSpan() {
                                override fun onClick(widget: android.view.View) {
                                    val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(safeUrl))
                                    context.startActivity(browserIntent)
                                    // Track the link click
                                    val userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
                                    if (userEmail != null) {
                                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                            .collection("admin_logs").document(item.id)
                                            .update("ann_clickers", com.google.firebase.firestore.FieldValue.arrayUnion(userEmail))
                                    }
                                }
                                override fun updateDrawState(ds: android.text.TextPaint) {
                                    super.updateDrawState(ds)
                                    ds.color = android.graphics.Color.parseColor("#2196F3")
                                    ds.isUnderlineText = true
                                }
                            }
                            spannable.setSpan(clickSpan, 0, linkText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            text = spannable
                            movementMethod = android.text.method.LinkMovementMethod.getInstance()
                            highlightColor = android.graphics.Color.TRANSPARENT
                            textSize = 14f
                            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_external_link, 0, 0, 0)
                            compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
                        }
                        holder.layoutTimelineContainer.addView(tvLink)
                    }
                } catch (e: Exception) { /* ignore malformed json */ }
            }

            // 🔥 Eliminate smudge glow in Blue Theme explicitly for title
            if (ThemeHelper.getCurrentTheme(this@NotificationActivity) == "Blue") {
                holder.tvTitle.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvTime.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                holder.tvResolvedStatus.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
            }

            highlightMatches(holder, item)

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
                val uris = selectedReplyImages[item.id] ?: mutableListOf()
                val uploadedCount = getRecentUserAttachmentCount(item)
                if (uris.size + uploadedCount >= 4) {
                    ToastHelper.showToast(this@NotificationActivity, "you can upload a max. of 4 media before your query gets a response")
                    return@setOnClickListener
                }
                activePickerQueryId = item.id
                val galleryIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                }
                val defaultGalleryIntent = android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                val chooserIntent = android.content.Intent.createChooser(galleryIntent, "Select Image Source").apply {
                    putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent, defaultGalleryIntent))
                }
                replyPickerLauncher.launch(chooserIntent)
            }

            // Render selected reply attachments previews
            updateReplyPreviews(holder, item.id)
            updateViewHolderUploadProgress(holder, item.id)

            // Setup TextWatcher to save drafts
            val oldWatcher = holder.edtReply.tag as? android.text.TextWatcher
            if (oldWatcher != null) {
                holder.edtReply.removeTextChangedListener(oldWatcher)
            }
            holder.edtReply.setText(replyDrafts[item.id] ?: "")
            val newWatcher = object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    replyDrafts[item.id] = s?.toString() ?: ""
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            holder.edtReply.addTextChangedListener(newWatcher)
            holder.edtReply.tag = newWatcher

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

            // 🔥 Auto-scroll to ensure reply box is visible when keyboard opens
            val scrollAction = Runnable {
                val pos = holder.adapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
                    val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(rv.context) {
                        override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                            val actualBoxBottom = rv.height
                            val density = rv.context.resources.displayMetrics.density
                            // The margin is 16dp. We want to push the view down further so the visible gap is reduced even more.
                            val extraPushDownPx = (7 * density).toInt()
                            return actualBoxBottom - viewEnd + extraPushDownPx
                        }
                    }
                    scroller.targetPosition = pos
                    rv.layoutManager?.startSmoothScroll(scroller)
                }
            }

            holder.edtReply.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.postDelayed(scrollAction, 300)
                }
            }

            holder.edtReply.setOnClickListener { view ->
                view.postDelayed(scrollAction, 300)
            }

            holder.edtReply.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    // Height changed due to text expansion, scroll to keep it above keyboard
                    view.postDelayed(scrollAction, 50)
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
                        startTranslationX = holder.mainCardContainer.translationX
                        isSwiping = false
                        v == holder.itemView || (v !is EditText && v !is Button && v !is ImageButton)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dX = event.rawX - startRawX
                        val absDX = Math.abs(dX)
                        val absDY = Math.abs(event.rawY - startRawY)

                        if (!isSwiping && absDX > 5 && absDX > absDY && dX < 0) { // Only right-to-left
                            isSwiping = true
                            holder.itemView.parent?.requestDisallowInterceptTouchEvent(true)
                        }

                        if (isSwiping) {
                            // Only allow left swiping (negative translation)
                            if (dX < 0) {
                                holder.mainCardContainer.translationX = dX
                                holder.mainCardContainer.alpha = 1f - (absDX / holder.mainCardContainer.width.toFloat())
                            }
                        }
                        isSwiping
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isSwiping) {
                            val dX = event.rawX - startRawX
                            if (dX < 0 && Math.abs(dX) > holder.mainCardContainer.width * 0.3) {
                                // Fly off, then show delete confirmation dialog
                                isSwiping = false
                                holder.mainCardContainer.animate()
                                    .translationX(-holder.mainCardContainer.width.toFloat())
                                    .alpha(0f)
                                    .setDuration(200)
                                    .withEndAction { onDelete(item) }
                                    .start()
                            } else {
                                // Snap back
                                isSwiping = false
                                holder.mainCardContainer.animate()
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

        private fun renderAttachments(context: Context, urls: List<String>, container: LinearLayout, isAnnouncement: Boolean = false) {
            if (urls.isEmpty()) return
            val density = context.resources.displayMetrics.density
            val isSingle = urls.size == 1
            val standardW = (94 * density).toInt()
            val standardH = (136 * density).toInt()
            val gap = (8 * density).toInt()
            val rowGap = (8 * density).toInt()

            fun makeCard(url: String): androidx.cardview.widget.CardView {
                val card = androidx.cardview.widget.CardView(context).apply {
                    radius = (8 * density)
                    cardElevation = 0f
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    tag = "attachment_card"
                    layoutParams = android.widget.LinearLayout.LayoutParams(standardW, standardH).apply {
                        if (!isSingle) setMargins(0, 0, gap, 0)
                    }
                }
                val imgView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                card.addView(imgView)
                
                com.bumptech.glide.Glide.with(context)
                    .load(url)
                    .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        override fun onResourceReady(resource: android.graphics.drawable.Drawable, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                            if (isAnnouncement && isSingle && resource.intrinsicWidth > resource.intrinsicHeight) {
                                card.layoutParams = android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    (120 * density).toInt()
                                ).apply { setMargins(0, 0, 0, 0) }
                            }
                            imgView.setImageDrawable(resource)
                        }
                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                            imgView.setImageDrawable(placeholder)
                        }
                    })
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
                    btnParams.topMargin = (48 * density).toInt()
                    btnParams.marginEnd = (24 * density).toInt()
                    closeBtn.layoutParams = btnParams
                    val isWhite = ThemeHelper.isWhiteTheme(context)
                    val tintColor = if (isWhite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    closeBtn.background = null
                    closeBtn.setImageResource(R.drawable.ic_close)
                    closeBtn.setColorFilter(tintColor)
                    closeBtn.scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = (14 * density).toInt()
                    closeBtn.setPadding(p, p, p, p)
                    closeBtn.setOnClickListener { dialog.dismiss() }
                    fullImg.setOnClickListener { dialog.dismiss() }
                    fullImgContainer.setOnClickListener { dialog.dismiss() }
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
            val wrapperSize = 61
            val boxSize = 55
            
            for ((idx, uri) in uris.withIndex()) {
                val outerContainer = FrameLayout(holder.itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (16 * density).toInt()
                    }
                    clipChildren = false
                    clipToPadding = false
                }
                
                val innerCol = LinearLayout(holder.itemView.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (6 * density).toInt()
                        marginEnd = (6 * density).toInt()
                    }
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                
                val imageBox = FrameLayout(holder.itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams((boxSize * density).toInt(), (boxSize * density).toInt())
                    val tv = android.util.TypedValue()
                    context.theme.resolveAttribute(R.attr.inputBackground, tv, true)
                    background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
                    clipToOutline = true
                    clipChildren = true
                    setOnClickListener {
                        showFullscreenImagePreview(uri)
                    }
                }
                
                val imgView = ImageView(holder.itemView.context).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }
                com.bumptech.glide.Glide.with(holder.itemView.context).load(uri).into(imgView)
                imageBox.addView(imgView)
                
                val tvProgress = TextView(holder.itemView.context).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    tag = "progress_${queryId}_$uri"
                    
                    val pm = replyUploadProgress[queryId]
                    val currentProg = pm?.get(uri)
                    if (currentProg != null && currentProg < 100) {
                        visibility = View.VISIBLE
                        text = "$currentProg%"
                    } else {
                        visibility = View.GONE
                    }
                }
                imageBox.addView(tvProgress)
                
                val btnDelete = ImageButton(holder.itemView.context).apply {
                    layoutParams = FrameLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    }
                    setBackgroundResource(android.R.color.transparent)
                    setImageResource(R.drawable.ic_trash)
                    scaleType = ImageView.ScaleType.FIT_CENTER
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
                
                innerCol.addView(imageBox)
                
                val eyeIcon = ImageView(holder.itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()).apply {
                        topMargin = (10 * density).toInt()
                    }
                    setImageResource(R.drawable.ic_eye)
                    val tv = android.util.TypedValue()
                    context.theme.resolveAttribute(R.attr.textMutedColor, tv, true)
                    setColorFilter(androidx.core.content.ContextCompat.getColor(context, tv.resourceId))
                    setOnClickListener { showFullscreenImagePreview(uri) }
                }
                
                val viewText = TextView(holder.itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (2 * density).toInt()
                    }
                    text = "view"
                    textSize = 10f
                    val tv = android.util.TypedValue()
                    context.theme.resolveAttribute(R.attr.textMutedColor, tv, true)
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, tv.resourceId))
                    setOnClickListener { showFullscreenImagePreview(uri) }
                }
                
                innerCol.addView(eyeIcon)
                innerCol.addView(viewText)
                
                outerContainer.addView(innerCol)
                outerContainer.addView(btnDelete)
                
                holder.layoutReplyPreviews.addView(outerContainer)
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
                    "$currentHistory\n\nTeam CashDash: $lastTeamReply\n\n$userName: $textWithAttachments"
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
                        replyDrafts.remove(model.id)
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
            val tvSwipeHintItem = v.findViewById<TextView>(R.id.tvSwipeHintItem)
            val mainCardContainer = v.findViewById<LinearLayout>(R.id.mainCardContainer)
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
    
    private fun showFullscreenImagePreview(uri: Uri) {
        val dialog = android.app.Dialog(this@NotificationActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = FrameLayout(this@NotificationActivity)
        container.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        val imgView = com.github.chrisbanes.photoview.PhotoView(this@NotificationActivity)
        imgView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        imgView.scaleType = ImageView.ScaleType.FIT_CENTER
        com.bumptech.glide.Glide.with(this@NotificationActivity).load(uri).into(imgView)

        val closeBtn = ImageView(this@NotificationActivity)
        val density = resources.displayMetrics.density
        val btnParams = FrameLayout.LayoutParams(
            (56 * density).toInt(),
            (56 * density).toInt()
        )
        btnParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        btnParams.topMargin = (48 * density).toInt()
        btnParams.marginEnd = (24 * density).toInt()
        closeBtn.layoutParams = btnParams
        
        val isWhite = ThemeHelper.isWhiteTheme(this@NotificationActivity)
        val tintColor = if (isWhite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        
        closeBtn.background = null
        closeBtn.setImageResource(R.drawable.ic_close)
        closeBtn.setColorFilter(tintColor)
        closeBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = (14 * density).toInt()
        closeBtn.setPadding(p, p, p, p)
        closeBtn.setOnClickListener { dialog.dismiss() }

        imgView.setOnClickListener { dialog.dismiss() }
        container.setOnClickListener { dialog.dismiss() }

        container.addView(imgView)
        container.addView(closeBtn)

        dialog.setContentView(container)
        dialog.show()
    }
}
