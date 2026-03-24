package com.cash.dash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.net.URLDecoder
import java.util.Calendar
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.ScaleGestureDetector
import androidx.core.app.NotificationCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

@OptIn(ExperimentalGetImage::class)
class ScannerActivity : AppCompatActivity(), SensorEventListener {

    private val CAMERA_REQUEST = 101
    private val GALLERY_PICK = 102
    private val PAYMENT_REQ = 500

    private lateinit var previewView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    private var camera: Camera? = null
    private var isFlashOn = false
    private var userManuallyToggled = false
    private var scannedOnce = false
    private var processing = false

    // Sensors & Gestures
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    
    // Pending transaction state for Result Tracking
    private var pendingAmount: Int = 0
    private var pendingCategory: String? = null
    private var pendingTitle: String = "UPI Payment"
    private var pendingUpi: String? = null
    private var allocationHandled: Boolean = false

    // Gestures for Swipe-Right-to-Home
    private lateinit var swipeDetector: android.view.GestureDetector

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null) {
            // Pass all events to the zoom detector
            scaleGestureDetector.onTouchEvent(ev)
            
            // Only handle swipe if it's a single finger (prevents conflicts with zoom)
            if (ev.pointerCount == 1 && swipeDetector.onTouchEvent(ev)) {
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("startActivity(Intent(this, MainActivity::class.java)); finish()"))
    override fun onBackPressed() {
        val intent = Intent(this@ScannerActivity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        val btnClose = findViewById<ImageButton>(R.id.btnCloseScanner)
        val btnFlashlight = findViewById<ImageButton>(R.id.btnFlashlight)
        val btnGallery = findViewById<ImageButton>(R.id.btnGallery)
        val btnHistory = findViewById<ImageButton>(R.id.btnHistory)
        
        // Manual Flashlight Toggle
        btnFlashlight.setOnClickListener {
            isFlashOn = !isFlashOn
            userManuallyToggled = true
            camera?.cameraControl?.enableTorch(isFlashOn)
            updateFlashlightIcon()
        }

        // Dynamic Inset Handling for Icons
        val root = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            
            // Apply margins to the top icons (all in a row)
            val closeParams = btnClose.layoutParams as FrameLayout.LayoutParams
            closeParams.topMargin = systemBars.top + 60
            btnClose.layoutParams = closeParams

            val historyParams = btnHistory.layoutParams as FrameLayout.LayoutParams
            historyParams.topMargin = systemBars.top + 60
            btnHistory.layoutParams = historyParams

            val flashParams = btnFlashlight.layoutParams as FrameLayout.LayoutParams
            flashParams.topMargin = systemBars.top + 60 // Same row as others
            // Position to the left of the history button (which is at top|end)
            flashParams.marginEnd = historyParams.marginEnd + 220 // Increased offset for clear gap from history button
            btnFlashlight.layoutParams = flashParams
            
            insets
        }
        
        previewView = findViewById(R.id.previewView)

        // Sensors & Zoom
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val state = camera?.cameraInfo?.zoomState?.value ?: return false
                val currentZoom = state.zoomRatio
                val delta = detector.scaleFactor
                
                // Block Ultrawide (anything < 1.0f) and excessive telephoto (keep it digital on main)
                // 1.0f is the primary 'normal' camera.
                val nextZoom = (currentZoom * delta).coerceIn(1.0f, 3.0f) 
                camera?.cameraControl?.setZoomRatio(nextZoom)
                return true
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else startScanner()

        btnClose.setOnClickListener {
            val intent = Intent(this@ScannerActivity, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
        btnGallery.setOnClickListener { openGallery() }
        
        btnHistory.setOnClickListener {
            val localPrefs = getSharedPreferences("LocalScanPrefs", MODE_PRIVATE)
            val lastUpi = localPrefs.getString("last_upi", null)
            if (lastUpi != null) {
                scannedOnce = true
                if (::cameraProvider.isInitialized) {
                    cameraProvider.unbindAll()
                }
                showAmountDialog(lastUpi)
            } else {
                ToastHelper.showToast(this, "No previous scan history found")
            }
        }

        swipeDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e1.x - e2.x > 150 && Math.abs(velocityX) > 150) { // Right to Left Swipe
                    val intent = Intent(this@ScannerActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    finish()
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    return true
                }
                return false
            }
        })

        // Removed destructive pullDataFromCloud race condition to preserve fast, accurate offline cache.
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // Dynamically detect if admin deleted the account from Firebase Auth
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.reload()?.addOnFailureListener { e ->
            if (e is com.google.firebase.auth.FirebaseAuthInvalidUserException) { // Account deleted or disabled
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("MoneySchedulePrefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("GraphData", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("ScannerHistory", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("LocalScanPrefs", Context.MODE_PRIVATE).edit().clear().apply()

                val intent = Intent(this, EntryActivity::class.java)
                intent.putExtra("reason", "admin_deleted")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            if (!userManuallyToggled) {
                if (lux < 5f && !isFlashOn) {
                    isFlashOn = true
                    camera?.cameraControl?.enableTorch(true)
                    updateFlashlightIcon()
                } else if (lux > 20f && isFlashOn) {
                    isFlashOn = false
                    camera?.cameraControl?.enableTorch(false)
                    updateFlashlightIcon()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateFlashlightIcon() {
        val btnFlashlight = findViewById<ImageButton>(R.id.btnFlashlight)
        btnFlashlight.setImageResource(if (isFlashOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        btnFlashlight.imageTintList = android.content.res.ColorStateList.valueOf(
            if (isFlashOn) Color.parseColor("#8BF7E6") else Color.WHITE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanner()
            } else {
                ToastHelper.showToast(this, "Camera permission is required to scan QR codes")
                finish()
            }
        }
    }

    // ------------------------------------------------------------------- GALLERY
    private fun openGallery() {
        startActivityForResult(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            GALLERY_PICK
        )
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)

        if (req == PAYMENT_REQ) {
            val response = data?.getStringExtra("response") ?: ""
            if (response.contains("SUCCESS", true)) redirectSuccess()
            else redirectFailed()
        }

        if (req == GALLERY_PICK && res == Activity.RESULT_OK) {
            data?.data?.let { scanGalleryQR(it) }
        }


    }

    private fun scanGalleryQR(uri: Uri) {
        try {
            // Use inSampleSize to prevent OOM on large images
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, options) 
            }

            // Target max 1024px for scanning
            var inSampleSize = 1
            if (options.outHeight > 1024 || options.outWidth > 1024) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= 1024 && halfWidth / inSampleSize >= 1024) {
                    inSampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize

            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            if (bmp == null) {
                toast("⚠ Could not load image")
                return
            }

            val img = InputImage.fromBitmap(bmp, 0)
            BarcodeScanning.getClient().process(img).addOnSuccessListener { codes ->
                for (b in codes) {
                    val upi = b.rawValue ?: continue
                    if (upi.contains("upi://pay")) {
                        scannedOnce = true
                        successBeep()
                        showAmountDialog(upi)
                        return@addOnSuccessListener
                    }
                }
                toast("⚠ No UPI QR found in image")
            }.addOnFailureListener {
                toast("⚠ Error scanning image")
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }
    }

    // ------------------------------------------------------------------- CAMERA
    private fun startScanner() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(
                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { scanQR(it) }

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            // Fix: Immediately apply the current intended torch state once camera is ready
            camera?.cameraControl?.enableTorch(isFlashOn)
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun scanQR(proxy: ImageProxy) {
        if (processing || scannedOnce) { proxy.close(); return }
        processing = true

        val media = proxy.image ?: return proxy.close()
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        BarcodeScanning.getClient().process(img).addOnSuccessListener { codes ->
            for (b in codes) {
                val upi = b.rawValue ?: continue
                if (upi.contains("upi://pay")) {
                    scannedOnce = true
                    cameraProvider.unbindAll()
                    successBeep()
                    shake()
                    showAmountDialog(upi)
                }
            }
        }.addOnCompleteListener { proxy.close(); processing = false }
    }

    // ------------------------------------------------------------------- DIALOG PAYMENT
    private fun showAmountDialog(upi: String) {
        // android.util.Log.d("ScannerActivity", "showAmountDialog called for: $upi")
        try {
            val name = (decode(getParam(upi,"pn")) ?: "Unknown").replace("|", "-")
            val id = (decode(getParam(upi,"pa")) ?: "Unknown").replace("|", "-")

            if (upi.contains("upi://pay")) {
                // android.util.Log.d("ScannerActivity", "Saving last_upi and syncing cloud")
                getSharedPreferences("LocalScanPrefs", MODE_PRIVATE)
                    .edit().putString("last_upi", upi).apply()
                FirestoreSyncManager.pushAllDataToCloud(this)
            }

            // android.util.Log.d("ScannerActivity", "Inflating payment bottom sheet")
            val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
            val view = layoutInflater.inflate(R.layout.layout_payment_bottom_sheet, null)
            dialog.setContentView(view)
        
        // Reset pending state
        pendingAmount = 0
        pendingCategory = null
        pendingTitle = "To: $name"
        allocationHandled = false

        val tvInfo = view.findViewById<TextView>(R.id.tvReceiverInfo)
        
        // Hide UPI ID text if it's a phone number based payment
        val isPhonePayment = !id.contains("@") && id.all { it.isDigit() }
        if (isPhonePayment) {
            tvInfo.text = "Receiver: $name"
        } else {
            tvInfo.text = "Receiver: $name\nUPI ID: $id"
        }

        val etAmount = view.findViewById<EditText>(R.id.etPaymentAmount)
        val tvAllocation = view.findViewById<TextView>(R.id.tvAllocationLabel)
        val btnChoose = view.findViewById<Button>(R.id.btnChooseAllocation)
        
        val btnCred = view.findViewById<Button>(R.id.btnPayCred)
        val btnGPay = view.findViewById<Button>(R.id.btnPayGPay)
        
        val paymentActionContainer = view.findViewById<LinearLayout>(R.id.paymentActionContainer)
        val btnPayInitiate = view.findViewById<Button>(R.id.btnPayInitiate)
        val tvWalletBalance = view.findViewById<TextView>(R.id.tvWalletBalance)

        // Set Wallet Balance
        val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
        val balance = walletPrefs.getInt("wallet_balance", 0)
        tvWalletBalance.text = "Wallet Balance: ₹$balance"

        // Show CRED and GPay for all transactions natively
        btnCred.visibility = View.VISIBLE
        btnGPay.visibility = View.VISIBLE

        tvAllocation.visibility = View.GONE
        btnChoose.visibility = View.GONE

        etAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val amt = s.toString()
                btnPayInitiate.text = if (amt.isNotEmpty()) "Pay ₹$amt" else "Pay ₹0"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnPayInitiate.setOnClickListener {
            val amtStr = etAmount.text.toString()
            if (amtStr.isEmpty() || amtStr.toIntOrNull() == 0) {
                ToastHelper.showToast(this, "Please enter an amount")
                return@setOnClickListener
            }
            showAllocationChooser(dialog, tvAllocation, btnChoose, paymentActionContainer, btnPayInitiate)
        }

        btnChoose.setOnClickListener {
            showAllocationChooser(dialog, tvAllocation, btnChoose, paymentActionContainer, btnPayInitiate)
        }

        btnCred.setOnClickListener {
            if (!allocationHandled) {
                ToastHelper.showToast(this, "Please select an allocation or skip")
                return@setOnClickListener
            }
            val amtStr = etAmount.text.toString()
            if (amtStr.isEmpty()) return@setOnClickListener
            pendingAmount = amtStr.toIntOrNull() ?: 0
            dialog.dismiss()
            payUPI(upi, amtStr, "com.dreamplug.androidapp")
        }

        btnGPay.setOnClickListener {
            if (!allocationHandled) {
                ToastHelper.showToast(this, "Please select an allocation or skip")
                return@setOnClickListener
            }
            val amtStr = etAmount.text.toString()
            if (amtStr.isEmpty()) return@setOnClickListener
            pendingAmount = amtStr.toIntOrNull() ?: 0
            dialog.dismiss()
            payUPI(upi, amtStr, "com.google.android.apps.nbu.paisa.user")
        }

        dialog.setOnDismissListener {
            scannedOnce = false
            startScanner()
        }
        
        dialog.show()
        } catch (e: Exception) {
            // android.util.Log.e("ScannerActivity", "CRASH in showAmountDialog: ${e.message}", e)
            ToastHelper.showToast(this, "⚠ Error opening payment dialog")
        }
    }

    // verifyUPI removed as per user request to remove verification logic

    private fun showAllocationChooser(parentDialog: BottomSheetDialog, label: TextView, btn: Button, paymentContainer: LinearLayout, btnPayInit: Button) {
        val chooser = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.layout_allocation_chooser_bottom_sheet, null)
        chooser.setContentView(view)

        val container = view.findViewById<LinearLayout>(R.id.allocationListContainer)
        val prefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val limitPrefs = prefs // Already using CategoryPrefs for limits
        val spentPrefs = getSharedPreferences("GraphData", MODE_PRIVATE)

        // Add "Create New Allocation" button at the very top
        val btnCreateNew = Button(this).apply {
            text = "+ Create New Allocation"
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 16f
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_3d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150
            ).apply { setMargins(0, 0, 0, 30) }
            
            setOnClickListener {
                showCreateCategoryDialog(chooser, label, btn, paymentContainer, btnPayInit)
            }
        }
        container.addView(btnCreateNew)

        val btnSkip = Button(this).apply {
            text = "Skip allocation"
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 16f
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_3d_red)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150
            ).apply { setMargins(0, 0, 0, 30) }
            
            setOnClickListener {
                pendingCategory = null
                allocationHandled = true
                label.text = "No allocation selected"
                label.visibility = View.VISIBLE
                btn.text = "Choose"
                btn.visibility = View.VISIBLE
                paymentContainer.visibility = View.VISIBLE
                btnPayInit.visibility = View.GONE
                chooser.dismiss()
            }
        }
        container.addView(btnSkip)

        val categories = prefs.getStringSet("categories", emptySet()) ?: emptySet()

        if (categories.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No allocated categories found. Set limits in Rigor Tracker first."
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(20, 20, 20, 20)
            }
            container.addView(empty)
        } else {
            for (cat in categories) {
                val row = layoutInflater.inflate(R.layout.item_rigor_category, container, false)
                val txtName = row.findViewById<TextView>(R.id.categoryName)
                val spentBar = row.findViewById<View>(R.id.spentBar)
                val progressOuter = row.findViewById<View>(R.id.progressOuter)
                val txtSpent = row.findViewById<TextView>(R.id.txtSpent)
                val txtLimit = row.findViewById<TextView>(R.id.txtLimit)

                // 🔮 AI Keyword Custom Icons
                val iconView = row.findViewById<ImageView>(R.id.categoryIcon)
                val iconRes = CategoryIconHelper.getIconForCategory(cat)
                iconView.setImageResource(iconRes)

                txtName.text = cat

                val limit = limitPrefs.getInt("LIMIT_$cat", 0)
                val spent = spentPrefs.getFloat("SPENT_$cat", 0f)

                txtSpent.text = "Spent: ₹${spent.toInt()}"
                txtLimit.text = if (limit > 0) "Limit: ₹$limit" else "Limit: —"

                val progress = if (limit > 0) (spent / limit).coerceIn(0f, 1f) else 0f

                row.post {
                    val maxWidth = progressOuter.width
                    val targetWidth = (maxWidth * progress).toInt()

                    spentBar.clearAnimation()

                    val anim = android.view.animation.ScaleAnimation(
                        0f, 1f, 1f, 1f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f
                    )
                    anim.duration = 500
                    anim.fillAfter = true

                    spentBar.startAnimation(anim)

                    spentBar.layoutParams.width = targetWidth
                    spentBar.requestLayout()

                    if (limit > 0 && spent >= limit) {
                        spentBar.setBackgroundColor(Color.RED)
                    } else {
                        spentBar.setBackgroundColor(Color.parseColor("#8BF7E6"))
                    }
                }

                row.setOnClickListener {
                    pendingCategory = cat
                    allocationHandled = true
                    label.text = "Allocated to: $cat"
                    label.visibility = View.VISIBLE
                    btn.text = "Change"
                    btn.visibility = View.VISIBLE
                    paymentContainer.visibility = View.VISIBLE
                    btnPayInit.visibility = View.GONE
                    chooser.dismiss()
                }
                container.addView(row)
            }
        }
        chooser.show()
        
        // Force the bottom sheet to fully expand so the top button isn't clipped
        val bottomSheet = chooser.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet as android.widget.FrameLayout)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun showCreateCategoryDialog(parentDialog: BottomSheetDialog, label: TextView, btn: Button, paymentContainer: LinearLayout, btnPayInit: Button) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(60, 60, 60, 50)
        box.setBackgroundResource(R.drawable.bg_transaction)

        val titleView = TextView(this).apply {
            text = "New Allocation"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        box.addView(titleView)

        val inputName = EditText(this).apply {
            hint = "Category Name (e.g. Travel)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#A8B5D1"))
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            setPadding(30,30,30,30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140).apply {
                setMargins(0, 0, 0, 30)
            }
        }
        box.addView(inputName)

        val inputLimit = EditText(this).apply {
            hint = "Monthly Limit (Optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#A8B5D1"))
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            setPadding(30,30,30,30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140).apply {
                setMargins(0, 0, 0, 50)
            }
        }
        box.addView(inputLimit)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 130, 1f).apply {
                setMargins(0, 0, 15, 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = android.widget.Button(this).apply {
            text = "Create"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 130, 1f).apply {
                setMargins(15, 0, 0, 0)
            }
            setOnClickListener {
                val catName = inputName.text.toString().trim().replace("|", "-")
                if (catName.isNotEmpty() && !catName.equals("Overall", ignoreCase = true)) {
                    val limitStr = inputLimit.text.toString().trim()
                    val newLimit = if (limitStr.isNotEmpty()) limitStr.toIntOrNull() ?: 0 else 0

                    val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
                    val totalBalance = walletPrefs.getInt("initial_balance", 0).coerceAtLeast(0)

                    val prefsCat = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
                    val set = HashSet(prefsCat.getStringSet("categories", emptySet()) ?: emptySet())

                    var currentSumOfLimits = 0
                    for (cat in set) {
                        currentSumOfLimits += prefsCat.getInt("LIMIT_$cat", 0)
                    }
                    val maxAllowed = totalBalance - currentSumOfLimits

                    if (newLimit > maxAllowed) {
                        ToastHelper.showToast(this@ScannerActivity, "Exceeds total balance! Max allowed limit: ₹$maxAllowed")
                        return@setOnClickListener
                    }

                    val editor = prefsCat.edit()
                    set.add(catName)
                    editor.putStringSet("categories", set)

                    if (newLimit > 0) {
                        editor.putInt("LIMIT_$catName", newLimit)
                    }
                    editor.apply()
                    FirestoreSyncManager.pushAllDataToCloud(this@ScannerActivity)

                    // Immediately close and reopen the bottom sheet to refresh the list
                    parentDialog.dismiss()
                    showAllocationChooser(parentDialog, label, btn, paymentContainer, btnPayInit)
                    ToastHelper.showToast(this@ScannerActivity, "Created $catName")
                    dialog.dismiss()
                } else if (catName.equals("Overall", ignoreCase = true)) {
                    ToastHelper.showToast(this@ScannerActivity, "Cannot use reserved name 'Overall'")
                }
            }
        }
        buttonContainer.addView(btnSave)
        box.addView(buttonContainer)

        dialog.show()
    }

    // ------------------------------------------------------------------- PAYMENT TRIGGER
    private fun payUPI(upi: String, amt: String, pkg: String) {
        try {
            // THE ULTIMATE P2P FALLBACK (Bypassing Security Blocks)
            // 1. We convert the Merchant QR into a P2P QR (pa & pn only) to bypass 'mc=' signature locks.
            // 2. We extract the raw `pa` precisely to preserve the "@" symbol (un-encoded).
            // 3. If pkg matches GPay, we use Intent.createChooser to bypass "Forced Intent" security checks.

            val paMatch = Regex("[?&]pa=([^&]+)").find(upi)?.groupValues?.get(1)
            val pnMatch = Regex("[?&]pn=([^&]+)").find(upi)?.groupValues?.get(1)

            if (paMatch == null || paMatch.isEmpty()) {
                ToastHelper.showToast(this, "Invalid QR: Missing UPI ID")
                return
            }

            val parsedAmt = amt.toDoubleOrNull() ?: 0.0
            val formattedAmt = String.format(java.util.Locale.US, "%.2f", parsedAmt)

            var p2pUriString = "upi://pay?pa=$paMatch&am=$formattedAmt&cu=INR"
            if (pnMatch != null && pnMatch.isNotEmpty()) {
                p2pUriString += "&pn=$pnMatch"
            }

            val baseIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(p2pUriString)
            }



            // Always explicitly set the package to target the chosen app
            baseIntent.setPackage(pkg)
            
            // Phase 54: Robust Fallback intent handling
            // Check if the target app can natively handle the upi://pay deep link
            val resolveInfo = packageManager.resolveActivity(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
            
            if (resolveInfo != null) {
                // The app officially supports this deep link, launch it natively
                startActivityForResult(baseIntent, PAYMENT_REQ)
            } else {
                // The app is installed but its intent filters are hidden/strict (common with new apps like Supermoney)
                // Fallback: Force-launch the app's main activity and attach the URI data manually
                val fallbackIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (fallbackIntent != null) {
                    fallbackIntent.action = Intent.ACTION_VIEW
                    fallbackIntent.data = android.net.Uri.parse(p2pUriString)
                    startActivityForResult(fallbackIntent, PAYMENT_REQ)
                } else {
                    ToastHelper.showToast(this, "App not installed on this device")
                }
            }
        } catch (e: Exception) {
            ToastHelper.showToast(this, "Failed to launch payment app")
        }
    }

    // ------------------------------------------------------------------- RESULT RETURN
    private fun redirectSuccess(){
        if (pendingAmount > 0) {
            deductFromWallet(pendingAmount)
            val categoryToSave = pendingCategory ?: "no choice"
            saveExpense(categoryToSave, pendingAmount, pendingTitle)
        }

        val i = Intent(this, MainActivity::class.java)
        i.putExtra("payment_status","success")
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(i)
        finish()
    }

    private fun redirectFailed(){
        
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("payment_status","failed")
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(i)
        finish()
    }

    private fun saveExpense(category: String, amount: Int, titleText: String) {
        val prefs = getSharedPreferences("GraphData", MODE_PRIVATE)
        val weeklyPrefs = getSharedPreferences("CategoryWeekData", MODE_PRIVATE)
        
        val editor = prefs.edit()
        val weekEditor = weeklyPrefs.edit()

        val cal = Calendar.getInstance()
        cal.setFirstDayOfWeek(Calendar.MONDAY)
        cal.setMinimalDaysInFirstWeek(1)
        val timestamp = cal.timeInMillis.toString()

        val dayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val monthIndex = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val weekIndex = cal.get(Calendar.WEEK_OF_MONTH) - 1

        val oldSpent = prefs.getFloat("SPENT_$category", 0f)
        editor.putFloat("SPENT_$category", oldSpent + amount)

        val dailyKey = "DAY_${weekIndex}_${dayIndex}_${monthIndex}_${year}"
        editor.putFloat(dailyKey, prefs.getFloat(dailyKey, 0f) + amount)

        val weeklyKey = "WEEK_${weekIndex}_${monthIndex}_${year}"
        editor.putFloat(weeklyKey, prefs.getFloat(weeklyKey, 0f) + amount)

        val monthlyKey = "MONTH_${monthIndex}_${year}"
        editor.putFloat(monthlyKey, prefs.getFloat(monthlyKey, 0f) + amount)

        val weekSlot = weekIndex + 1
        val categoryWeekKey = "${category}_W$weekSlot"
        val oldWeekValue = weeklyPrefs.getInt(categoryWeekKey, 0)
        weekEditor.putInt(categoryWeekKey, oldWeekValue + amount)

        val historySet = (prefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()).toMutableSet()
        historySet.add("EXP|$timestamp|$titleText|$category|$amount|$weekIndex|$dayIndex|$monthIndex|$year")
        editor.putStringSet("HISTORY_LIST", historySet)

        editor.putString("TRANS_${timestamp}_TITLE", titleText)
        editor.putString("TRANS_${timestamp}_CATEGORY", category)
        editor.putInt("TRANS_${timestamp}_AMOUNT", amount)
        editor.putInt("TRANS_${timestamp}_WEEK", weekIndex)
        editor.putInt("TRANS_${timestamp}_DAY", dayIndex)
        editor.putInt("TRANS_${timestamp}_MONTH", monthIndex)
        editor.putInt("TRANS_${timestamp}_YEAR", year)

        editor.apply()
        weekEditor.apply()
        
        FirestoreSyncManager.pushAllDataToCloud(this)
    }


    // ------------------------------------------------------------------- UTIL
    private fun getParam(t:String,k:String)=Regex("$k=([^&]*)").find(t)?.groupValues?.get(1)
    private fun decode(v:String?)=v?.let{URLDecoder.decode(it,"UTF-8")}
    private fun toast(s:String)=ToastHelper.showToast(this,s)

    private fun successBeep(){
        try{
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createOneShot(150,VibrationEffect.DEFAULT_AMPLITUDE))
            MediaPlayer.create(this,android.provider.Settings.System.DEFAULT_NOTIFICATION_URI).start()
        }catch(_:Exception){}
    }

    private fun shake(){
        val f=findViewById<View>(R.id.neonFrame)
        f.animate().translationXBy(25f).setDuration(60).withEndAction {
            f.animate().translationXBy(-50f).setDuration(60).withEndAction {
                f.animate().translationX(0f).setDuration(60)
            }
        }
    }

    private fun deductFromWallet(amt: Int) {
        val prefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
        val current = prefs.getInt("wallet_balance", 0)
        prefs.edit().putInt("wallet_balance", current - amt).apply()
    }
}
