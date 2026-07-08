package com.cash.dash

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import com.bumptech.glide.Glide
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.*

data class LinkChip(val text: String, val url: String)

class AdminPromotionsActivity : ThemedActivity() {

    enum class TabType { NONE, GLOBAL, ADMIN, USER, AGE }
    
    private var currentTab = TabType.NONE
    private val allUsers = mutableListOf<Pair<String, String>>()
    private val filteredUsers = mutableListOf<Pair<String, String>>()
    private val selectedEmails = mutableSetOf<String>()

    private lateinit var edtNotifTitle: EditText
    private lateinit var edtNotifBody: EditText
    private lateinit var edtTriggerUrlTitle: EditText
    private lateinit var edtAnnouncementTitle: EditText
    private lateinit var edtAnnouncementBody: EditText
    private lateinit var tvAddLinksLabel: TextView
    private lateinit var edtTriggerUrl: EditText

    private lateinit var btnSendPromotions: Button

    private lateinit var chipGroupLinks: ChipGroup

    private var selectedImageUri: Uri? = null
    private var uploadedImageUrl: String = ""
    
    private var currentUploadTask: com.google.firebase.storage.UploadTask? = null
    private var isImageUploading = false
    private var isImageUploadFailed = false
    private var imageUploadProgress = 0
    private var pendingSendAction: (() -> Unit)? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            uploadedImageUrl = ""
            updateImageUI()
            startBackgroundUpload()
        }
    }

    private fun updateImageUI() {
        val imgPreview = findViewById<ImageView>(R.id.imgPreview)
        val imgPlus = findViewById<ImageView>(R.id.imgPlus)
        val btnDeleteImage = findViewById<View>(R.id.btnDeleteImage)
        val btnViewImage = findViewById<View>(R.id.btnViewImage)

        if (selectedImageUri != null || uploadedImageUrl.isNotEmpty()) {
            imgPreview.visibility = View.VISIBLE
            imgPlus.visibility = View.GONE
            btnDeleteImage.visibility = View.VISIBLE
            btnViewImage.visibility = View.VISIBLE
            if (selectedImageUri != null) {
                Glide.with(this).load(selectedImageUri).into(imgPreview)
            } else {
                Glide.with(this).load(uploadedImageUrl).into(imgPreview)
            }
        } else {
            imgPreview.visibility = View.GONE
            imgPlus.visibility = View.VISIBLE
            btnDeleteImage.visibility = View.GONE
            btnViewImage.visibility = View.GONE
            imgPreview.setImageURI(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_promotions)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.header)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val headerView = findViewById<View>(R.id.header)
            headerView?.setPadding(
                headerView.paddingLeft,
                systemBars.top + (10 * resources.displayMetrics.density).toInt(),
                headerView.paddingRight,
                headerView.paddingBottom
            )
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        edtNotifTitle = findViewById(R.id.edtNotifTitle)
        edtNotifBody = findViewById(R.id.edtNotifBody)
        edtTriggerUrlTitle = findViewById(R.id.edtTriggerUrlTitle)
        edtAnnouncementTitle = findViewById(R.id.edtAnnouncementTitle)
        edtAnnouncementBody = findViewById(R.id.edtAnnouncementBody)
        
        val spanFilter = android.text.InputFilter { source, start, end, dest, dstart, dend ->
            val spannable = dest as? android.text.Spannable
            if (spannable != null) {
                val spans = spannable.getSpans(0, dest.length, android.text.style.URLSpan::class.java).filter { span ->
                    val s = spannable.getSpanStart(span)
                    val e = spannable.getSpanEnd(span)
                    (dstart < e && dend > s)
                }
                if (spans.isNotEmpty()) {
                    val span = spans[0]
                    val spanStart = spannable.getSpanStart(span)
                    val spanEnd = spannable.getSpanEnd(span)

                    if (!(dstart <= spanStart && dend >= spanEnd)) {
                        edtAnnouncementBody.post {
                            edtAnnouncementBody.setSelection(spanStart, spanEnd)
                        }
                        return@InputFilter dest.subSequence(dstart, dend)
                    }
                }
            }
            null
        }
        edtAnnouncementBody.filters = arrayOf(spanFilter)
        
        tvAddLinksLabel = findViewById(R.id.tvAddLinksLabel)

        edtTriggerUrl = findViewById(R.id.edtTriggerUrl)

        btnSendPromotions = findViewById(R.id.btnSendPromotions)

        chipGroupLinks = findViewById(R.id.chipGroupLinks)
        refreshLinkChips()

        edtAnnouncementBody.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tvAddLinksLabel.visibility = View.VISIBLE
                chipGroupLinks.visibility = View.VISIBLE
            }
        }
        
        edtAnnouncementBody.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val layout = edtAnnouncementBody.layout ?: return@setOnTouchListener false
                val touchX = (event.x - edtAnnouncementBody.totalPaddingLeft + edtAnnouncementBody.scrollX)
                val touchY = (event.y - edtAnnouncementBody.totalPaddingTop + edtAnnouncementBody.scrollY).toInt()

                val line = layout.getLineForVertical(touchY)
                val off = layout.getOffsetForHorizontal(line, touchX)

                val spannable = edtAnnouncementBody.text as? android.text.Spannable
                if (spannable != null) {
                    val links = spannable.getSpans(off, off, android.text.style.URLSpan::class.java)
                    if (links.isNotEmpty()) {
                        val span = links[0]
                        val start = spannable.getSpanStart(span)
                        val end = spannable.getSpanEnd(span)

                        // Only intercept if touch is precisely within the link's horizontal pixel bounds
                        val linkStartX = layout.getPrimaryHorizontal(start)
                        val linkEndX = layout.getPrimaryHorizontal(end)
                        val lineForStart = layout.getLineForOffset(start)
                        val lineForEnd = layout.getLineForOffset(end)

                        if (line in lineForStart..lineForEnd && touchX >= linkStartX - 12f && touchX <= linkEndX + 12f) {
                            val text = spannable.substring(start, end)
                            showLinkDialog(start, end, span.url, text)
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }

        findViewById<View>(R.id.frameImage).setOnClickListener {
            if (selectedImageUri == null && uploadedImageUrl.isEmpty()) {
                pickImageLauncher.launch("image/*")
            } else {
                showFullscreenImagePreview(selectedImageUri ?: uploadedImageUrl)
            }
        }

        findViewById<View>(R.id.btnDeleteImage).setOnClickListener {
            selectedImageUri = null
            uploadedImageUrl = ""
            currentUploadTask?.cancel()
            isImageUploading = false
            isImageUploadFailed = false
            pendingSendAction = null
            if (btnSendPromotions.text.toString().startsWith("Uploading image")) {
                btnSendPromotions.isEnabled = true
                btnSendPromotions.text = "Send Promotion"
            }
            updateImageUI()
        }

        findViewById<View>(R.id.btnViewImage).setOnClickListener {
            val model = selectedImageUri ?: if (uploadedImageUrl.isNotEmpty()) uploadedImageUrl else null
            model?.let { showFullscreenImagePreview(it) }
        }

        updateImageUI()

        setupTabs()
        setupUserSearch()
        
        val cbSelectAll = findViewById<CheckBox>(R.id.cbSelectAll)
        val checkboxColor = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.WHITE
        cbSelectAll.buttonTintList = android.content.res.ColorStateList.valueOf(checkboxColor)
        
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedEmails.addAll(filteredUsers.map { it.first })
            } else {
                selectedEmails.clear()
            }
            refreshUserCheckboxes()
        }
        
        findViewById<TextView>(R.id.btnFetchUsers).setOnClickListener {
            fetchAgeSpecificUsers()
        }

        btnSendPromotions.setOnClickListener {
            val notifTitle = edtNotifTitle.text.toString().trim()
            val notifBody = edtNotifBody.text.toString().trim()
            val annTitle = edtAnnouncementTitle.text.toString().trim()
            val annBody = edtAnnouncementBody.text.toString().trim()



            if (currentTab == TabType.NONE) {
                ToastHelper.showToast(this, "Please select a target audience.")
                return@setOnClickListener
            }

            when (currentTab) {
                TabType.NONE -> {} // Handled above
                TabType.GLOBAL -> confirmSend("Global", "Send promotion to all users?", notifTitle)
                TabType.ADMIN -> confirmSend("Admin", "Send promotion to Admins only?", notifTitle)
                TabType.USER, TabType.AGE -> {
                    if (selectedEmails.isEmpty()) {
                        ToastHelper.showToast(this, "Please select at least one user")
                        return@setOnClickListener
                    }
                    val typeStr = if (currentTab == TabType.AGE) "Age Specific" else "User Specific"
                    confirmSend(typeStr, "Send promotion to ${selectedEmails.size} user(s)?", notifTitle)
                }
            }
        }
        
        fetchAllUsers()
        handleRetriggerIntent()
    }
    
    private fun handleRetriggerIntent() {
        if (!intent.getBooleanExtra("is_retrigger", false)) return

        edtNotifTitle.setText(intent.getStringExtra("notifTitle") ?: "")
        edtNotifBody.setText(intent.getStringExtra("notifBody") ?: "")
        edtTriggerUrlTitle.setText(intent.getStringExtra("triggerUrlTitle") ?: "")
        edtAnnouncementTitle.setText(intent.getStringExtra("annTitle") ?: "")
        
        var annBodyStr = intent.getStringExtra("annBody") ?: ""
        if (annBodyStr.isNotEmpty()) {
            annBodyStr = annBodyStr.replace("&nbsp;<a href=\"[^\"]*\"><img src=\"ic_external_link\"/></a>".toRegex(), "")
            annBodyStr = annBodyStr.replace("&nbsp;<img src=\"ic_external_link\"/>".toRegex(), "")
            annBodyStr = annBodyStr.replace("&nbsp;<font color=\"#2196F3\">\u2197</font>".toRegex(), "")
            val htmlWithBr = annBodyStr.replace("\n", "<br>")
            edtAnnouncementBody.setText(android.text.Html.fromHtml(htmlWithBr, android.text.Html.FROM_HTML_MODE_LEGACY))
        } else {
            edtAnnouncementBody.setText("")
        }
        edtTriggerUrl.setText(intent.getStringExtra("triggerUrl") ?: "")
        
        val imgUrl = intent.getStringExtra("uploadedImageUrl")
        if (!imgUrl.isNullOrEmpty()) {
            uploadedImageUrl = imgUrl
            val imgPreview = findViewById<ImageView>(R.id.imgPreview)
            val imgPlus = findViewById<ImageView>(R.id.imgPlus)
            val btnDeleteImage = findViewById<View>(R.id.btnDeleteImage)
            val btnViewImage = findViewById<View>(R.id.btnViewImage)
            
            com.bumptech.glide.Glide.with(this).load(imgUrl).into(imgPreview)
            imgPreview.visibility = View.VISIBLE
            imgPlus.visibility = View.GONE
            btnDeleteImage.visibility = View.VISIBLE
            btnViewImage.visibility = View.VISIBLE
        }

        val tabTypeStr = intent.getStringExtra("tabType") ?: "NONE"
        val tab = try { TabType.valueOf(tabTypeStr) } catch(e: Exception) { TabType.NONE }
        
        val tabGlobal = findViewById<TextView>(R.id.tabGlobal)
        when (tab) {
            TabType.GLOBAL -> tabGlobal.performClick()
            TabType.ADMIN -> findViewById<TextView>(R.id.tabAdmin).performClick()
            TabType.USER -> findViewById<TextView>(R.id.tabUser).performClick()
            TabType.AGE -> findViewById<TextView>(R.id.tabAge).performClick()
            else -> {}
        }
        
        if (tab == TabType.AGE) {
            findViewById<EditText>(R.id.edtMinAge).setText(intent.getStringExtra("minAge") ?: "")
            findViewById<EditText>(R.id.edtMaxAge).setText(intent.getStringExtra("maxAge") ?: "")
        }

        val emailsList = intent.getStringArrayListExtra("selectedEmails")
        if (emailsList != null && emailsList.isNotEmpty()) {
            selectedEmails.addAll(emailsList)
        }
        
        // Note: selectedEmails might take a second to visibly check on the UI if fetchAllUsers is still running
        // so we wait slightly before refreshing checkboxes
        findViewById<View>(R.id.layoutUserList).postDelayed({ refreshUserCheckboxes() }, 1000)
    }

    private fun setupTabs() {
        val tabGlobal = findViewById<TextView>(R.id.tabGlobal)
        val tabAdmin = findViewById<TextView>(R.id.tabAdmin)
        val tabUser = findViewById<TextView>(R.id.tabUser)
        val tabAge = findViewById<TextView>(R.id.tabAge)

        val layoutAgeSpecific = findViewById<View>(R.id.layoutAgeSpecific)
        val layoutUserList = findViewById<View>(R.id.layoutUserList)
        val tvTargetDesc = findViewById<TextView>(R.id.tvTargetDesc)
        
        fun selectTab(tab: TabType) {
            if (currentTab != tab) {
                selectedEmails.clear()
                val cbSelectAll = findViewById<CheckBox>(R.id.cbSelectAll)
                if (cbSelectAll != null) cbSelectAll.isChecked = false
            }
            currentTab = tab
            
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(R.attr.roundBackground, typedValue, true)
            val bgResId = typedValue.resourceId
            
            val tabs = listOf(tabGlobal, tabAdmin, tabUser, tabAge)
            val defaultBg = androidx.core.content.ContextCompat.getDrawable(this, bgResId)
            for (t in tabs) {
                t.background = defaultBg?.constantState?.newDrawable()?.mutate()
                t.backgroundTintList = null
                t.setTextColor(ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor))
                t.invalidate()
            }
            
            if (tab != TabType.NONE) {
                val selectedView = when (tab) {
                    TabType.GLOBAL -> tabGlobal
                    TabType.ADMIN -> tabAdmin
                    TabType.USER -> tabUser
                    TabType.AGE -> tabAge
                    TabType.NONE -> tabGlobal
                }
                
                val isWhite = ThemeHelper.isWhiteTheme(this)
                val colorStr = when (tab) {
                    TabType.GLOBAL -> if (isWhite) "#008000" else "#4CAF50"
                    TabType.ADMIN -> if (isWhite) "#D32F2F" else "#FF4D4D"
                    TabType.USER -> if (isWhite) "#CD8500" else "#FFA500"
                    TabType.AGE -> if (isWhite) "#0047AB" else "#00C2FF"
                    TabType.NONE -> "#555555"
                }
                val color = android.graphics.Color.parseColor(colorStr)
                
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor(color)
                drawable.cornerRadius = 100f * resources.displayMetrics.density
                
                selectedView.background = drawable
                selectedView.backgroundTintList = null
                selectedView.setTextColor(android.graphics.Color.WHITE)
                
                btnSendPromotions.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            } else {
                btnSendPromotions.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
            }

            layoutAgeSpecific.visibility = if (tab == TabType.AGE) View.VISIBLE else View.GONE
            layoutUserList.visibility = if (tab == TabType.USER || tab == TabType.AGE) View.VISIBLE else View.GONE
            
            if (tab == TabType.USER) {
                filteredUsers.clear()
                filteredUsers.addAll(allUsers)
                refreshUserCheckboxes()
                findViewById<EditText>(R.id.edtSearchUsers).visibility = View.VISIBLE
            } else if (tab == TabType.AGE) {
                filteredUsers.clear()
                refreshUserCheckboxes()
                findViewById<EditText>(R.id.edtSearchUsers).visibility = View.GONE
            }

            when (tab) {
                TabType.NONE -> {
                    tvTargetDesc.text = "Please select a target audience."
                    btnSendPromotions.text = "Select Audience"
                }
                TabType.GLOBAL -> {
                    tvTargetDesc.text = "This will be sent to all users."
                    btnSendPromotions.text = "Send Promotion Globally"
                }
                TabType.ADMIN -> {
                    tvTargetDesc.text = "This will be sent to administrators only."
                    btnSendPromotions.text = "Send Promotion to Admins"
                }
                TabType.USER -> {
                    tvTargetDesc.text = "Select specific users to send."
                    btnSendPromotions.text = "Send Promotion to Selected Users"
                }
                TabType.AGE -> {
                    tvTargetDesc.text = "Filter users by age range."
                    btnSendPromotions.text = "Send Promotion to Selected Users"
                }
            }
        }

        tabGlobal.setOnClickListener { selectTab(TabType.GLOBAL) }
        tabAdmin.setOnClickListener { selectTab(TabType.ADMIN) }
        tabUser.setOnClickListener { selectTab(TabType.USER) }
        tabAge.setOnClickListener { selectTab(TabType.AGE) }
        
        selectTab(TabType.NONE)
    }

    private fun setupUserSearch() {
        val edtSearch = findViewById<EditText>(R.id.edtSearchUsers)
        edtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase()
                filteredUsers.clear()
                if (query.isEmpty()) {
                    filteredUsers.addAll(allUsers)
                } else {
                    filteredUsers.addAll(allUsers.filter {
                        it.first.lowercase().contains(query) || it.second.lowercase().contains(query)
                    })
                }
                refreshUserCheckboxes()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun fetchAllUsers() {
        FirebaseFirestore.getInstance().collection("users").get()
            .addOnSuccessListener { querySnapshot ->
                allUsers.clear()
                for (doc in querySnapshot.documents) {
                    val email = doc.id
                    val profileName = doc.getString("name")
                    val displayName = if (!profileName.isNullOrEmpty()) profileName else email.substringBefore("@")
                    allUsers.add(Pair(email, displayName))
                }
                allUsers.sortBy { it.second.lowercase() }
                
                if (currentTab == TabType.USER) {
                    filteredUsers.clear()
                    filteredUsers.addAll(allUsers)
                    refreshUserCheckboxes()
                }
            }
    }

    private fun fetchAgeSpecificUsers() {
        val minAgeStr = findViewById<EditText>(R.id.edtMinAge).text.toString().trim()
        val maxAgeStr = findViewById<EditText>(R.id.edtMaxAge).text.toString().trim()
        
        val minAge = minAgeStr.toIntOrNull() ?: 0
        val maxAge = maxAgeStr.toIntOrNull() ?: 999
        
        if (minAge > maxAge) {
            ToastHelper.showToast(this, "Min age cannot be greater than max age")
            return
        }

        val btnFetch = findViewById<TextView>(R.id.btnFetchUsers)
        btnFetch.text = "Fetching..."
        btnFetch.isEnabled = false
        
        filteredUsers.clear()
        
        FirebaseFirestore.getInstance().collection("users").get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val dob = doc.getString("dob")
                    if (!dob.isNullOrEmpty()) {
                        val age = calculateAge(dob)
                        if (age in minAge..maxAge) {
                            val email = doc.id
                            val profileName = doc.getString("name")
                            val displayName = if (!profileName.isNullOrEmpty()) profileName else email.substringBefore("@")
                            filteredUsers.add(Pair(email, displayName))
                        }
                    }
                }
                filteredUsers.sortBy { it.second.lowercase() }
                refreshUserCheckboxes()
                ToastHelper.showToast(this, "Found ${filteredUsers.size} users")
                btnFetch.text = "Fetch Users"
                btnFetch.isEnabled = true
            }
            .addOnFailureListener {
                ToastHelper.showToast(this, "Failed to fetch users")
                btnFetch.text = "Fetch Users"
                btnFetch.isEnabled = true
            }
    }

    private fun refreshUserCheckboxes() {
        val layoutUsersInner = findViewById<LinearLayout>(R.id.layoutUsersInner)
        val tvEmptyList = findViewById<TextView>(R.id.tvEmptyList)
        
        layoutUsersInner.removeAllViews()
        
        if (filteredUsers.isEmpty()) {
            tvEmptyList.visibility = View.VISIBLE
            return
        }
        
        tvEmptyList.visibility = View.GONE
        val density = resources.displayMetrics.density
        val checkboxColor = if (ThemeHelper.isWhiteTheme(this)) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.WHITE
        val checkboxTintList = android.content.res.ColorStateList.valueOf(checkboxColor)

        for (target in filteredUsers) {
            val email = target.first
            val displayName = target.second
            
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                
                val cb = CheckBox(this@AdminPromotionsActivity).apply {
                    buttonTintList = checkboxTintList
                    isChecked = selectedEmails.contains(email)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedEmails.add(email)
                        } else {
                            selectedEmails.remove(email)
                        }
                    }
                }
                
                val tv = TextView(this@AdminPromotionsActivity).apply {
                    text = "$displayName - $email"
                    setTextColor(ThemeHelper.resolveColorAttr(this@AdminPromotionsActivity, R.attr.textPrimaryColor))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding((8 * density).toInt(), 0, 0, 0)
                }

                addView(cb)
                addView(tv)
                
                setOnClickListener {
                    cb.isChecked = !cb.isChecked
                }
            }
            layoutUsersInner.addView(layout)
        }
    }

    private fun calculateAge(dobString: String): Int {
        try {
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
            val dob = sdf.parse(dobString) ?: return 0
            val today = java.util.Calendar.getInstance()
            val dobCal = java.util.Calendar.getInstance()
            dobCal.time = dob
            
            var age = today.get(java.util.Calendar.YEAR) - dobCal.get(java.util.Calendar.YEAR)
            if (today.get(java.util.Calendar.DAY_OF_YEAR) < dobCal.get(java.util.Calendar.DAY_OF_YEAR)) {
                age--
            }
            return age
        } catch (e: Exception) {
            return 0
        }
    }

    private fun confirmSend(type: String, message: String, title: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
        val confirmDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.tvConfirmTitle).text = "$type Send"
        dialogView.findViewById<TextView>(R.id.tvConfirmMessage).text = message
        
        val btnYes = dialogView.findViewById<Button>(R.id.btnConfirmAction)
        val btnNo = dialogView.findViewById<Button>(R.id.btnConfirmCancel)

        btnYes.text = "Send"
        btnYes.setTextColor(ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor))
        
        btnYes.setOnClickListener {
            confirmDialog.dismiss()
            sendPromotion()
        }
        btnNo.setOnClickListener { confirmDialog.dismiss() }
        confirmDialog.show()
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

    private fun refreshLinkChips() {
        chipGroupLinks.removeAllViews()

        // Add the "+" add chip
        val addChip = android.widget.TextView(this).apply {
            text = "+ Add Link"
            textSize = 14f
            setPadding((12 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt())
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 50f * resources.displayMetrics.density
                setStroke((1f * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#2196F3"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showLinkDialog() }
        }
        chipGroupLinks.addView(addChip)
    }

    private fun showLinkDialog(spanStart: Int = -1, spanEnd: Int = -1, existingUrl: String = "", existingText: String = "") {
        val dialogView = layoutInflater.inflate(R.layout.dialog_link_chip, null)

        val edtUrl = dialogView.findViewById<android.widget.EditText>(R.id.edtLinkUrl)
        val edtText = dialogView.findViewById<android.widget.EditText>(R.id.edtLinkText)
        val tvPreview = dialogView.findViewById<android.widget.TextView>(R.id.tvLinkPreview)
        val btnDelete = dialogView.findViewById<android.view.View>(R.id.btnLinkDelete)

        if (existingUrl.isNotEmpty()) edtUrl.setText(existingUrl)
        if (existingText.isNotEmpty()) edtText.setText(existingText)

        val updatePreview = {
            val label = edtText.text.toString().trim().ifEmpty { "Link" }
            val spannable = android.text.SpannableString(label)
            spannable.setSpan(
                android.text.style.UnderlineSpan(), 0, label.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#2196F3")),
                0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tvPreview.text = spannable
        }

        updatePreview()
        edtUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        edtText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dialogView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            edtUrl.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            edtText.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }

        dialogView.findViewById<android.view.View>(R.id.btnLinkCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        if (spanStart != -1 && spanEnd != -1) {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                val currentText = edtAnnouncementBody.text as? android.text.SpannableStringBuilder
                if (currentText != null) {
                    currentText.replace(spanStart, spanEnd, "")
                    edtAnnouncementBody.setText(currentText)
                    edtAnnouncementBody.setSelection(spanStart.coerceAtMost(currentText.length))
                }
                dialog.dismiss()
            }
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnLinkDone)?.setOnClickListener {
            val url = edtUrl.text.toString().trim()
            val text = edtText.text.toString().trim()
            if (url.isEmpty() || text.isEmpty()) {
                ToastHelper.showToast(this, "Please fill both fields")
                return@setOnClickListener
            }
            if (text.isNotEmpty() && url.isNotEmpty()) {
                val spannable = android.text.SpannableString(text)
                spannable.setSpan(android.text.style.URLSpan(url), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#2196F3")), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                
                val currentText = edtAnnouncementBody.text ?: ""
                val sStart = if (spanStart != -1) spanStart else edtAnnouncementBody.selectionStart.coerceAtLeast(0)
                val sEnd = if (spanEnd != -1) spanEnd else edtAnnouncementBody.selectionEnd.coerceAtLeast(0)
                val replacement = android.text.SpannableStringBuilder(currentText)
                val oldSpans = replacement.getSpans(sStart, sEnd, android.text.style.URLSpan::class.java)
                for (oldSpan in oldSpans) {
                    replacement.removeSpan(oldSpan)
                }
                val oldColors = replacement.getSpans(sStart, sEnd, android.text.style.ForegroundColorSpan::class.java)
                for (oldColor in oldColors) {
                    replacement.removeSpan(oldColor)
                }
                replacement.replace(sStart, sEnd, spannable)
                edtAnnouncementBody.setText(replacement)
                edtAnnouncementBody.setSelection((sStart + text.length).coerceAtMost(replacement.length))
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun sendPromotion() {
        btnSendPromotions.isEnabled = false
        btnSendPromotions.text = "Sending..."

        val notifTitle = edtNotifTitle.text.toString().trim()
        val notifBody = edtNotifBody.text.toString().trim()
        val triggerUrlTitle = edtTriggerUrlTitle.text.toString().trim()
        val annTitle = edtAnnouncementTitle.text.toString().trim()
        
        // Build raw HTML from Spannable spans for the announcement body
        val sb = StringBuilder(edtAnnouncementBody.text.toString())
        val spannable = edtAnnouncementBody.text as android.text.Spannable
        val spans = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
        // Sort from end to start so indices don't shift when replacing
        for (span in spans.sortedByDescending { spannable.getSpanStart(it) }) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val url = span.url
            val safeUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            val text = spannable.substring(start, end)
            val html = "<a href=\"$safeUrl\">$text</a>&nbsp;<a href=\"$safeUrl\"><img src=\"ic_external_link\"/></a>"
            sb.replace(start, end, html)
        }
        val annBody = sb.toString().trim()
        val triggerUrl = edtTriggerUrl.text.toString().trim()

        val hasPush = notifTitle.isNotEmpty() || notifBody.isNotEmpty()
        val hasAnn = annTitle.isNotEmpty() || annBody.isNotEmpty()

        if (!hasPush && !hasAnn) {
            ToastHelper.showToast(this, "\u26A0 Please fill either Push Notification details or Announcement details.")
            btnSendPromotions.isEnabled = true
            btnSendPromotions.text = "Send Promotion"
            return
        }

        if (hasPush && (notifTitle.isEmpty() || notifBody.isEmpty())) {
            ToastHelper.showToast(this, "\u26A0 Push Notification Title and Body are both required if sending a push.")
            btnSendPromotions.isEnabled = true
            btnSendPromotions.text = "Send Promotion"
            return
        }

        if (hasAnn && (annTitle.isEmpty() || annBody.isEmpty())) {
            ToastHelper.showToast(this, "\u26A0 Announcement Title and Body are both required if sending an announcement.")
            btnSendPromotions.isEnabled = true
            btnSendPromotions.text = "Send Promotion"
            return
        }

        if (selectedImageUri != null && uploadedImageUrl.isEmpty()) {
            if (isImageUploading) {
                btnSendPromotions.text = "Uploading image... ($imageUploadProgress/100%)"
                pendingSendAction = {
                    processSend(notifTitle, notifBody, annTitle, annBody, triggerUrl, triggerUrlTitle)
                }
            } else if (isImageUploadFailed) {
                btnSendPromotions.text = "Uploading image... (0/100%)"
                pendingSendAction = {
                    processSend(notifTitle, notifBody, annTitle, annBody, triggerUrl, triggerUrlTitle)
                }
                startBackgroundUpload()
            }
        } else {
            btnSendPromotions.text = "Sending Data..."
            processSend(notifTitle, notifBody, annTitle, annBody, triggerUrl, triggerUrlTitle)
        }
    }

    private fun handleFailedUploadDuringSend() {
        pendingSendAction = null
        btnSendPromotions.isEnabled = true
        btnSendPromotions.text = "Send Promotion"
        ToastHelper.showToast(this, "Image upload failed. Please try again.")
    }

    private fun startBackgroundUpload() {
        if (selectedImageUri == null) return
        
        currentUploadTask?.cancel()
        isImageUploading = true
        isImageUploadFailed = false
        imageUploadProgress = 0
        
        val fileName = "promotions/${System.currentTimeMillis()}.jpg"
        val ref = FirebaseStorage.getInstance().reference.child(fileName)
        
        currentUploadTask = ref.putFile(selectedImageUri!!)
        
        currentUploadTask?.addOnProgressListener { snapshot ->
            val progress = if (snapshot.totalByteCount > 0) {
                (100.0 * snapshot.bytesTransferred / snapshot.totalByteCount).toInt()
            } else 0
            imageUploadProgress = progress
            if (pendingSendAction != null) {
                btnSendPromotions.text = "Uploading image... ($progress/100%)"
            }
        }?.addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { uri ->
                uploadedImageUrl = uri.toString()
                isImageUploading = false
                isImageUploadFailed = false
                if (pendingSendAction != null) {
                    btnSendPromotions.text = "Sending Data..."
                    pendingSendAction?.invoke()
                    pendingSendAction = null
                }
            }.addOnFailureListener {
                isImageUploading = false
                isImageUploadFailed = true
                if (pendingSendAction != null) {
                    handleFailedUploadDuringSend()
                }
            }
        }?.addOnFailureListener {
            isImageUploading = false
            isImageUploadFailed = true
            if (pendingSendAction != null) {
                handleFailedUploadDuringSend()
            }
        }
    }

    private fun processSend(notifTitle: String, notifBody: String, annTitle: String, annBody: String, triggerUrl: String, triggerUrlTitle: String) {
        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        val adminOnly = (currentTab == TabType.ADMIN)

        val hasPush = notifTitle.isNotEmpty() && notifBody.isNotEmpty()
        val hasAnn = annTitle.isNotEmpty() && annBody.isNotEmpty()

        var pendingTasks = 0
        var completedTasks = 0
        var failedTasks = 0

        fun checkCompletion() {
            if (completedTasks + failedTasks == pendingTasks) {
                if (failedTasks == 0) {
                    val payload = hashMapOf<String, Any>(
                        "notifTitle" to notifTitle,
                        "notifBody" to notifBody,
                        "triggerUrlTitle" to triggerUrlTitle,
                        "annTitle" to annTitle,
                        "annBody" to annBody,
                        "triggerUrl" to triggerUrl,
                        "uploadedImageUrl" to uploadedImageUrl,
                        "tabType" to currentTab.name
                    )
                    if (currentTab == TabType.USER || currentTab == TabType.AGE) {
                        payload["selectedEmails"] = selectedEmails.toList()
                    }
                    logAction("Promotional Activity", notifTitle.ifEmpty { annTitle }, notifBody.ifEmpty { annBody }, payload)
                    ToastHelper.showToast(this, "Promotion Sent Successfully!")
                    finish()
                } else {
                    btnSendPromotions.isEnabled = true
                    btnSendPromotions.text = "Send Promotion"
                    ToastHelper.showToast(this, "Some parts of the promotion failed to send.")
                }
            }
        }

        if (hasAnn) {
            pendingTasks++
            val annData = hashMapOf<String, Any>(
                "timestamp" to timestamp,
                "subject" to annTitle,
                "query" to annBody,
                "reply" to "[ANNOUNCEMENT]",
                "status" to "resolved",
                "read" to false,
                "imageUrl" to uploadedImageUrl,
                "triggerUrl" to triggerUrl,
                "triggerText" to triggerUrlTitle,
                "adminOnly" to adminOnly,
                "time" to timeStr
            )
            if (currentTab == TabType.USER || currentTab == TabType.AGE) {
                annData["targetEmails"] = selectedEmails.toList()
            }
            db.collection("announcements").document(timestamp.toString()).set(annData)
                .addOnSuccessListener { completedTasks++; checkCompletion() }
                .addOnFailureListener { failedTasks++; checkCompletion() }
        }

        if (hasPush) {
            pendingTasks++
            if (currentTab == TabType.GLOBAL || currentTab == TabType.ADMIN) {
                val pushData = hashMapOf(
                    "title" to notifTitle,
                    "message" to notifBody,
                    "imageUrl" to uploadedImageUrl,
                    "triggerUrl" to triggerUrl,
                    "triggerText" to triggerUrlTitle,
                    "adminOnly" to adminOnly,
                    "timestamp" to timestamp,
                    "time" to timeStr
                )
                db.collection("global_pushes").document(timestamp.toString()).set(pushData)
                    .addOnSuccessListener { completedTasks++; checkCompletion() }
                    .addOnFailureListener { failedTasks++; checkCompletion() }
            } else {
                val batch = db.batch()
                selectedEmails.forEachIndexed { index, email ->
                    val data = hashMapOf(
                        "email" to email,
                        "title" to notifTitle,
                        "message" to notifBody,
                        "imageUrl" to uploadedImageUrl,
                        "triggerUrl" to triggerUrl,
                        "triggerText" to triggerUrlTitle,
                        "timestamp" to timestamp + index
                    )
                    batch.set(db.collection("user_pushes").document(), data)
                }
                batch.commit()
                    .addOnSuccessListener { completedTasks++; checkCompletion() }
                    .addOnFailureListener { failedTasks++; checkCompletion() }
            }
        }
    }

    private fun logAction(actionBaseType: String, title: String, content: String, payload: MutableMap<String, Any>? = null) {
        val type = when (currentTab) {
            TabType.GLOBAL -> "Global $actionBaseType"
            TabType.ADMIN -> "Admin $actionBaseType"
            TabType.USER -> "User Specific $actionBaseType"
            TabType.AGE -> "Age Specific $actionBaseType"
            TabType.NONE -> "Unknown $actionBaseType"
        }
        
        val db = FirebaseFirestore.getInstance()
        val logData = hashMapOf<String, Any>(
            "timestamp" to System.currentTimeMillis(),
            "actionType" to type,
            "title" to title,
            "message" to content,
            "performedBy" to (getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", "Unknown") ?: "Unknown")
        )

        if (currentTab == TabType.USER || currentTab == TabType.AGE) {
            val targetsStr = selectedEmails.map { email ->
                val name = allUsers.find { it.first == email }?.second ?: "Unknown"
                "$name - $email"
            }.joinToString(", ")
            logData["details"] = targetsStr
        }

        if (currentTab == TabType.AGE) {
            val minAge = findViewById<EditText>(R.id.edtMinAge).text.toString().trim()
            val maxAge = findViewById<EditText>(R.id.edtMaxAge).text.toString().trim()
            if (minAge.isNotEmpty() || maxAge.isNotEmpty()) {
                logData["ageRange"] = "$minAge - $maxAge"
                payload?.put("minAge", minAge)
                payload?.put("maxAge", maxAge)
            }
        }

        if (payload != null) {
            logData["payload"] = payload
        }

        db.collection("admin_logs").add(logData)
    }
    private fun showFullscreenImagePreview(model: Any) {
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
        Glide.with(this).load(model).into(imgView)

        val closeBtn = ImageView(this)
        val density = resources.displayMetrics.density
        val btnParams = FrameLayout.LayoutParams(
            (56 * density).toInt(),
            (56 * density).toInt()
        )
        btnParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        btnParams.topMargin = (24 * density).toInt()
        btnParams.marginEnd = (24 * density).toInt()
        closeBtn.layoutParams = btnParams
        
        val glassBg = android.graphics.drawable.GradientDrawable()
        glassBg.shape = android.graphics.drawable.GradientDrawable.OVAL
        glassBg.setColor(android.graphics.Color.parseColor("#66000000"))
        glassBg.setStroke(3, android.graphics.Color.parseColor("#80FFFFFF"))
        closeBtn.background = glassBg
        
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        closeBtn.setColorFilter(android.graphics.Color.RED)
        closeBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = (14 * density).toInt()
        closeBtn.setPadding(p, p, p, p)
        closeBtn.setOnClickListener { dialog.dismiss() }

        container.addView(imgView)
        container.addView(closeBtn)

        dialog.setContentView(container)
        dialog.show()
    }
}
