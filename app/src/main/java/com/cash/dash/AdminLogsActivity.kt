package com.cash.dash

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

class AdminLogsActivity : ThemedActivity() {

    private lateinit var rvLogs: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: LogsAdapter
    private val logsList = mutableListOf<AdminLogModel>()
    private val fullLogsList = mutableListOf<AdminLogModel>()
    private var selectedFilters = mutableSetOf<String>()
    private var logsListener: com.google.firebase.firestore.ListenerRegistration? = null

    private var isFirstPermissionCheck = true
    private val permissionListener: (AdminManager.AdminPermissions) -> Unit = { perms ->
        if (!isFirstPermissionCheck && !perms.hasAnyAccess) {
            ToastHelper.showToast(this, "Permission denied")
            finish()
        }
        isFirstPermissionCheck = false
    }

    override fun onDestroy() {
        super.onDestroy()
        AdminManager.removeListener(permissionListener)
        logsListener?.remove()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_logs)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        AdminManager.addListener(permissionListener)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnFilterLogs).setOnClickListener { showFilterDialog() }

        rvLogs = findViewById(R.id.rvAdminLogs)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmptyLogs)

        rvLogs.layoutManager = LinearLayoutManager(this)
        adapter = LogsAdapter(logsList)
        rvLogs.adapter = adapter

        // Apply window insets for status bar padding and notch styling
        val root = (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        fetchAdminLogs()
    }

    private fun fetchAdminLogs() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        rvLogs.visibility = View.GONE

        val db = FirebaseFirestore.getInstance()
        logsListener = db.collection("admin_logs")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { querySnapshot, e ->
                progressBar.visibility = View.GONE
                if (e != null) {
                    tvEmpty.text = "No activity logs available offline."
                    tvEmpty.visibility = View.VISIBLE
                    return@addSnapshotListener
                }

                if (querySnapshot == null || querySnapshot.isEmpty) {
                    tvEmpty.visibility = View.VISIBLE
                    fullLogsList.clear()
                    applyFilters()
                    return@addSnapshotListener
                }
                
                tvEmpty.visibility = View.GONE

                fullLogsList.clear()
                val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())

                for (doc in querySnapshot.documents) {
                    val performedBy = doc.getString("performedBy")
                    val adminEmail = doc.getString("adminEmail")
                    val email = adminEmail ?: performedBy ?: "Unknown"
                    val name = when (email.lowercase().trim()) {
                        "mohamedaufin64@gmail.com" -> "Mohamed Aufin"
                        "arunbhalaji200904@gmail.com" -> "Arun Bhalaji"
                        else -> email
                    }
                    val rawType = doc.getString("actionType") ?: doc.getString("type") ?: "Action"
                    var type = if (rawType == "Targeted Announcement") "User Specific Announcement" else rawType
                    type = type.replace(" (Batch)", "")
                    type = type.replace("Push", "Notification", ignoreCase = true)
                    val title = doc.getString("title") ?: ""
                    val message = doc.getString("message") ?: doc.getString("content") ?: ""
                    val ts = doc.getLong("timestamp") ?: 0L
                    val notifClickers = (doc.get("notif_clickers") as? List<*>)?.size ?: 0
                    val annClickers = (doc.get("ann_clickers") as? List<*>)?.size ?: 0
                    val totalAudience = doc.getLong("totalAudience")?.toInt() ?: 0
                    val legacyClicks = doc.getLong("clicks") ?: 0L
                    val details = doc.getString("details") ?: doc.getString("targetUsers")
                    val payload = doc.get("payload") as? Map<String, Any>
                    val timeStr = if (ts > 0) sdf.format(Date(ts)) else ""

                    val emails = if (!details.isNullOrEmpty()) details.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

                    var finalActionDesc = type
                    var targetedUsersText: String? = null
                    
                    if (emails.isNotEmpty() && (type.contains("User Specific") || type.contains("Age Specific"))) {
                        if (emails.size == 1) {
                            targetedUsersText = "Targeted user :\n• ${emails[0]}"
                        } else {
                            targetedUsersText = "Targeted users :\n" + emails.joinToString("\n") { "• $it" }
                        }
                    }

                    // Formatted content text
                    val formattedDetails = buildString {
                        if (payload != null) {
                            val notifTitle = payload["notifTitle"] as? String ?: ""
                            val notifBody = payload["notifBody"] as? String ?: ""
                            val annTitle = payload["annTitle"] as? String ?: ""
                            val annBody = payload["annBody"] as? String ?: ""
                            val triggerUrl = payload["triggerUrl"] as? String ?: ""
                            val triggerUrlTitle = payload["triggerUrlTitle"] as? String ?: ""
                            val uploadedImageUrl = payload["uploadedImageUrl"] as? String ?: ""
                            
                            val hasNotif = notifTitle.isNotEmpty() || notifBody.isNotEmpty()
                            val notifLines = mutableListOf<String>()
                            if (hasNotif) {
                                notifLines.add("Push Notification:")
                                if (notifTitle.isNotEmpty()) notifLines.add("Title - ${android.text.TextUtils.htmlEncode(notifTitle)}")
                                if (notifBody.isNotEmpty()) notifLines.add("Content - ${android.text.TextUtils.htmlEncode(notifBody)}")
                                val cleanUrl = triggerUrl.replace("https://", "").replace("http://", "")
                                if (triggerUrlTitle.isNotEmpty()) notifLines.add("Trigger URL Title - ${android.text.TextUtils.htmlEncode(triggerUrlTitle)}")
                                if (cleanUrl.isNotEmpty()) notifLines.add("Trigger URL - <a href=\"https://$cleanUrl\">$cleanUrl</a>")
                                append(notifLines.joinToString("\n"))
                            }

                            val hasAnn = annTitle.isNotEmpty() || annBody.isNotEmpty()
                            if (hasAnn) {
                                if (hasNotif) append("\n\n")
                                val annLines = mutableListOf<String>()
                                annLines.add("Announcement:")
                                if (annTitle.isNotEmpty()) annLines.add("Title - ${android.text.TextUtils.htmlEncode(annTitle)}")
                                
                                val urls = mutableListOf<String>()
                                if (annBody.isNotEmpty()) {
                                    var cleanAnnBody = annBody.replace("&nbsp;<a href=\"[^\"]*\"><img src=\"ic_external_link\"/></a>".toRegex(), "")
                                        .replace("&nbsp;<img src=\"ic_external_link\"/>".toRegex(), "")
                                        .replace("&nbsp;<font color=\"#2196F3\">\u2197</font>".toRegex(), "")
                                        
                                    cleanAnnBody = cleanAnnBody.replace("<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>".toRegex()) { match ->
                                        urls.add(match.groupValues[1])
                                        "<b>${match.groupValues[2]}</b>"
                                    }
                                    annLines.add("Content - $cleanAnnBody")
                                }
                                if (urls.isNotEmpty()) {
                                    annLines.add("Link - " + urls.joinToString(", ") { "<a href=\"$it\">$it</a>" })
                                }
                                append(annLines.joinToString("\n"))
                            }
                            
                            if (uploadedImageUrl.isNotEmpty()) {
                                if (hasNotif || hasAnn) append("\n\n")
                                append("Supporting media:")
                            }
                        } else {
                            if (title.isNotEmpty()) {
                                append("Title: ")
                                append(android.text.TextUtils.htmlEncode(title))
                            }
                            if (message.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append("Message: ")
                                append(android.text.TextUtils.htmlEncode(message))
                            }
                        }
                    }

                    val ageRange = doc.getString("ageRange")
                    val triggeredByText = "This action is triggered by: $name"
                    
                    if (ageRange != null) {
                        val ageStr = "Age targeted : ($ageRange)"
                        targetedUsersText = if (targetedUsersText != null) {
                            "$ageStr\n\n$targetedUsersText"
                        } else {
                            ageStr
                        }
                    }

                    // Build a synthetic payload for old logs that pre-date payload storage,
                    // so the Re-trigger button always appears for every retrigger-able log.
                    val effectivePayload: Map<String, Any> = payload ?: run {
                        val tabType = when {
                            type.contains("Global", ignoreCase = true) -> "GLOBAL"
                            type.contains("Admin", ignoreCase = true) -> "ADMIN"
                            type.contains("User Specific", ignoreCase = true) -> "USER"
                            type.contains("Age Specific", ignoreCase = true) -> "AGE"
                            else -> "GLOBAL"
                        }
                        val isAnn = type.contains("Announcement", ignoreCase = true)
                        if (isAnn) {
                            // Announcement → AdminMessagingActivity fields
                            mapOf("msg_title" to title, "msg_body" to message, "tabType" to tabType, "isAnnouncement" to true)
                        } else if (type.contains("Notification", ignoreCase = true) || type.contains("Push", ignoreCase = true)) {
                            // Push Notification → AdminMessagingActivity fields
                            mapOf("msg_title" to title, "msg_body" to message, "tabType" to tabType, "isAnnouncement" to false)
                        } else {
                            // Promotional Activity → AdminPromotionsActivity fields
                            mapOf("notifTitle" to title, "notifBody" to message, "tabType" to tabType)
                        }
                    }

                    var clicksText: String? = null
                    if (totalAudience > 0 || notifClickers > 0 || annClickers > 0) {
                        val audStr = if (totalAudience > 0) "$totalAudience" else "?"
                        val parts = mutableListOf<String>()
                        if (notifClickers > 0 || totalAudience > 0) {
                            parts.add("Clicks through notification : $notifClickers/$audStr")
                        }
                        if (annClickers > 0 || totalAudience > 0) {
                            parts.add("Clicks through announcement : $annClickers/$audStr")
                        }
                        if (parts.isNotEmpty()) {
                            clicksText = parts.joinToString("\n")
                        }
                    } else if (legacyClicks > 0) {
                        clicksText = "Link Clicks: $legacyClicks"
                    }

                    fullLogsList.add(
                        AdminLogModel(
                            actionType = finalActionDesc,
                            time = timeStr,
                            details = formattedDetails,
                            triggeredBy = triggeredByText,
                            rawActionType = type,
                            targetedUsers = targetedUsersText,
                            payload = effectivePayload,
                            imageUrl = (payload?.get("uploadedImageUrl") as? String)?.takeIf { it.isNotEmpty() },
                            clicksText = clicksText
                        )
                    )
                }

                applyFilters()
            }
    }

    private fun applyFilters() {
        logsList.clear()
        if (selectedFilters.isEmpty()) {
            logsList.addAll(fullLogsList)
        } else {
            logsList.addAll(fullLogsList.filter { log ->
                val type = log.rawActionType ?: ""
                val isNotif = type.contains("Notification", ignoreCase = true) || type.contains("Push", ignoreCase = true)
                val isAnn = type.contains("Announcement", ignoreCase = true)
                val isPromo = type.contains("Promotional Activity", ignoreCase = true)
                
                (selectedFilters.contains("Notifications") && isNotif) ||
                (selectedFilters.contains("Announcements") && isAnn) ||
                (selectedFilters.contains("Promotions") && isPromo)
            })
        }
        adapter.notifyDataSetChanged()
        
        if (logsList.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvLogs.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvLogs.visibility = View.VISIBLE
        }
    }

    private fun showFilterDialog() {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_admin_logs_filter, null)
        bottomSheetDialog.setContentView(view)
        
        val cbNotif = view.findViewById<android.widget.CheckBox>(R.id.cbNotifications)
        val cbAnn = view.findViewById<android.widget.CheckBox>(R.id.cbAnnouncements)
        val cbPromo = view.findViewById<android.widget.CheckBox>(R.id.cbPromotions)
        
        cbNotif.isChecked = selectedFilters.contains("Notifications")
        cbAnn.isChecked = selectedFilters.contains("Announcements")
        cbPromo.isChecked = selectedFilters.contains("Promotions")
        
        view.findViewById<View>(R.id.btnApplyFilter).setOnClickListener {
            selectedFilters.clear()
            if (cbNotif.isChecked) selectedFilters.add("Notifications")
            if (cbAnn.isChecked) selectedFilters.add("Announcements")
            if (cbPromo.isChecked) selectedFilters.add("Promotions")
            
            applyFilters()
            bottomSheetDialog.dismiss()
        }
        
        view.findViewById<View>(R.id.btnClearFilter).setOnClickListener {
            selectedFilters.clear()
            cbNotif.isChecked = false
            cbAnn.isChecked = false
            cbPromo.isChecked = false
            applyFilters()
        }
        
        view.findViewById<View>(R.id.btnCancelFilter).setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.show()
    }

    data class AdminLogModel(
        val actionType: String,
        val time: String,
        val details: String,
        val triggeredBy: String,
        val rawActionType: String,
        val targetedUsers: String?,
        val payload: Map<String, Any>?,
        val imageUrl: String? = null,
        val clicksText: String? = null
    )

    inner class LogsAdapter(private val items: List<AdminLogModel>) :
        RecyclerView.Adapter<LogsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_admin_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvActionType.text = item.actionType
            holder.tvTime.text = item.time
            holder.tvDetails.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            val linkColorStr = if (ThemeHelper.isWhiteTheme(holder.itemView.context)) "#0047AB" else "#2196F3"
            holder.tvDetails.setLinkTextColor(android.graphics.Color.parseColor(linkColorStr))
            holder.tvDetails.text = android.text.Html.fromHtml(item.details.replace("\n", "<br>"), android.text.Html.FROM_HTML_MODE_LEGACY)
            holder.tvTriggeredBy.text = item.triggeredBy

            // Color-code the left accent bar and the badge text based on rawActionType
            val color = when {
                item.rawActionType.contains("User Specific", ignoreCase = true) -> android.graphics.Color.parseColor(if (ThemeHelper.isWhiteTheme(holder.itemView.context)) "#CD8500" else "#FFA500") // Yellow
                item.rawActionType.contains("Age Specific", ignoreCase = true) -> android.graphics.Color.parseColor(if (ThemeHelper.isWhiteTheme(holder.itemView.context)) "#0047AB" else "#00C2FF") // Blue
                item.rawActionType.contains("Global", ignoreCase = true) -> ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textGreenColor) // Green
                item.rawActionType.contains("Admin", ignoreCase = true) -> ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textRedColor) // Red
                item.rawActionType.contains("Promotional Activity", ignoreCase = true) -> android.graphics.Color.parseColor("#FF007F") // Pink
                else -> ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textGreenColor)
            }
            
            if (item.targetedUsers != null) {
                holder.tvTargetedUsers.visibility = View.VISIBLE
                holder.tvTargetedUsers.text = item.targetedUsers
            } else {
                holder.tvTargetedUsers.visibility = View.GONE
            }

            if (item.clicksText != null) {
                holder.tvLogClicks.visibility = View.VISIBLE
                holder.tvLogClicks.text = item.clicksText
            } else {
                holder.tvLogClicks.visibility = View.GONE
            }

            if (item.imageUrl != null) {
                holder.cardLogMedia.visibility = View.VISIBLE
                holder.cardLogMedia.setOnClickListener {
                    showFullscreenImagePreview(item.imageUrl)
                }
                val density = holder.itemView.resources.displayMetrics.density
                com.bumptech.glide.Glide.with(holder.itemView.context)
                    .asBitmap()
                    .load(item.imageUrl)
                    .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                        override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                            val isPortrait = resource.height > resource.width
                            holder.imgLogMedia.layoutParams = holder.imgLogMedia.layoutParams.apply {
                                if (isPortrait) {
                                    this.width = (94 * density).toInt()
                                    this.height = (136 * density).toInt()
                                    holder.imgLogMedia.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                } else {
                                    this.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    this.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                    holder.imgLogMedia.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                }
                            }
                            holder.imgLogMedia.setImageBitmap(resource)
                        }
                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                    })
            } else {
                holder.cardLogMedia.visibility = View.GONE
            }

            holder.viewAccentBar.setBackgroundColor(color)
            holder.tvActionType.setTextColor(color)
            holder.tvTriggeredBy.setTextColor(color)

            if (item.payload != null) {
                holder.btnReTrigger.visibility = View.VISIBLE
                holder.btnReTrigger.setOnClickListener {
                    // Determine which activity to launch based on what was originally sent
                    val isMessagingLog = item.payload.containsKey("msg_title") || item.payload.containsKey("msg_body")
                    val targetActivity = if (isMessagingLog) AdminMessagingActivity::class.java else AdminPromotionsActivity::class.java
                    val intent = android.content.Intent(holder.itemView.context, targetActivity).apply {
                        putExtra("is_retrigger", true)
                        // For AdminMessagingActivity, also pass isAnnouncement flag
                        if (isMessagingLog) {
                            putExtra("isAnnouncement", item.payload["isAnnouncement"] as? Boolean ?: item.rawActionType.contains("Announcement", ignoreCase = true))
                        }
                        item.payload.forEach { (key, value) ->
                            when (value) {
                                is String  -> putExtra(key, value)
                                is Boolean -> putExtra(key, value)
                                is List<*> -> putStringArrayListExtra(key, ArrayList(value.map { it.toString() }))
                            }
                        }
                    }
                    holder.itemView.context.startActivity(intent)
                }
            } else {
                holder.btnReTrigger.visibility = View.GONE
                holder.btnReTrigger.setOnClickListener(null)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val viewAccentBar: View = itemView.findViewById(R.id.viewAccentBar)
            val tvActionType: TextView = itemView.findViewById(R.id.tvLogActionType)
            val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
            val tvDetails: TextView = itemView.findViewById(R.id.tvLogDetails)
            val tvTriggeredBy: TextView = itemView.findViewById(R.id.tvLogTriggeredBy)
            val tvTargetedUsers: TextView = view.findViewById(R.id.tvLogTargetedUsers)
            val btnReTrigger: TextView = view.findViewById(R.id.btnReTrigger)
            val tvLogClicks: TextView = view.findViewById(R.id.tvLogClicks)
            val cardLogMedia: androidx.cardview.widget.CardView = view.findViewById(R.id.cardLogMedia)
            val imgLogMedia: android.widget.ImageView = view.findViewById(R.id.imgLogMedia)
        }
    }

    private fun showFullscreenImagePreview(model: Any) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = android.widget.FrameLayout(this)
        container.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        val imgView = com.github.chrisbanes.photoview.PhotoView(this)
        imgView.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        imgView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        com.bumptech.glide.Glide.with(this).load(model).into(imgView)

        val closeBtn = android.widget.ImageView(this)
        val density = resources.displayMetrics.density
        val btnParams = android.widget.FrameLayout.LayoutParams(
            (56 * density).toInt(),
            (56 * density).toInt()
        )
        btnParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        btnParams.topMargin = (48 * density).toInt()
        btnParams.marginEnd = (24 * density).toInt()
        closeBtn.layoutParams = btnParams
        
        val isWhite = ThemeHelper.isWhiteTheme(this)
        val tintColor = if (isWhite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        
        closeBtn.background = null
        closeBtn.setImageResource(R.drawable.ic_close)
        closeBtn.setColorFilter(tintColor)
        closeBtn.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
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
