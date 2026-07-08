package com.cash.dash

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.*

class AdminPromotionsActivity : ThemedActivity() {

    private lateinit var edtNotifTitle: EditText
    private lateinit var edtNotifBody: EditText
    private lateinit var edtAnnouncementTitle: EditText
    private lateinit var edtAnnouncementBody: EditText
    private lateinit var edtTriggerText: EditText
    private lateinit var edtTriggerUrl: EditText
    private lateinit var tvImageStatus: TextView
    private lateinit var spinnerTargetAudience: Spinner
    private lateinit var btnSendPromotions: Button

    private var selectedImageUri: Uri? = null
    private var uploadedImageUrl: String = ""

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            val fileName = getFileName(uri)
            tvImageStatus.text = "Selected: $fileName"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_promotions)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        edtNotifTitle = findViewById(R.id.edtNotifTitle)
        edtNotifBody = findViewById(R.id.edtNotifBody)
        edtAnnouncementTitle = findViewById(R.id.edtAnnouncementTitle)
        edtAnnouncementBody = findViewById(R.id.edtAnnouncementBody)
        edtTriggerText = findViewById(R.id.edtTriggerText)
        edtTriggerUrl = findViewById(R.id.edtTriggerUrl)
        tvImageStatus = findViewById(R.id.tvImageStatus)
        spinnerTargetAudience = findViewById(R.id.spinnerTargetAudience)
        btnSendPromotions = findViewById(R.id.btnSendPromotions)

        val audiences = arrayOf("Global (All Users)", "Admins Only")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audiences)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetAudience.adapter = adapter

        findViewById<Button>(R.id.btnAttachImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnSendPromotions.setOnClickListener {
            sendPromotion()
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "image.jpg"
    }

    private fun sendPromotion() {
        val notifTitle = edtNotifTitle.text.toString().trim()
        val notifBody = edtNotifBody.text.toString().trim()
        val annTitle = edtAnnouncementTitle.text.toString().trim()
        val annBody = edtAnnouncementBody.text.toString().trim()
        val triggerText = edtTriggerText.text.toString().trim()
        val triggerUrl = edtTriggerUrl.text.toString().trim()

        if (notifTitle.isEmpty() || notifBody.isEmpty() || annTitle.isEmpty() || annBody.isEmpty()) {
            ToastHelper.showToast(this, "Please fill all required fields (Titles and Bodies).")
            return
        }

        btnSendPromotions.isEnabled = false
        btnSendPromotions.text = "Sending..."

        if (selectedImageUri != null) {
            btnSendPromotions.text = "Uploading Image..."
            uploadImage { success ->
                if (success) {
                    btnSendPromotions.text = "Sending Data..."
                    processSend(notifTitle, notifBody, annTitle, annBody, triggerText, triggerUrl)
                } else {
                    btnSendPromotions.isEnabled = true
                    btnSendPromotions.text = "Send Promotion"
                    ToastHelper.showToast(this, "Image upload failed.")
                }
            }
        } else {
            processSend(notifTitle, notifBody, annTitle, annBody, triggerText, triggerUrl)
        }
    }

    private fun uploadImage(callback: (Boolean) -> Unit) {
        val fileName = "promotions/${System.currentTimeMillis()}.jpg"
        val ref = FirebaseStorage.getInstance().reference.child(fileName)
        
        ref.putFile(selectedImageUri!!)
            .addOnSuccessListener {
                ref.downloadUrl.addOnSuccessListener { uri ->
                    uploadedImageUrl = uri.toString()
                    callback(true)
                }.addOnFailureListener {
                    callback(false)
                }
            }
            .addOnFailureListener {
                callback(false)
            }
    }

    private fun processSend(notifTitle: String, notifBody: String, annTitle: String, annBody: String, triggerText: String, triggerUrl: String) {
        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        
        // 1. Write to Announcements
        val annData = hashMapOf(
            "timestamp" to timestamp,
            "title" to annTitle,
            "content" to annBody,
            "imageUrl" to uploadedImageUrl,
            "triggerText" to triggerText,
            "triggerUrl" to triggerUrl
        )
        
        db.collection("announcements").document(timestamp.toString()).set(annData)
            .addOnSuccessListener {
                // 2. Write to Push Notifications
                val audience = spinnerTargetAudience.selectedItem.toString()
                val adminOnly = audience == "Admins Only"
                
                val pushData = hashMapOf(
                    "title" to notifTitle,
                    "message" to notifBody,
                    "imageUrl" to uploadedImageUrl,
                    "triggerText" to triggerText,
                    "triggerUrl" to triggerUrl,
                    "adminOnly" to adminOnly,
                    "timestamp" to timestamp
                )
                
                db.collection("global_pushes").document(timestamp.toString()).set(pushData)
                    .addOnSuccessListener {
                        ToastHelper.showToast(this, "Promotion Sent Successfully!")
                        finish()
                    }
                    .addOnFailureListener { e ->
                        btnSendPromotions.isEnabled = true
                        btnSendPromotions.text = "Send Promotion"
                        ToastHelper.showToast(this, "Failed to send push: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                btnSendPromotions.isEnabled = true
                btnSendPromotions.text = "Send Promotion"
                ToastHelper.showToast(this, "Failed to post announcement: ${e.message}")
            }
    }
}
