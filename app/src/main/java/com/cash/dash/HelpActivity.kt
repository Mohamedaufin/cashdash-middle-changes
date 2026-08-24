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


private data class DialogDimensions(
    val padding: Int,
    val fieldHeight: Int,
    val fieldMargin: Int,
    val queryHeight: Int,
    val submitHeight: Int,
    val headerMargin: Int,
    val titleMargin: Int,
    val textBodySize: Float,
    val textTitleSize: Float,
    val imagesBottomMargin: Int
)

data class ImageSlotViews(
    val slot: ViewGroup,
    val frame: ViewGroup,
    val preview: ImageView,
    val plus: ImageView,
    val trash: ImageButton,
    val eye: ImageView,
    val viewText: TextView,
    val tvProgress: TextView? = null
)

class HelpActivity : ThemedActivity() {

    private val selectedImageUris = mutableListOf<Uri>()
    private val contactUploadedUrls = mutableMapOf<Uri, String>()
    private val contactUploadProgress = mutableMapOf<Uri, Int>()
    private var isWaitingForUploads = false
    private var activeDialog: Dialog? = null



    private val pickerChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val intentData = result.data
            val selectedUri = intentData?.data
            if (selectedUri != null) {
                if (selectedImageUris.size < 4) {
                    selectedImageUris.add(selectedUri)
                    uploadContactImage(selectedUri)
                    updateDialogImageSlots()
                }
            } else {
                val bitmap = intentData?.extras?.get("data") as? android.graphics.Bitmap
                if (bitmap != null && selectedImageUris.size < 4) {
                    try {
                        val file = java.io.File(externalCacheDir, "support_img_${System.currentTimeMillis()}.jpg")
                        val out = java.io.FileOutputStream(file)
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                        out.flush()
                        out.close()
                        val uri = Uri.fromFile(file)
                        selectedImageUris.add(uri)
                        uploadContactImage(uri)
                        updateDialogImageSlots()
                    } catch (e: Exception) {
                        ToastHelper.showToast(this, "Failed to save camera image: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isWaitingForUploads", isWaitingForUploads)
        outState.putBoolean("hasActiveDialog", activeDialog != null)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isWaitingForUploads = savedInstanceState.getBoolean("isWaitingForUploads", isWaitingForUploads)
        if (savedInstanceState.getBoolean("hasActiveDialog", false)) {
            showContactDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }



        findViewById<TextView>(R.id.btnContactUs).setOnClickListener {
            startActivity(Intent(this, ContactSupportActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
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
        selectedImageUris.clear()
        contactUploadedUrls.clear()
        contactUploadProgress.clear()
        isWaitingForUploads = false

        adjustDialogLayoutForScreenSize(dialog)

        val tvName = dialog.findViewById<TextView>(R.id.tvContactName)
        val tvTime = dialog.findViewById<TextView>(R.id.tvContactTime)
        val tvEmail = dialog.findViewById<TextView>(R.id.tvContactEmail)
        val edtSubject = dialog.findViewById<EditText>(R.id.edtContactSubject)
        val edtQuery = dialog.findViewById<EditText>(R.id.edtContactQuery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnContactSubmit)
        
        updateDialogImageSlots()

        // Load User Data
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "User") ?: "User"
        val email = prefs.getString("user_email", "No Email") ?: "No Email"

        // Get Formatted Time
        val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
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

            isWaitingForUploads = true
            updateDialogSubmitButton()
            checkAndSubmitIfWaiting()
        }
        
        dialog.setOnDismissListener {
            selectedImageUris.clear()
            contactUploadedUrls.clear()
            contactUploadProgress.clear()
            isWaitingForUploads = false
            activeDialog = null
        }

        dialog.show()
    }

    private fun uploadContactImage(uri: Uri) {
        contactUploadProgress[uri] = 0
        updateDialogSubmitButton()

        val storageRef = FirebaseStorage.getInstance().reference
        val imageRef = storageRef.child("support_attachments/${System.currentTimeMillis()}_contact.jpg")

        val uploadTask = imageRef.putFile(uri)
        uploadTask.addOnProgressListener { taskSnapshot ->
                if (!selectedImageUris.contains(uri)) {
                    uploadTask.cancel()
                    return@addOnProgressListener
                }
                val percent = if (taskSnapshot.totalByteCount > 0) {
                    (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                } else 0
                contactUploadProgress[uri] = percent
                updateDialogSubmitButton()
                
                val index = selectedImageUris.indexOf(uri)
                if (index != -1) {
                    val dialog = activeDialog
                    val tvProgress = when(index) {
                        0 -> dialog?.findViewById<TextView>(R.id.tvProgress1)
                        1 -> dialog?.findViewById<TextView>(R.id.tvProgress2)
                        2 -> dialog?.findViewById<TextView>(R.id.tvProgress3)
                        3 -> dialog?.findViewById<TextView>(R.id.tvProgress4)
                        else -> null
                    }
                    if (tvProgress != null) {
                        if (percent < 100) {
                            tvProgress.visibility = android.view.View.VISIBLE
                            tvProgress.text = "$percent%"
                        } else {
                            tvProgress.visibility = android.view.View.GONE
                        }
                    }
                }
            }
            .addOnSuccessListener {
                if (!selectedImageUris.contains(uri)) return@addOnSuccessListener
                imageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    contactUploadedUrls[uri] = downloadUri.toString()
                    contactUploadProgress.remove(uri)
                    updateDialogSubmitButton()
                    
                    val index = selectedImageUris.indexOf(uri)
                    if (index != -1) {
                        val dialog = activeDialog
                        val tvProgress = when(index) {
                            0 -> dialog?.findViewById<TextView>(R.id.tvProgress1)
                            1 -> dialog?.findViewById<TextView>(R.id.tvProgress2)
                            2 -> dialog?.findViewById<TextView>(R.id.tvProgress3)
                            3 -> dialog?.findViewById<TextView>(R.id.tvProgress4)
                            else -> null
                        }
                        tvProgress?.visibility = android.view.View.GONE
                    }
                    
                    checkAndSubmitIfWaiting()
                }.addOnFailureListener { e ->
                    contactUploadProgress.remove(uri)
                    isWaitingForUploads = false
                    updateDialogSubmitButton()
                    
                    val index = selectedImageUris.indexOf(uri)
                    if (index != -1) {
                        val dialog = activeDialog
                        val tvProgress = when(index) {
                            0 -> dialog?.findViewById<TextView>(R.id.tvProgress1)
                            1 -> dialog?.findViewById<TextView>(R.id.tvProgress2)
                            2 -> dialog?.findViewById<TextView>(R.id.tvProgress3)
                            3 -> dialog?.findViewById<TextView>(R.id.tvProgress4)
                            else -> null
                        }
                        tvProgress?.visibility = android.view.View.GONE
                    }
                    
                    ToastHelper.showToast(this, "Failed to get URL: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                if (!selectedImageUris.contains(uri)) return@addOnFailureListener
                contactUploadProgress.remove(uri)
                isWaitingForUploads = false
                updateDialogSubmitButton()
                
                val index = selectedImageUris.indexOf(uri)
                if (index != -1) {
                    val dialog = activeDialog
                    val tvProgress = when(index) {
                        0 -> dialog?.findViewById<TextView>(R.id.tvProgress1)
                        1 -> dialog?.findViewById<TextView>(R.id.tvProgress2)
                        2 -> dialog?.findViewById<TextView>(R.id.tvProgress3)
                        3 -> dialog?.findViewById<TextView>(R.id.tvProgress4)
                        else -> null
                    }
                    tvProgress?.visibility = android.view.View.GONE
                }
                
                ToastHelper.showToast(this, "Upload failed: ${e.message}")
            }
    }

    private fun updateDialogSubmitButton() {
        val dialog = activeDialog ?: return
        val btnSubmit = dialog.findViewById<Button>(R.id.btnContactSubmit) ?: return

        val pendingCount = contactUploadProgress.size
        if (pendingCount > 0) {
            btnSubmit.text = "Uploading Media..."
            btnSubmit.isEnabled = false // Disabled during upload to prevent double-submit
            btnSubmit.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
        } else {
            if (isWaitingForUploads) {
                btnSubmit.text = "Submitting..."
                btnSubmit.isEnabled = false
                btnSubmit.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
            } else {
                btnSubmit.text = "Submit"
                btnSubmit.isEnabled = true
                btnSubmit.backgroundTintList = null
            }
        }
    }

    private fun checkAndSubmitIfWaiting() {
        if (!isWaitingForUploads) return
        val pendingCount = contactUploadProgress.size
        if (pendingCount > 0) return // Still uploading

        val dialog = activeDialog ?: return
        val name = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_name", "User") ?: "User"
        val email = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", "No Email") ?: "No Email"
        val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
        val currentTime = sdf.format(Date())

        val subject = dialog.findViewById<EditText>(R.id.edtContactSubject).text.toString().trim()
        val query = dialog.findViewById<EditText>(R.id.edtContactQuery).text.toString().trim()

        val urls = selectedImageUris.mapNotNull { contactUploadedUrls[it] }
        val firstUrl = urls.firstOrNull()
        val remainingUrls = if (urls.size > 1) urls.drop(1) else emptyList()

        submitQueryToFirestore(name, currentTime, email, subject, query, firstUrl, remainingUrls)
        dialog.dismiss()
    }

    private fun updateDialogImageSlots() {
        val dialog = activeDialog ?: return
        
        val slotViews = listOf(
            ImageSlotViews(
                dialog.findViewById(R.id.slotImage1),
                dialog.findViewById(R.id.frameImage1),
                dialog.findViewById(R.id.imgPreview1),
                dialog.findViewById(R.id.imgPlus1),
                dialog.findViewById(R.id.btnTrash1),
                dialog.findViewById(R.id.imgEye1),
                dialog.findViewById(R.id.tvView1),
                dialog.findViewById(R.id.tvProgress1)
            ),
            ImageSlotViews(
                dialog.findViewById(R.id.slotImage2),
                dialog.findViewById(R.id.frameImage2),
                dialog.findViewById(R.id.imgPreview2),
                dialog.findViewById(R.id.imgPlus2),
                dialog.findViewById(R.id.btnTrash2),
                dialog.findViewById(R.id.imgEye2),
                dialog.findViewById(R.id.tvView2),
                dialog.findViewById(R.id.tvProgress2)
            ),
            ImageSlotViews(
                dialog.findViewById(R.id.slotImage3),
                dialog.findViewById(R.id.frameImage3),
                dialog.findViewById(R.id.imgPreview3),
                dialog.findViewById(R.id.imgPlus3),
                dialog.findViewById(R.id.btnTrash3),
                dialog.findViewById(R.id.imgEye3),
                dialog.findViewById(R.id.tvView3),
                dialog.findViewById(R.id.tvProgress3)
            ),
            ImageSlotViews(
                dialog.findViewById(R.id.slotImage4),
                dialog.findViewById(R.id.frameImage4),
                dialog.findViewById(R.id.imgPreview4),
                dialog.findViewById(R.id.imgPlus4),
                dialog.findViewById(R.id.btnTrash4),
                dialog.findViewById(R.id.imgEye4),
                dialog.findViewById(R.id.tvView4),
                dialog.findViewById(R.id.tvProgress4)
            )
        )

        for (i in 0..3) {
            val views = slotViews[i]
            if (i < selectedImageUris.size) {
                views.slot.visibility = android.view.View.VISIBLE
                views.preview.visibility = android.view.View.VISIBLE
                com.bumptech.glide.Glide.with(this@HelpActivity).load(selectedImageUris[i]).override(800).thumbnail(0.1f).into(views.preview)
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
                    updateDialogImageSlots()
                    updateDialogSubmitButton()
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
                views.tvProgress?.visibility = android.view.View.GONE

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
                views.tvProgress?.visibility = android.view.View.GONE
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

        val imgView = com.github.chrisbanes.photoview.PhotoView(this)
        imgView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        imgView.scaleType = ImageView.ScaleType.FIT_CENTER
        com.bumptech.glide.Glide.with(this).load(uri).into(imgView)

        val closeBtn = ImageButton(this)
        val btnParams = FrameLayout.LayoutParams(
            (56 * resources.displayMetrics.density).toInt(),
            (56 * resources.displayMetrics.density).toInt()
        )
        btnParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        btnParams.topMargin = (48 * resources.displayMetrics.density).toInt()
        btnParams.marginEnd = (24 * resources.displayMetrics.density).toInt()
        closeBtn.layoutParams = btnParams

        val isWhite = ThemeHelper.isWhiteTheme(this)
        val tintColor = if (isWhite) Color.BLACK else Color.WHITE
        
        closeBtn.background = null
        closeBtn.setImageResource(R.drawable.ic_close)
        closeBtn.setColorFilter(tintColor)
        closeBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        val padding = (14 * resources.displayMetrics.density).toInt()
        closeBtn.setPadding(padding, padding, padding, padding)

        closeBtn.setOnClickListener { dialog.dismiss() }
                val gestureDetector = android.view.GestureDetector(imgView.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                dialog.dismiss()
                return true
            }
        })
        imgView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
        container.setOnClickListener { dialog.dismiss() }

        container.addView(imgView)
        container.addView(closeBtn)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun adjustDialogLayoutForScreenSize(dialog: Dialog) {
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / density

        val rootLayout = dialog.findViewById<LinearLayout>(R.id.dialogRootLayout)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvContactTitle)
        val tvName = dialog.findViewById<TextView>(R.id.tvContactName)
        val tvTime = dialog.findViewById<TextView>(R.id.tvContactTime)
        val tvEmail = dialog.findViewById<TextView>(R.id.tvContactEmail)
        val edtSubject = dialog.findViewById<EditText>(R.id.edtContactSubject)
        val edtQuery = dialog.findViewById<EditText>(R.id.edtContactQuery)
        val tvAddImagesHeader = dialog.findViewById<TextView>(R.id.tvAddImagesHeader)
        val layoutImagesContainer = dialog.findViewById<LinearLayout>(R.id.layoutImagesContainer)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnContactSubmit)

        // Set dimensions based on screen height to prevent scrollbars and overflow
        val dims = when {
            screenHeightDp < 600 -> {
                DialogDimensions(12, 38, 6, 75, 45, 6, 12, 12f, 16f, 18)
            }
            screenHeightDp < 720 -> {
                DialogDimensions(16, 44, 8, 90, 50, 8, 16, 13f, 18f, 24)
            }
            else -> {
                DialogDimensions(22, 52, 10, 115, 55, 10, 22, 14f, 20f, 35)
            }
        }

        // Apply margins/paddings
        val padPx = (dims.padding * density).toInt()
        rootLayout?.setPadding(padPx, padPx, padPx, padPx)

        fun applyParams(view: android.view.View?, height: Int, bottomMargin: Int) {
            val params = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            params.height = if (height >= 0) (height * density).toInt() else height
            params.bottomMargin = (bottomMargin * density).toInt()
            view.layoutParams = params
        }

        applyParams(tvTitle, ViewGroup.LayoutParams.WRAP_CONTENT, dims.titleMargin)
        applyParams(tvName, dims.fieldHeight, dims.fieldMargin)
        applyParams(tvTime, dims.fieldHeight, dims.fieldMargin)
        applyParams(tvEmail, dims.fieldHeight, dims.fieldMargin)
        applyParams(edtSubject, dims.fieldHeight, dims.fieldMargin)
        applyParams(edtQuery, dims.queryHeight, dims.fieldMargin)
        applyParams(tvAddImagesHeader, ViewGroup.LayoutParams.WRAP_CONTENT, dims.headerMargin)
        applyParams(layoutImagesContainer, ViewGroup.LayoutParams.WRAP_CONTENT, dims.imagesBottomMargin)
        applyParams(btnSubmit, dims.submitHeight, 0)

        // Apply text sizes
        tvTitle?.textSize = dims.textTitleSize
        tvName?.textSize = dims.textBodySize
        tvTime?.textSize = dims.textBodySize
        tvEmail?.textSize = dims.textBodySize
        edtSubject?.textSize = dims.textBodySize
        edtQuery?.textSize = dims.textBodySize
        tvAddImagesHeader?.textSize = dims.textBodySize
    }

    private fun submitQueryToFirestore(name: String, time: String, email: String, subject: String, query: String, imageUrl: String?, imageUrls: List<String>) {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        val timestamp = System.currentTimeMillis()

        // Build final query string with [Attachment:] markers appended
        val allUrls = mutableListOf<String>()
        if (imageUrl != null) allUrls.add(imageUrl)
        allUrls.addAll(imageUrls)
        val queryWithAttachments = if (allUrls.isNotEmpty()) {
            val markers = allUrls.joinToString("\n") { "[Attachment: $it]" }
            if (query.isNotEmpty()) "$query\n$markers" else markers
        } else query

        // Also save to Firestore for persistence and display in NotificationActivity
        // 🔥 offline-first queueing: we set needs_admin_email to true so the cloud function catches it when internet returns
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
        // Support email is dispatched server-side by the onSupportQuery Cloud Function,
        // which fires on the needs_admin_email flag written above. The old
        // cashdashWebhook endpoint was unauthenticated and has been removed.
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}


