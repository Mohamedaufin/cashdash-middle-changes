package com.cash.dash

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ContactSupportActivity : ThemedActivity() {

    private val selectedImageUris = mutableListOf<Uri>()
    private val contactUploadedUrls = mutableMapOf<Uri, String>()
    private val contactUploadProgress = mutableMapOf<Uri, Int>()
    private var isWaitingForUploads = false

    private val pickerChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data
            val selectedUri = intentData?.data
            if (selectedUri != null) {
                if (selectedImageUris.size < 4) {
                    selectedImageUris.add(selectedUri)
                    uploadContactImage(selectedUri)
                    updateImageSlots()
                }
            } else {
                val bitmap = intentData?.extras?.get("data") as? android.graphics.Bitmap
                if (bitmap != null && selectedImageUris.size < 4) {
                    try {
                        val file = File(externalCacheDir, "support_img_${System.currentTimeMillis()}.jpg")
                        val out = FileOutputStream(file)
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                        out.flush()
                        out.close()
                        val uri = Uri.fromFile(file)
                        selectedImageUris.add(uri)
                        uploadContactImage(uri)
                        updateImageSlots()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isWaitingForUploads", isWaitingForUploads)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isWaitingForUploads = savedInstanceState.getBoolean("isWaitingForUploads", isWaitingForUploads)
        updateSubmitButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_support)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val btnSubmit = findViewById<Button>(R.id.btnContactSubmit)

        updateImageSlots()

        btnSubmit.setOnClickListener {
            val edtSubject = findViewById<EditText>(R.id.edtContactSubject)
            val edtQuery = findViewById<EditText>(R.id.edtContactQuery)
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

            isWaitingForUploads = true
            updateSubmitButton()
            checkAndSubmitIfWaiting()
        }
    }

    private fun uploadContactImage(uri: Uri) {
        contactUploadProgress[uri] = 0
        updateSubmitButton()

        val storageRef = FirebaseStorage.getInstance().reference
        val imageRef = storageRef.child("support_attachments/${System.currentTimeMillis()}_contact.jpg")

        imageRef.putFile(uri)
            .addOnProgressListener { taskSnapshot ->
                val percent = if (taskSnapshot.totalByteCount > 0) {
                    (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                } else 0
                contactUploadProgress[uri] = percent
                updateSubmitButton()
            }
            .addOnSuccessListener {
                imageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    contactUploadedUrls[uri] = downloadUri.toString()
                    contactUploadProgress.remove(uri)
                    updateSubmitButton()
                    checkAndSubmitIfWaiting()
                }
            }
            .addOnFailureListener {
                contactUploadProgress.remove(uri)
                updateSubmitButton()
                ToastHelper.showToast(this, "Failed to upload image")
            }
    }

    private fun updateSubmitButton() {
        val btnSubmit = findViewById<Button>(R.id.btnContactSubmit) ?: return
        val pendingCount = contactUploadProgress.size
        if (pendingCount > 0) {
            val progressSum = contactUploadProgress.values.sum()
            val avgProgress = progressSum / pendingCount
            btnSubmit.text = "Uploading ($avgProgress%)..."
            btnSubmit.isEnabled = false
        } else {
            if (isWaitingForUploads) {
                btnSubmit.text = "Submitting..."
                btnSubmit.isEnabled = false
            } else {
                btnSubmit.text = "Submit"
                btnSubmit.isEnabled = true
            }
        }
    }

    private fun checkAndSubmitIfWaiting() {
        if (!isWaitingForUploads) return
        val pendingCount = contactUploadProgress.size
        if (pendingCount > 0) return // Still uploading

        val name = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_name", "User") ?: "User"
        val email = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", "No Email") ?: "No Email"
        val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
        val currentTime = sdf.format(Date())

        val edtSubject = findViewById<EditText>(R.id.edtContactSubject)
        val edtQuery = findViewById<EditText>(R.id.edtContactQuery)
        val subject = edtSubject.text.toString().trim()
        val query = edtQuery.text.toString().trim()

        val urls = selectedImageUris.mapNotNull { contactUploadedUrls[it] }
        val firstUrl = urls.firstOrNull()
        val remainingUrls = if (urls.size > 1) urls.drop(1) else emptyList()

        submitQueryToFirestore(name, currentTime, email, subject, query, firstUrl, remainingUrls)
        finish()
    }

    private fun updateImageSlots() {
        val slotViews = listOf(
            ImageSlotViews(
                findViewById(R.id.slotImage1),
                findViewById(R.id.frameImage1),
                findViewById(R.id.imgPreview1),
                findViewById(R.id.imgPlus1),
                findViewById(R.id.btnTrash1),
                findViewById(R.id.imgEye1),
                findViewById(R.id.tvView1)
            ),
            ImageSlotViews(
                findViewById(R.id.slotImage2),
                findViewById(R.id.frameImage2),
                findViewById(R.id.imgPreview2),
                findViewById(R.id.imgPlus2),
                findViewById(R.id.btnTrash2),
                findViewById(R.id.imgEye2),
                findViewById(R.id.tvView2)
            ),
            ImageSlotViews(
                findViewById(R.id.slotImage3),
                findViewById(R.id.frameImage3),
                findViewById(R.id.imgPreview3),
                findViewById(R.id.imgPlus3),
                findViewById(R.id.btnTrash3),
                findViewById(R.id.imgEye3),
                findViewById(R.id.tvView3)
            ),
            ImageSlotViews(
                findViewById(R.id.slotImage4),
                findViewById(R.id.frameImage4),
                findViewById(R.id.imgPreview4),
                findViewById(R.id.imgPlus4),
                findViewById(R.id.btnTrash4),
                findViewById(R.id.imgEye4),
                findViewById(R.id.tvView4)
            )
        )

        for (i in 0..3) {
            val views = slotViews[i]
            if (i < selectedImageUris.size) {
                views.slot.visibility = android.view.View.VISIBLE
                views.preview.visibility = android.view.View.VISIBLE
                views.preview.setImageURI(selectedImageUris[i])
                views.preview.clipToOutline = true
                views.frame.clipToOutline = true
                views.plus.visibility = android.view.View.GONE
                views.trash.visibility = android.view.View.VISIBLE
                views.eye.visibility = android.view.View.VISIBLE
                views.viewText.visibility = android.view.View.VISIBLE

                views.trash.setOnClickListener {
                    val uri = selectedImageUris[i]
                    selectedImageUris.removeAt(i)
                    contactUploadedUrls.remove(uri)
                    contactUploadProgress.remove(uri)
                    updateImageSlots()
                    updateSubmitButton()
                }
                views.frame.setOnClickListener {
                    showFullscreenImagePreview(selectedImageUris[i])
                }
                views.eye.setOnClickListener {
                    showFullscreenImagePreview(selectedImageUris[i])
                }
                views.viewText.setOnClickListener {
                    showFullscreenImagePreview(selectedImageUris[i])
                }
            } else if (i == selectedImageUris.size) {
                views.slot.visibility = android.view.View.VISIBLE
                views.preview.visibility = android.view.View.GONE
                views.plus.visibility = android.view.View.VISIBLE
                views.trash.visibility = android.view.View.GONE
                views.eye.visibility = android.view.View.GONE
                views.viewText.visibility = android.view.View.GONE

                views.frame.setOnClickListener {
                    val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    val chooserIntent = Intent.createChooser(galleryIntent, "Select Image Source").apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                    pickerChooserLauncher.launch(chooserIntent)
                }
            } else {
                views.slot.visibility = android.view.View.GONE
            }
        }
    }

    private fun showFullscreenImagePreview(uri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = FrameLayout(this)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val imgView = PhotoView(this)
        imgView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        imgView.scaleType = ImageView.ScaleType.FIT_CENTER
        Glide.with(this).load(uri).into(imgView)

        val closeBtn = ImageButton(this)
        val size = (44 * resources.displayMetrics.density).toInt()
        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            topMargin = (40 * resources.displayMetrics.density).toInt()
            marginStart = (20 * resources.displayMetrics.density).toInt()
        }
        closeBtn.layoutParams = params
        closeBtn.setImageResource(R.drawable.ic_back_arrow1)
        closeBtn.background = ColorDrawable(Color.TRANSPARENT)
        closeBtn.setOnClickListener { dialog.dismiss() }

        container.addView(imgView)
        container.addView(closeBtn)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun submitQueryToFirestore(name: String, time: String, email: String, subject: String, query: String, imageUrl: String?, imageUrls: List<String>) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val timestamp = System.currentTimeMillis()

        val allUrls = mutableListOf<String>()
        if (imageUrl != null) allUrls.add(imageUrl)
        allUrls.addAll(imageUrls)
        val queryWithAttachments = if (allUrls.isNotEmpty()) {
            val markers = allUrls.joinToString("\n") { "[Attachment: $it]" }
            if (query.isNotEmpty()) "$query\n$markers" else markers
        } else query

        val db = FirebaseFirestore.getInstance()
        val notificationData = hashMapOf<String, Any>(
            "name" to name,
            "email" to email,
            "time" to time,
            "subject" to subject,
            "originalSubject" to subject,
            "query" to queryWithAttachments,
            "timestamp" to timestamp,
            "read" to false,
            "status" to "pending",
            "reply" to "Waiting for reply...",
            "needs_admin_email" to true
        )
        if (imageUrl != null) {
            notificationData["imageUrl"] = imageUrl
        }
        if (allUrls.isNotEmpty()) {
            notificationData["imageUrls"] = allUrls
        }

        val userEmail = user.email ?: getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", null) ?: return
        
        db.collection("users").document(userEmail).collection("notifications")
            .document(timestamp.toString())
            .set(notificationData)
            .addOnSuccessListener {
            }
            .addOnFailureListener {
            }

        ToastHelper.showToast(this, "Query sent! We'll notify you when we reply.")
        triggerImmediateWebhook(user.uid, name, userEmail, time, subject, queryWithAttachments, timestamp, imageUrl, allUrls)
    }

    private fun triggerImmediateWebhook(
        uid: String,
        name: String,
        email: String,
        time: String,
        subject: String,
        query: String,
        timestamp: Long,
        imageUrl: String?,
        imageUrls: List<String>
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
                    put("id", timestamp.toString())
                    put("name", name)
                    put("email", email)
                    put("time", time)
                    put("subject", subject)
                    put("query", query)
                    put("timestamp", timestamp)
                    if (imageUrl != null) {
                        put("imageUrl", imageUrl)
                    }
                    if (imageUrls.isNotEmpty()) {
                        put("imageUrls", org.json.JSONArray(imageUrls))
                    }
                }

                val os = conn.outputStream
                val writer = OutputStreamWriter(os, "UTF-8")
                writer.write(payload.toString())
                writer.flush()
                writer.close()
                os.close()

                val responseCode = conn.responseCode
                Log.d("ContactSupportActivity", "⚡ Immediate Webhook Sent. Response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("ContactSupportActivity", "❌ Immediate Webhook Failed: ${e.message}")
            }
        }
    }
}
