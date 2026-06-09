package com.cash.dash

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import com.google.firebase.firestore.FirebaseFirestore

class AdminActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val root = findViewById<android.view.View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomPadding = Math.max(systemBars.bottom, ime.bottom)
            view.setPadding(0, 0, 0, bottomPadding)
            insets
        }

        val edtTitle = findViewById<EditText>(R.id.edtAnnouncementTitle)
        val edtBody = findViewById<EditText>(R.id.edtAnnouncementBody)
        val btnPublish = findViewById<Button>(R.id.btnPublishAnnouncement)

        btnPublish.setOnClickListener {
            val title = edtTitle.text.toString().trim()
            val body = edtBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in both fields")
                return@setOnClickListener
            }

            btnPublish.isEnabled = false
            btnPublish.text = "Publishing..."

            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            val data = hashMapOf(
                "subject" to "📣 $title",
                "query" to body,
                "reply" to "[ANNOUNCEMENT]",
                "status" to "resolved",
                "timestamp" to timestamp,
                "read" to false
            )

            db.collection("announcements").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Announcement Published!")
                    edtTitle.text.clear()
                    edtBody.text.clear()
                    btnPublish.isEnabled = true
                    btnPublish.text = "Publish Announcement"
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to publish")
                    btnPublish.isEnabled = true
                    btnPublish.text = "Publish Announcement"
                }
        }

        val edtPushTitle = findViewById<EditText>(R.id.edtPushTitle)
        val edtPushBody = findViewById<EditText>(R.id.edtPushBody)
        val btnSendPush = findViewById<Button>(R.id.btnSendPush)

        btnSendPush.setOnClickListener {
            val title = edtPushTitle.text.toString().trim()
            val body = edtPushBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in both fields")
                return@setOnClickListener
            }

            btnSendPush.isEnabled = false
            btnSendPush.text = "Sending..."

            val db = FirebaseFirestore.getInstance()
            val timestamp = System.currentTimeMillis()
            val data = hashMapOf(
                "title" to title,
                "message" to body,
                "timestamp" to timestamp
            )

            db.collection("global_pushes").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Push Notification Sent!")
                    edtPushTitle.text.clear()
                    edtPushBody.text.clear()
                    btnSendPush.isEnabled = true
                    btnSendPush.text = "Send Global Push"
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to send")
                    btnSendPush.isEnabled = true
                    btnSendPush.text = "Send Global Push"
                }
        }
    }
}
