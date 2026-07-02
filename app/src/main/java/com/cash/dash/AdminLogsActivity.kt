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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_logs)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

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
        db.collection("admin_logs")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                progressBar.visibility = View.GONE
                if (querySnapshot.isEmpty) {
                    tvEmpty.visibility = View.VISIBLE
                    return@addOnSuccessListener
                }

                logsList.clear()
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
                    if (type == "User Specific Push") {
                        type = "User Specific Notification"
                    }
                    val title = doc.getString("title") ?: ""
                    val message = doc.getString("message") ?: doc.getString("content") ?: ""
                    val ts = doc.getLong("timestamp") ?: 0L
                    val details = doc.getString("details") ?: doc.getString("targetUsers")
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
                        if (title.isNotEmpty()) {
                            append("Title: ")
                            append(title)
                        }
                        if (message.isNotEmpty()) {
                            if (isNotEmpty()) append("\n")
                            append("Message: ")
                            append(message)
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

                    logsList.add(
                        AdminLogModel(
                            actionType = finalActionDesc,
                            time = timeStr,
                            details = formattedDetails,
                            triggeredBy = triggeredByText,
                            rawActionType = type,
                            targetedUsers = targetedUsersText
                        )
                    )
                }

                rvLogs.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                tvEmpty.text = "Failed to load activity logs."
                tvEmpty.visibility = View.VISIBLE
                ToastHelper.showToast(this, "Error: ${e.message}")
            }
    }

    data class AdminLogModel(
        val actionType: String,
        val time: String,
        val details: String,
        val triggeredBy: String,
        val rawActionType: String,
        val targetedUsers: String?
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
            holder.tvDetails.text = item.details
            holder.tvTriggeredBy.text = item.triggeredBy

            // Color-code the left accent bar and the badge text based on rawActionType
            val color = when {
                item.rawActionType.contains("Age Specific Announcement", ignoreCase = true) -> android.graphics.Color.parseColor("#FF007F") // Pink
                item.rawActionType.contains("Age Specific Notification", ignoreCase = true) -> android.graphics.Color.parseColor("#00C2FF") // Cyan
                item.rawActionType.contains("Global", ignoreCase = true) -> ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textGreenColor) // Green
                item.rawActionType.contains("Admin", ignoreCase = true) -> ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textRedColor) // Red
                item.rawActionType.contains("User Specific", ignoreCase = true) -> android.graphics.Color.parseColor("#FFA500") // Yellow
                else -> ThemeHelper.resolveColorAttr(holder.itemView.context, R.attr.textGreenColor)
            }
            
            if (item.targetedUsers != null) {
                holder.tvTargetedUsers.visibility = View.VISIBLE
                holder.tvTargetedUsers.text = item.targetedUsers
            } else {
                holder.tvTargetedUsers.visibility = View.GONE
            }

            holder.viewAccentBar.setBackgroundColor(color)
            holder.tvActionType.setTextColor(color)
            holder.tvTriggeredBy.setTextColor(color)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val viewAccentBar: View = itemView.findViewById(R.id.viewAccentBar)
            val tvActionType: TextView = itemView.findViewById(R.id.tvLogActionType)
            val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
            val tvDetails: TextView = itemView.findViewById(R.id.tvLogDetails)
            val tvTriggeredBy: TextView = itemView.findViewById(R.id.tvLogTriggeredBy)
            val tvTargetedUsers: TextView = itemView.findViewById(R.id.tvLogTargetedUsers)
        }
    }
}
