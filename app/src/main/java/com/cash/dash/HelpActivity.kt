@file:Suppress("DEPRECATION")
package com.cash.dash

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.storage.FirebaseStorage


class HelpActivity : ThemedActivity() {

    private var selectedImageUri: Uri? = null
    private var activeDialog: Dialog? = null

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            val imgPreview = activeDialog?.findViewById<ImageView>(R.id.imgPreview)
            imgPreview?.visibility = android.view.View.VISIBLE
            imgPreview?.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val btnNotifications = findViewById<android.view.View>(R.id.btnNotifications)
        val notificationBadge = findViewById<android.view.View>(R.id.notificationBadge)
        
        btnNotifications.setOnClickListener {
            notificationBadge.visibility = android.view.View.GONE
            startActivity(Intent(this, NotificationActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
        
        setupNotificationListener(notificationBadge)

        findViewById<TextView>(R.id.btnContactUs).setOnClickListener {
            showContactDialog()
        }

        val root = findViewById<android.view.View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val btnContactUs = findViewById<android.view.View>(R.id.btnContactUs)
            val params = btnContactUs.layoutParams as ViewGroup.MarginLayoutParams
            // User requested ~0.5-1 cm of blank space (approx 40dp extra)
            params.bottomMargin = systemBars.bottom + 60
            btnContactUs.layoutParams = params
            insets
        }
    }

    private fun showContactDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_contact_us)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - 100,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        activeDialog = dialog

        val tvName = dialog.findViewById<TextView>(R.id.tvContactName)
        val tvTime = dialog.findViewById<TextView>(R.id.tvContactTime)
        val tvEmail = dialog.findViewById<TextView>(R.id.tvContactEmail)
        val edtSubject = dialog.findViewById<EditText>(R.id.edtContactSubject)
        val edtQuery = dialog.findViewById<EditText>(R.id.edtContactQuery)
        val btnAddImage = dialog.findViewById<Button>(R.id.btnAddImage)
        val imgPreview = dialog.findViewById<ImageView>(R.id.imgPreview)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnContactSubmit)
        
        btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Load User Data
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "User") ?: "User"
        val email = prefs.getString("user_email", "No Email") ?: "No Email"

        // Get Formatted Time
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val currentTime = sdf.format(Date())

        tvName.text = "Name: $name"
        tvTime.text = "Time: $currentTime"
        tvEmail.text = "Email: $email"

        btnSubmit.setOnClickListener {
            val subject = edtSubject.text.toString().trim()
            val query = edtQuery.text.toString().trim()

            if (subject.isEmpty()) {
                ToastHelper.showToast(this, "Please enter a subject")
                return@setOnClickListener
            }

            if (query.isEmpty()) {
                ToastHelper.showToast(this, "Please mention your query")
                return@setOnClickListener
            }

            // Prevent multiple submissions
            btnSubmit.isEnabled = false
            btnSubmit.text = "Submitting..."

            if (selectedImageUri != null) {
                btnSubmit.text = "Uploading Image..."
                val storageRef = FirebaseStorage.getInstance().reference
                val imageRef = storageRef.child("support_attachments/${System.currentTimeMillis()}.jpg")
                imageRef.putFile(selectedImageUri!!)
                    .addOnProgressListener { taskSnapshot ->
                        val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                        btnSubmit.text = "Uploading Image... $progress%"
                    }
                    .addOnSuccessListener {
                        imageRef.downloadUrl.addOnSuccessListener { uri ->
                            submitQueryToFirestore(name, currentTime, email, subject, query, uri.toString())
                            dialog.dismiss()
                        }
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(this@HelpActivity, "Failed to upload image: ${e.message}")
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Submit"
                    }
            } else {
                submitQueryToFirestore(name, currentTime, email, subject, query, null)
                dialog.dismiss()
            }
        }
        
        dialog.setOnDismissListener {
            selectedImageUri = null
            activeDialog = null
        }

        dialog.show()
    }

    private fun submitQueryToFirestore(name: String, time: String, email: String, subject: String, query: String, imageUrl: String?) {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        val timestamp = System.currentTimeMillis()

        // Also save to Firestore for persistence and display in NotificationActivity
        // 🔥 offline-first queueing: we set needs_admin_email to true so the cloud function catches it when internet returns
        val db = FirebaseFirestore.getInstance()
        val notificationData = hashMapOf<String, Any>(
            "name" to name,
            "email" to email,
            "time" to time,
            "subject" to subject,
            "originalSubject" to subject,
            "query" to query,
            "timestamp" to timestamp,
            "read" to false,
            "status" to "pending",
            "reply" to "Waiting for reply...",
            "needs_admin_email" to true
        )
        if (imageUrl != null) {
            notificationData["imageUrl"] = imageUrl
        }
        // Use the explicit timestamp as the document ID so the backend can update it on reply
        val userEmail = user.email ?: getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", null) ?: return
        
        db.collection("users").document(userEmail).collection("notifications")
            .document(timestamp.toString())
            .set(notificationData)
            .addOnSuccessListener {
            }
            .addOnFailureListener { e ->
            }

        // 🚀 SHOW IMMEDIATE SUCCESS (Don't wait for background webhook)
        ToastHelper.showToast(this, "Query sent! We'll notify you when we reply.")

        // 🚀 ASYNC DIRECT WEBHOOK INJECTION:
        // Parallel direct push to circumvent Firestore trigger cold-starts and send instant email!
        triggerImmediateWebhook(user.uid, name, userEmail, time, subject, query, timestamp, imageUrl)
    }

    private fun setupNotificationListener(badge: android.view.View) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", null) ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users").document(email).collection("notifications")
            .whereEqualTo("read", false)
            .addSnapshotListener { snapshot, _ ->
                var hasUnreadReply = false
                if (snapshot != null) {
                    for (doc in snapshot.documents) {
                        val reply = doc.getString("reply")?.trim()
                        if (!reply.isNullOrEmpty() && reply != "Waiting for reply...") {
                            hasUnreadReply = true
                            break
                        }
                    }
                }
                badge.visibility = if (hasUnreadReply) android.view.View.VISIBLE else android.view.View.GONE
            }
    }

    private fun triggerImmediateWebhook(uid: String, name: String, email: String, time: String, subject: String, query: String, timestamp: Long, imageUrl: String?) {
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
                    put("id", timestamp.toString()) // ⚡ CRITICAL: Required for direct Cloud Function routing
                    put("name", name)
                    put("email", email)
                    put("time", time)
                    put("subject", subject)
                    put("query", query)
                    put("timestamp", timestamp)
                    if (imageUrl != null) {
                        put("imageUrl", imageUrl)
                    }
                }

                val os = conn.outputStream
                val writer = OutputStreamWriter(os, "UTF-8")
                writer.write(payload.toString())
                writer.flush()
                writer.close()
                os.close()

                val responseCode = conn.responseCode
                Log.d("HelpActivity", "⚡ Immediate Webhook Sent. Response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("HelpActivity", "❌ Immediate Webhook Failed: ${e.message}")
            }
        }
    }
}
