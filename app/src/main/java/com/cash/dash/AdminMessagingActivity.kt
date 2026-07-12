package com.cash.dash

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class AdminMessagingActivity : ThemedActivity() {

    private var isAnnouncement = false
    private var currentTab = TabType.NONE
    private val selectedEmails = mutableSetOf<String>()
    private val allUsers = mutableListOf<Pair<String, String>>() // Email, Display Name
    private val filteredUsers = mutableListOf<Pair<String, String>>()
    
    private val selectedImageUris = mutableListOf<Uri>()
    private val uploadedImageUrls = mutableListOf<String>()
    private var isImageUploading = false
    private var pendingSendAction: (() -> Unit)? = null
    
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val maxAllowed = if (isAnnouncement) 5 else 1
            var addedCount = 0
            for (uri in uris) {
                if (selectedImageUris.size < maxAllowed) {
                    selectedImageUris.add(uri)
                    uploadedImageUrls.add("")
                    val index = selectedImageUris.size - 1
                    startBackgroundUpload(index)
                    addedCount++
                } else {
                    ToastHelper.showToast(this@AdminMessagingActivity, "Maximum $maxAllowed photos allowed")
                    break
                }
            }
            if (addedCount > 0) {
                updateMediaStrip()
            }
        }
    }

    enum class TabType { NONE, GLOBAL, ADMIN, USER, AGE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_messaging)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        isAnnouncement = intent.getBooleanExtra("isAnnouncement", false)

        val tvHeaderTitle = findViewById<TextView>(R.id.tvHeaderTitle)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val tvTargetDesc = findViewById<TextView>(R.id.tvTargetDesc)
        val edtMessageTitle = findViewById<EditText>(R.id.edtMessageTitle)
        val edtMessageBody = findViewById<EditText>(R.id.edtMessageBody)

        tvHeaderTitle.text = if (isAnnouncement) "New Announcement" else "New Push Notification"
        
        edtMessageTitle.hint = if (isAnnouncement) "Announcement Title" else "Notification Title"
        edtMessageBody.hint = if (isAnnouncement) "Write your announcement here..." else "Write your notification here..."

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupTabs()
        setupUserSearch()
        
        val btnSendInline = findViewById<Button>(R.id.btnSendInline)
        
        val sendAction = View.OnClickListener {
            val title = edtMessageTitle.text.toString().trim()
            val body = edtMessageBody.text.toString().trim()
            
            if (title.isEmpty() || body.isEmpty()) {
                ToastHelper.showToast(this, "Please fill in both fields")
                return@OnClickListener
            }

            if (currentTab == TabType.NONE) {
                ToastHelper.showToast(this, "Please select a target audience.")
                return@OnClickListener
            }

            when (currentTab) {
                TabType.NONE -> {} // Handled above
                TabType.GLOBAL -> confirmSend("Global", "Send to all users?", title, body)
                TabType.ADMIN -> confirmSend("Admin", "Send to Admins only?", title, body)
                TabType.USER, TabType.AGE -> {
                    if (selectedEmails.isEmpty()) {
                        ToastHelper.showToast(this, "Please select at least one user")
                        return@OnClickListener
                    }
                    val typeStr = if (currentTab == TabType.AGE) "Age Specific" else "User Specific"
                    confirmSend(typeStr, "Send to ${selectedEmails.size} user(s)?", title, body)
                }
            }
        }
        
        btnSend.setOnClickListener(sendAction)
        btnSendInline.setOnClickListener(sendAction)

        findViewById<TextView>(R.id.btnFetchUsers).setOnClickListener {
            fetchAgeSpecificUsers()
        }
        
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

        // Pre-fetch all users in background
        fetchAllUsers()
        handleRetriggerIntent()
        updateMediaStrip()
    }
    
    private fun startBackgroundUpload(index: Int) {
        val uri = selectedImageUris[index]
        isImageUploading = true
        
        val fileName = "promotions/${System.currentTimeMillis()}_$index.jpg"
        val ref = FirebaseStorage.getInstance().reference.child(fileName)
        
        ref.putFile(uri).addOnProgressListener { snapshot ->
            val progress = if (snapshot.totalByteCount > 0) {
                (100.0 * snapshot.bytesTransferred / snapshot.totalByteCount).toInt()
            } else 0
            val btnSend = findViewById<Button>(R.id.btnSend)
            val btnSendInline = findViewById<Button>(R.id.btnSendInline)
            btnSend.text = "Uploading image... ($progress/100%)"
            btnSendInline.text = "Uploading image... ($progress/100%)"
        }.addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUrl ->
                if (index < uploadedImageUrls.size) {
                    uploadedImageUrls[index] = downloadUrl.toString()
                }
                checkAllUploadsDone()
            }
        }.addOnFailureListener {
            ToastHelper.showToast(this, "Failed to upload image")
            checkAllUploadsDone()
        }
    }

    private fun checkAllUploadsDone() {
        // Only done if size matches and no empty strings remain
        if (uploadedImageUrls.size == selectedImageUris.size && uploadedImageUrls.all { it.isNotEmpty() }) {
            isImageUploading = false
            val btnSend = findViewById<Button>(R.id.btnSend)
            val btnSendInline = findViewById<Button>(R.id.btnSendInline)
            
            if (pendingSendAction != null) {
                btnSend.text = "Sending Data..."
                btnSendInline.text = "Sending Data..."
                pendingSendAction?.invoke()
                pendingSendAction = null
            } else {
                btnSend.text = "Send"
                btnSendInline.text = "Send"
            }
        }
    }

    private fun showFullscreenImagePreview(model: Any) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val closeBtn = ImageButton(this).apply {
            val size = (48 * resources.displayMetrics.density).toInt()
            val margin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, margin, margin, 0)
            }
            setBackgroundResource(android.R.color.transparent)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(android.graphics.Color.WHITE)
            setOnClickListener { dialog.dismiss() }
        }
        val frame = FrameLayout(this).apply {
            addView(imageView)
            addView(closeBtn)
        }
        Glide.with(this).load(model).into(imageView)
        dialog.setContentView(frame)
        dialog.show()
    }

    private fun updateMediaStrip() {
        val llMediaStrip = findViewById<LinearLayout>(R.id.llMediaStrip)
        llMediaStrip.removeAllViews()
        
        val maxAllowed = if (isAnnouncement) 5 else 1
        val density = resources.displayMetrics.density
        
        for (i in selectedImageUris.indices) {
            val uri = selectedImageUris[i]
            val uploadedUrl = uploadedImageUrls.getOrNull(i) ?: ""
            
            val outerFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((66 * density).toInt(), (66 * density).toInt()).apply {
                    marginEnd = (8 * density).toInt()
                }
                clipChildren = false
                clipToPadding = false
            }
            
            val imageBox = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams((58 * density).toInt(), (58 * density).toInt()).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                }
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(R.attr.inputBackground, tv, true)
                background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
                clipToOutline = true
                setOnClickListener {
                    showFullscreenImagePreview(uri)
                }
            }
            
            val imgView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glide.with(this).load(uri).into(imgView)
            imageBox.addView(imgView)
            
            val btnDelete = ImageButton(this).apply {
                layoutParams = FrameLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                }
                setBackgroundResource(android.R.color.transparent)
                setImageResource(R.drawable.ic_trash)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                    selectedImageUris.removeAt(i)
                    if (i < uploadedImageUrls.size) {
                        uploadedImageUrls.removeAt(i)
                    }
                    if (selectedImageUris.isEmpty()) {
                        isImageUploading = false
                    }
                    updateMediaStrip()
                }
            }
            
            outerFrame.addView(imageBox)
            outerFrame.addView(btnDelete)
            
            llMediaStrip.addView(outerFrame)
        }
        
        if (selectedImageUris.size < maxAllowed) {
            val addBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((58 * density).toInt(), (58 * density).toInt()).apply {
                    gravity = android.view.Gravity.BOTTOM
                    topMargin = (8 * density).toInt() // to align with the outer frames
                }
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(R.attr.inputBackground, tv, true)
                background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
                clipToOutline = true
                setOnClickListener {
                    pickImageLauncher.launch("image/*")
                }
            }
            
            val plusIcon = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                    gravity = android.view.Gravity.CENTER
                }
                setImageResource(R.drawable.ic_plus_vector)
            }
            addBox.addView(plusIcon)
            llMediaStrip.addView(addBox)
        }
    }

    private fun handleRetriggerIntent() {
        if (!intent.getBooleanExtra("is_retrigger", false)) return

        val title = intent.getStringExtra("msg_title") ?: ""
        val body = intent.getStringExtra("msg_body") ?: ""
        if (title.isNotEmpty()) findViewById<EditText>(R.id.edtMessageTitle).setText(title)
        if (body.isNotEmpty()) findViewById<EditText>(R.id.edtMessageBody).setText(body)

        val imageUrlsExtra = intent.getStringArrayListExtra("imageUrls")
        if (imageUrlsExtra != null && imageUrlsExtra.isNotEmpty()) {
            for (url in imageUrlsExtra) {
                if (url.isNotEmpty()) {
                    selectedImageUris.add(Uri.parse(url))
                    uploadedImageUrls.add(url)
                }
            }
        } else {
            val singleImageUrl = intent.getStringExtra("imageUrl")
            if (!singleImageUrl.isNullOrEmpty()) {
                selectedImageUris.add(Uri.parse(singleImageUrl))
                uploadedImageUrls.add(singleImageUrl)
            }
        }

        val tabTypeStr = intent.getStringExtra("tabType") ?: "NONE"
        val tabGlobal = findViewById<TextView>(R.id.tabGlobal)
        when (tabTypeStr) {
            "GLOBAL" -> tabGlobal.performClick()
            "ADMIN" -> findViewById<TextView>(R.id.tabAdmin).performClick()
            "USER" -> findViewById<TextView>(R.id.tabUser).performClick()
            "AGE" -> findViewById<TextView>(R.id.tabAge).performClick()
        }
    }

    private fun setupTabs() {
        val tabGlobal = findViewById<TextView>(R.id.tabGlobal)
        val tabAdmin = findViewById<TextView>(R.id.tabAdmin)
        val tabUser = findViewById<TextView>(R.id.tabUser)
        val tabAge = findViewById<TextView>(R.id.tabAge)

        val layoutAgeSpecific = findViewById<View>(R.id.layoutAgeSpecific)
        val layoutUserList = findViewById<View>(R.id.layoutUserList)
        val tvTargetDesc = findViewById<TextView>(R.id.tvTargetDesc)
        val btnSend = findViewById<Button>(R.id.btnSend)

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
                
                val isWhite = ThemeHelper.isWhiteTheme(this@AdminMessagingActivity)
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
                
                btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                findViewById<Button>(R.id.btnSendInline).backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            } else {
                btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
                findViewById<Button>(R.id.btnSendInline).backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
            }

            // Update UI based on tab
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

            val btnSendInline = findViewById<Button>(R.id.btnSendInline)
            val layoutBottomBar = findViewById<View>(R.id.layoutBottomBar)
            
            // Update Descriptions & Button
            when (tab) {
                TabType.NONE -> {
                    tvTargetDesc.text = "Please select a target audience."
                    btnSend.text = "Select Audience"
                    btnSendInline.text = "Select Audience"
                    btnSendInline.visibility = View.VISIBLE
                    layoutBottomBar.visibility = View.GONE
                }
                TabType.GLOBAL -> {
                    tvTargetDesc.text = "This will be sent to all users."
                    btnSend.text = "Send Globally"
                    btnSendInline.text = "Send Globally"
                    btnSendInline.visibility = View.VISIBLE
                    layoutBottomBar.visibility = View.GONE
                }
                TabType.ADMIN -> {
                    tvTargetDesc.text = "This will be sent to administrators only."
                    btnSend.text = "Send to Admins"
                    btnSendInline.text = "Send to Admins"
                    btnSendInline.visibility = View.VISIBLE
                    layoutBottomBar.visibility = View.GONE
                }
                TabType.USER -> {
                    tvTargetDesc.text = "Select specific users to send."
                    btnSend.text = "Send to Selected Users"
                    btnSendInline.visibility = View.GONE
                    layoutBottomBar.visibility = View.VISIBLE
                }
                TabType.AGE -> {
                    tvTargetDesc.text = "Filter users by age range."
                    btnSend.text = "Send to Selected Users"
                    btnSendInline.visibility = View.GONE
                    layoutBottomBar.visibility = View.VISIBLE
                }
            }
        }

        tabGlobal.setOnClickListener { selectTab(TabType.GLOBAL) }
        tabAdmin.setOnClickListener { selectTab(TabType.ADMIN) }
        tabUser.setOnClickListener { selectTab(TabType.USER) }
        tabAge.setOnClickListener { selectTab(TabType.AGE) }
        
        // Fix initial state styling properly — only call NONE default if not restoring
        if (currentTab == TabType.NONE) {
            selectTab(TabType.NONE)
        } else {
            selectTab(currentTab)
        }
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
                
                val cb = CheckBox(this@AdminMessagingActivity).apply {
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
                
                val tv = TextView(this@AdminMessagingActivity).apply {
                    text = "$displayName - $email"
                    setTextColor(ThemeHelper.resolveColorAttr(this@AdminMessagingActivity, R.attr.textPrimaryColor))
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

    private fun confirmSend(type: String, message: String, title: String, body: String) {
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
            val proceedWithSend = {
                if (isAnnouncement) {
                    sendAnnouncement(title, body)
                } else {
                    sendPushNotification(title, body)
                }
            }
            
            if (isImageUploading || uploadedImageUrls.size < selectedImageUris.size || uploadedImageUrls.any { it.isEmpty() }) {
                pendingSendAction = proceedWithSend
                val btnSend = findViewById<Button>(R.id.btnSend)
                val btnSendInline = findViewById<Button>(R.id.btnSendInline)
                btnSend.text = "Uploading images..."
                btnSendInline.text = "Uploading images..."
            } else {
                proceedWithSend()
            }
        }
        btnNo.setOnClickListener { confirmDialog.dismiss() }
        confirmDialog.show()
    }

    private fun sendAnnouncement(title: String, body: String) {
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnSendInline = findViewById<Button>(R.id.btnSendInline)
        btnSend.isEnabled = false
        btnSendInline.isEnabled = false
        btnSend.text = "Sending..."
        btnSendInline.text = "Sending..."

        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        
        val data = hashMapOf<String, Any>(
            "subject" to title,
            "query" to body,
            "reply" to "[ANNOUNCEMENT]",
            "status" to "resolved",
            "timestamp" to timestamp,
            "time" to timeStr,
            "read" to false,
            "adminOnly" to (currentTab == TabType.ADMIN),
            "promo_id" to timestamp.toString()
        )
        if (uploadedImageUrls.isNotEmpty()) {
            data["imageUrls"] = uploadedImageUrls.toList()
        }

        if (currentTab == TabType.USER || currentTab == TabType.AGE) {
            data["targetEmails"] = selectedEmails.toList()
        }

        db.collection("announcements").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Announcement Sent!")
                    logAction("Announcement", title, body, timestamp)
                    finish()
                }          .addOnFailureListener {
                ToastHelper.showToast(this, "Failed to send: ${it.message}")
                btnSend.isEnabled = true
                btnSendInline.isEnabled = true
                btnSend.text = "Send"
                btnSendInline.text = "Send"
            }
    }

    private fun sendPushNotification(title: String, body: String) {
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnSendInline = findViewById<Button>(R.id.btnSendInline)
        btnSend.isEnabled = false
        btnSendInline.isEnabled = false
        btnSend.text = "Sending..."
        btnSendInline.text = "Sending..."

        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

        if (currentTab == TabType.GLOBAL || currentTab == TabType.ADMIN) {
            val data = hashMapOf<String, Any>(
                "title" to title,
                "message" to body,
                "timestamp" to timestamp,
                "promo_id" to timestamp.toString(),
                "time" to timeStr,
                "adminOnly" to (currentTab == TabType.ADMIN)
            )
            if (uploadedImageUrls.isNotEmpty()) {
                data["imageUrl"] = uploadedImageUrls[0]
            }

            db.collection("global_pushes").document(timestamp.toString()).set(data)
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Push Notification Sent!")
                    logAction("Push Notification", title, body, timestamp)
                    finish()
                }
                .addOnFailureListener {
                    ToastHelper.showToast(this, "Failed to send: ${it.message}")
                    btnSend.isEnabled = true
                    btnSendInline.isEnabled = true
                    btnSend.text = "Send"
                    btnSendInline.text = "Send"
                }
        } else {
            val batch = db.batch()
            selectedEmails.forEachIndexed { index, email ->
                val data = hashMapOf<String, Any>(
                    "email" to email,
                    "title" to title,
                    "message" to body,
                    "timestamp" to timestamp + index,
                    "promo_id" to timestamp.toString()
                )
                if (uploadedImageUrls.isNotEmpty()) {
                    data["imageUrl"] = uploadedImageUrls[0]
                }
                val docRef = db.collection("user_pushes").document()
                batch.set(docRef, data)
            }

            batch.commit()
                .addOnSuccessListener {
                    ToastHelper.showToast(this, "Notifications sent to ${selectedEmails.size} user(s)!")
                    logAction("User Push Notification", title, body, timestamp)
                    finish()
                }
                .addOnFailureListener { e ->
                    ToastHelper.showToast(this, "Failed to send: ${e.message}")
                    btnSend.isEnabled = true
                    btnSendInline.isEnabled = true
                    btnSend.text = "Send"
                    btnSendInline.text = "Send"
                }
        }
    }

    private fun logAction(baseType: String, title: String, content: String, timestamp: Long) {
        val type = when (currentTab) {
            TabType.GLOBAL -> "Global $baseType"
            TabType.ADMIN -> "Admin $baseType"
            TabType.USER -> "User Specific $baseType"
            TabType.AGE -> "Age Specific $baseType"
            TabType.NONE -> "Unknown $baseType"
        }
        
        val db = FirebaseFirestore.getInstance()
        val logData = hashMapOf<String, Any>(
            "timestamp" to timestamp,
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
            }
        }

        // Save payload so Retrigger button appears in admin logs
        val payload = hashMapOf<String, Any>(
            "msg_title" to title,
            "msg_body" to content,
            "tabType" to currentTab.name,
            "isAnnouncement" to isAnnouncement
        )
        if (uploadedImageUrls.isNotEmpty()) {
            if (isAnnouncement) {
                payload["imageUrls"] = uploadedImageUrls.toList()
            } else {
                payload["imageUrl"] = uploadedImageUrls[0]
            }
        }
        if (currentTab == TabType.USER || currentTab == TabType.AGE) {
            payload["selectedEmails"] = selectedEmails.toList()
        }
        logData["payload"] = payload

        db.collection("admin_logs").document(timestamp.toString()).set(logData)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentTab", currentTab.name)
        outState.putBoolean("isAnnouncement", isAnnouncement)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val tabName = savedInstanceState.getString("currentTab", TabType.NONE.name) ?: TabType.NONE.name
        currentTab = try { TabType.valueOf(tabName) } catch (e: Exception) { TabType.NONE }
        isAnnouncement = savedInstanceState.getBoolean("isAnnouncement", false)
        // Re-run setupTabs which will now use the restored currentTab
        setupTabs()
    }
}
