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


class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        // Immersive UI
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.btnContactUs).setOnClickListener {
            showContactDialog()
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

        val tvName = dialog.findViewById<TextView>(R.id.tvContactName)
        val tvTime = dialog.findViewById<TextView>(R.id.tvContactTime)
        val tvEmail = dialog.findViewById<TextView>(R.id.tvContactEmail)
        val edtSubject = dialog.findViewById<EditText>(R.id.edtContactSubject)
        val edtQuery = dialog.findViewById<EditText>(R.id.edtContactQuery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnContactSubmit)

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

            submitQueryToFirestore(name, currentTime, email, subject, query)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun submitQueryToFirestore(name: String, time: String, email: String, subject: String, query: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        // 🚀 Firebase Cloud Function Webhook (replaces Pipedream)
        val pipedreamUrl = "https://us-central1-cashdash-8cd8b.cloudfunctions.net/cashdashWebhook"

        val timestamp = System.currentTimeMillis()

        // structuredQuery is ONLY for the admin email body — never stored in Firestore
        val payload = """
            {
              "uid": "${user.uid}",
              "id": "$timestamp",
              "name": "${name.replace("\"", "\\\"")}",
              "email": "${email.replace("\"", "\\\"")}",
              "time": "${time.replace("\"", "\\\"")}",
              "subject": "${subject.replace("\"", "\\\"")}",
              "query": "${query.replace("\n", "\\n").replace("\"", "\\\"")}",
              "rawQuery": "${query.replace("\n", "\\n").replace("\"", "\\\"")}",
              "timestamp": $timestamp,
              "is_reply": false
            }
        """.trimIndent()

        // Also save to Firestore for persistence and display in NotificationActivity
        val db = FirebaseFirestore.getInstance()
        val notificationData = hashMapOf(
            "name" to name,
            "email" to email,
            "time" to time,
            "subject" to subject,
            "originalSubject" to subject,
            "query" to query,
            "timestamp" to timestamp,
            "read" to false,
            "status" to "pending",
            "reply" to "Waiting for reply..."
        )
        // Use the explicit timestamp as the document ID so the backend can update it on reply
        val userEmail = user.email ?: return
        db.collection("users").document(userEmail).collection("notifications")
            .document(timestamp.toString())
            .set(notificationData)
            .addOnSuccessListener {
                Log.d("HelpActivity", "Query recorded in Firestore")
            }
            .addOnFailureListener { e ->
                Log.e("HelpActivity", "Error recording query in Firestore", e)
            }

        // 🚀 SHOW IMMEDIATE SUCCESS (Don't wait for background webhook)
        ToastHelper.showToast(this, "Query sent! We'll notify you when we reply.")

        Thread {
            try {
                val url = java.net.URL(pipedreamUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                conn.outputStream.use { os ->
                    val input = payload.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }
                
                // Silent check of response
                val code = conn.responseCode
                Log.d("HelpActivity", "Webhook response: $code")
                
            } catch (e: Exception) {
                Log.e("HelpActivity", "Background webhook error", e)
            }
        }.start()
    }
}
