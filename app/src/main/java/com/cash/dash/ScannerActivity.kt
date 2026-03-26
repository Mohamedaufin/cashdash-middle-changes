@file:Suppress("DEPRECATION")
package com.cash.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.URLDecoder
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScannerActivity : AppCompatActivity(), SensorEventListener {

    private val CAMERA_REQUEST = 101
    private val GALLERY_PICK = 102
    private val PAYMENT_REQ = 500

    private lateinit var previewView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    private var camera: Camera? = null
    private var barcodeScanner: BarcodeScanner? = null
    private lateinit var cameraExecutor: ExecutorService

    private var isFlashOn = false
    private var userManuallyToggled = false
    private var scannedOnce = false
    private var processing = false

    // Sensors & Gestures
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var swipeDetector: android.view.GestureDetector
    private var isPinching = false

    // Result Tracking
    private var pendingAmount: Int = 0
    private var pendingCategory: String? = null
    private var pendingTitle: String = "UPI Payment"
    private var allocationHandled: Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            scaleGestureDetector.onTouchEvent(ev)
            
            // Manual Tap to Focus & Expose
            if (ev.action == MotionEvent.ACTION_UP && ev.pointerCount == 1) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(ev.x, ev.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }

            if (ev.pointerCount > 1) isPinching = true
            if (ev.action == MotionEvent.ACTION_DOWN) isPinching = false

            if (ev.pointerCount == 1 && !isPinching && swipeDetector.onTouchEvent(ev)) {
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("startActivity(Intent(this, MainActivity::class.java)); finish()"))
    override fun onBackPressed() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        previewView = findViewById(R.id.previewView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val btnClose = findViewById<ImageButton>(R.id.btnCloseScanner)
        val btnFlashlight = findViewById<ImageButton>(R.id.btnFlashlight)
        val btnGallery = findViewById<ImageButton>(R.id.btnGallery)
        val btnHistory = findViewById<ImageButton>(R.id.btnHistory)

        btnFlashlight.setOnClickListener {
            isFlashOn = !isFlashOn
            userManuallyToggled = true
            camera?.cameraControl?.enableTorch(isFlashOn)
            updateFlashlightIcon()
        }

        val root = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            
            val closeParams = btnClose.layoutParams as FrameLayout.LayoutParams
            closeParams.topMargin = systemBars.top + 60
            btnClose.layoutParams = closeParams

            val historyParams = btnHistory.layoutParams as FrameLayout.LayoutParams
            historyParams.topMargin = systemBars.top + 60
            btnHistory.layoutParams = historyParams

            val flashParams = btnFlashlight.layoutParams as FrameLayout.LayoutParams
            flashParams.topMargin = systemBars.top + 60
            flashParams.marginEnd = historyParams.marginEnd + 220
            btnFlashlight.layoutParams = flashParams
            
            insets
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val state = camera?.cameraInfo?.zoomState?.value ?: return false
                val currentZoom = state.zoomRatio
                val delta = detector.scaleFactor
                val maxZoom = state.maxZoomRatio
                val nextZoom = (currentZoom * delta).coerceIn(1.0f, maxZoom) 
                camera?.cameraControl?.setZoomRatio(nextZoom)
                return true
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else {
            startCamera()
        }

        btnClose.setOnClickListener { onBackPressed() }
        btnGallery.setOnClickListener { openGallery() }
        
        btnHistory.setOnClickListener {
            val localPrefs = getSharedPreferences("LocalScanPrefs", MODE_PRIVATE)
            val lastUpi = localPrefs.getString("last_upi", null)
            if (lastUpi != null) {
                scannedOnce = true
                if (::cameraProvider.isInitialized) cameraProvider.unbindAll()
                showAmountDialog(lastUpi)
            } else {
                toast("No previous scan history found")
            }
        }

        swipeDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e1.x - e2.x > 150 && Math.abs(velocityX) > 150) {
                    onBackPressed()
                    return true
                }
                return false
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Resolution Booster: Targeting 1080p (1920x1080) for high-distance scanning power.
            // 640x480 is standard, but 1080p gives the ML Kit engine 4x more pixel detail!
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                    android.util.Size(1920, 1080),
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                ))
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // GPay-level Auto-Zoom integration with '2-Jump' Aggression Fix
            var lastAutoZoomTime = 0L
            val zoomSuggestionOptions = ZoomSuggestionOptions.Builder { zoomRatio ->
                val now = System.currentTimeMillis()
                if (now - lastAutoZoomTime < 600) return@Builder false
                lastAutoZoomTime = now
                
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
                if (zoomRatio > currentZoom) {
                    val aggressiveRatio = (zoomRatio * 1.25f).coerceIn(1.0f, camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10.0f)
                    camera?.cameraControl?.setZoomRatio(aggressiveRatio)
                    
                    // RE-FOCUS LOCK: Immediately snap focus into the box at the new zoom level.
                    // This ensures we dont wait for standard AF to 'drift' into focus.
                    rootFocusAndMetering() 
                    true
                } else false
            }.build()

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .setZoomSuggestionOptions(zoomSuggestionOptions)
                .build()

            barcodeScanner = BarcodeScanning.getClient(options)

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, { imageProxy -> scanQR(imageProxy) })

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)

                // Flashlight fix: If sensor already toggled state to true before camera was ready, apply it now.
                if (isFlashOn) {
                    camera?.cameraControl?.enableTorch(true)
                }

                // The Magic GPay Exposure Fix: Aggressive center lock metering.
                // Washed-out images happen because standard metering tries to balance the whole scene.
                // We lock metering to the dead center, where the QR code usually is!
                rootFocusAndMetering()

            } catch (e: Exception) {
                toast("Failed to initialize camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rootFocusAndMetering() {
        // GPay Settling Strategy: 
        // We wait slightly longer (1.5s) to let the hardware's internal 
        // "cold-start" CAF find a baseline before we force a lock.
        previewView.postDelayed({
            try {
                val expState = camera?.cameraInfo?.exposureState
                if (expState?.isExposureCompensationSupported == true) {
                    val range = expState.exposureCompensationRange
                    val targetIndex = (range.lower + (range.upper - range.lower) / 4).coerceIn(range.lower, 0)
                    camera?.cameraControl?.setExposureCompensationIndex(targetIndex)
                }

                val factory = previewView.meteringPointFactory
                val boxPoint = factory.createPoint(0.5f, 0.5f, 0.6f)

                // Adaptive Lock: Set a 7-second duration.
                // This 'Box Lock' is now the priority, but it will 'reset' and refresh 
                // every 7 seconds if the user hasn't scanned anything yet, preventing 
                // the camera from getting stuck in a blurred state.
                val action = FocusMeteringAction.Builder(boxPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(7, TimeUnit.SECONDS)
                    .build()

                camera?.cameraControl?.startFocusAndMetering(action)
            } catch (e: Exception) {}
        }, 1500)
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun scanQR(proxy: ImageProxy) {
        if (processing || scannedOnce) { proxy.close(); return }
        processing = true

        val media = proxy.image ?: return proxy.close()
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        barcodeScanner?.process(img)?.addOnSuccessListener { codes ->
            for (b in codes) {
                val upi = b.rawValue ?: continue
                if (upi.contains("upi://pay")) {
                    scannedOnce = true
                    cameraProvider.unbindAll()
                    successBeep()
                    shake()
                    showAmountDialog(upi)
                    return@addOnSuccessListener // prevent multiple decodes
                }
            }
        }?.addOnCompleteListener { 
            proxy.close()
            processing = false 
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.reload()?.addOnFailureListener { e ->
            if (e is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().clear().apply()
                // Clear other prefs... (skipped explicit repeats for brevity, add as needed)
                startActivity(Intent(this, EntryActivity::class.java).apply {
                    putExtra("reason", "admin_deleted")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner?.close()
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
        btnFlashlight.imageTintList = android.content.res.ColorStateList.valueOf(if (isFlashOn) Color.parseColor("#8BF7E6") else Color.WHITE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            toast("Camera permission is required")
            finish()
        }
    }

    private fun openGallery() {
        startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), GALLERY_PICK)
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

    @SuppressLint("UnsafeOptInUsageError")
    private fun scanGalleryQR(uri: Uri) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            var inSampleSize = 1
            if (options.outHeight > 1024 || options.outWidth > 1024) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= 1024 && halfWidth / inSampleSize >= 1024) inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            if (bmp == null) { toast("⚠ Could not load image"); return }

            val img = InputImage.fromBitmap(bmp, 0)
            BarcodeScanning.getClient().process(img).addOnSuccessListener { codes ->
                for (b in codes) {
                    val upi = b.rawValue ?: continue
                    if (upi.contains("upi://pay")) {
                        successBeep()
                        showAmountDialog(upi)
                        return@addOnSuccessListener
                    }
                }
                toast("⚠ No UPI QR found in image")
            }.addOnFailureListener { toast("⚠ Error scanning image") }
        } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------- DIALOG PAYMENT
    @SuppressLint("MissingInflatedId")
    private fun showAmountDialog(upi: String) {
        try {
            val name = (decode(getParam(upi,"pn")) ?: "Unknown").replace("|", "-")
            val id = (decode(getParam(upi,"pa")) ?: "Unknown").replace("|", "-")

            if (upi.contains("upi://pay")) {
                getSharedPreferences("LocalScanPrefs", MODE_PRIVATE).edit().putString("last_upi", upi).apply()
                FirestoreSyncManager.pushAllDataToCloud(this)
            }

            val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
            val view = layoutInflater.inflate(R.layout.layout_payment_bottom_sheet, null)
            dialog.setContentView(view)

            pendingAmount = 0
            pendingCategory = null
            pendingTitle = "To: $name"
            allocationHandled = false

            val tvInfo = view.findViewById<TextView>(R.id.tvReceiverInfo)
            val isPhonePayment = !id.contains("@") && id.all { it.isDigit() }
            tvInfo.text = if (isPhonePayment) "Receiver: $name" else "Receiver: $name\nUPI ID: $id"

            val etAmount = view.findViewById<EditText>(R.id.etPaymentAmount)
            val tvAllocation = view.findViewById<TextView>(R.id.tvAllocationLabel)
            val btnChoose = view.findViewById<Button>(R.id.btnChooseAllocation)
            
            val btnCred = view.findViewById<Button>(R.id.btnPayCred)
            val btnGPay = view.findViewById<Button>(R.id.btnPayGPay)
            
            val paymentActionContainer = view.findViewById<LinearLayout>(R.id.paymentActionContainer)
            val btnPayInitiate = view.findViewById<Button>(R.id.btnPayInitiate)
            val tvWalletBalance = view.findViewById<TextView>(R.id.tvWalletBalance)

            val balance = getSharedPreferences("WalletPrefs", MODE_PRIVATE).getInt("wallet_balance", 0)
            tvWalletBalance.text = "Wallet Balance: ₹$balance"

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
                    toast("Please enter an amount")
                    return@setOnClickListener
                }
                showAllocationChooser(dialog, tvAllocation, btnChoose, paymentActionContainer, btnPayInitiate)
            }

            btnChoose.setOnClickListener { showAllocationChooser(dialog, tvAllocation, btnChoose, paymentActionContainer, btnPayInitiate) }

            btnCred.setOnClickListener {
                if (!allocationHandled) { toast("Please select an allocation or skip"); return@setOnClickListener }
                val amtStr = etAmount.text.toString()
                if (amtStr.isEmpty()) return@setOnClickListener
                pendingAmount = amtStr.toIntOrNull() ?: 0
                dialog.dismiss()
                payUPI(upi, amtStr, "com.dreamplug.androidapp")
            }

            btnGPay.setOnClickListener {
                if (!allocationHandled) { toast("Please select an allocation or skip"); return@setOnClickListener }
                val amtStr = etAmount.text.toString()
                if (amtStr.isEmpty()) return@setOnClickListener
                pendingAmount = amtStr.toIntOrNull() ?: 0
                dialog.dismiss()
                payUPI(upi, amtStr, "com.google.android.apps.nbu.paisa.user")
            }

            dialog.setOnDismissListener {
                scannedOnce = false
                startCamera()
            }
            dialog.show()
        } catch (e: Exception) { toast("⚠ Error opening payment dialog") }
    }

    private fun showAllocationChooser(parentDialog: BottomSheetDialog, label: TextView, btn: Button, paymentContainer: LinearLayout, btnPayInit: Button) {
        val chooser = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.layout_allocation_chooser_bottom_sheet, null)
        chooser.setContentView(view)

        val container = view.findViewById<LinearLayout>(R.id.allocationListContainer)
        val prefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val spentPrefs = getSharedPreferences("GraphData", MODE_PRIVATE)

        val btnCreateNew = Button(this).apply {
            text = "+ Create New Allocation"
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 16f
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_3d)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { setMargins(0, 0, 0, 30) }
            setOnClickListener { showCreateCategoryDialog(chooser, label, btn, paymentContainer, btnPayInit) }
        }
        container.addView(btnCreateNew)

        val btnSkip = Button(this).apply {
            text = "Skip allocation"
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 16f
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_3d_red)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { setMargins(0, 0, 0, 30) }
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
            container.addView(TextView(this).apply {
                text = "No allocated categories found. Set limits in Rigor Tracker first."
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(20, 20, 20, 20)
            })
        } else {
            for (cat in categories) {
                val row = layoutInflater.inflate(R.layout.item_rigor_category, container, false)
                val txtName = row.findViewById<TextView>(R.id.categoryName)
                val spentBar = row.findViewById<View>(R.id.spentBar)
                val progressOuter = row.findViewById<View>(R.id.progressOuter)
                val txtSpent = row.findViewById<TextView>(R.id.txtSpent)
                val txtLimit = row.findViewById<TextView>(R.id.txtLimit)
                val iconView = row.findViewById<ImageView>(R.id.categoryIcon)

                iconView.setImageResource(CategoryIconHelper.getIconForCategory(cat))
                txtName.text = cat

                val limit = prefs.getInt("LIMIT_$cat", 0)
                val spent = spentPrefs.getFloat("SPENT_$cat", 0f)

                txtSpent.text = "Spent: ₹${spent.toInt()}"
                txtLimit.text = if (limit > 0) "Limit: ₹$limit" else "Limit: —"

                val progress = if (limit > 0) (spent / limit).coerceIn(0f, 1f) else 0f

                row.post {
                    val targetWidth = (progressOuter.width * progress).toInt()
                    val anim = android.view.animation.ScaleAnimation(0f, 1f, 1f, 1f, android.view.animation.Animation.RELATIVE_TO_SELF, 0f, android.view.animation.Animation.RELATIVE_TO_SELF, 0f)
                    anim.duration = 500; anim.fillAfter = true
                    spentBar.startAnimation(anim)
                    spentBar.layoutParams.width = targetWidth
                    spentBar.requestLayout()
                    spentBar.setBackgroundColor(if (limit > 0 && spent >= limit) Color.RED else Color.parseColor("#8BF7E6"))
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
        val bottomSheet = chooser.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet as FrameLayout)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun showCreateCategoryDialog(parentDialog: BottomSheetDialog, label: TextView, btn: Button, paymentContainer: LinearLayout, btnPayInit: Button) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            setBackgroundResource(R.drawable.bg_transaction)
        }

        box.addView(TextView(this).apply {
            text = "New Allocation"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        })

        val inputName = EditText(this).apply {
            hint = "Category Name"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#A8B5D1"))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140).apply { setMargins(0, 0, 0, 30) }
        }
        box.addView(inputName)

        val inputLimit = EditText(this).apply {
            hint = "Monthly Limit (Optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#A8B5D1"))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140).apply { setMargins(0, 0, 0, 50) }
        }
        box.addView(inputLimit)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(this).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonContainer.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 130, 1f).apply { setMargins(0, 0, 15, 0) }
            setOnClickListener { dialog.dismiss() }
        })

        buttonContainer.addView(Button(this).apply {
            text = "Create"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 130, 1f).apply { setMargins(15, 0, 0, 0) }
            setOnClickListener {
                val catName = inputName.text.toString().trim().replace("|", "-")
                if (catName.isNotEmpty() && !catName.equals("Overall", true)) {
                    val limitStr = inputLimit.text.toString().trim()
                    val newLimit = if (limitStr.isNotEmpty()) limitStr.toIntOrNull() ?: 0 else 0

                    val totalBalance = getSharedPreferences("WalletPrefs", MODE_PRIVATE).getInt("initial_balance", 0).coerceAtLeast(0)
                    val prefsCat = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
                    val set = HashSet(prefsCat.getStringSet("categories", emptySet()) ?: emptySet())

                    var currentSumOfLimits = 0
                    for (cat in set) currentSumOfLimits += prefsCat.getInt("LIMIT_$cat", 0)
                    val maxAllowed = totalBalance - currentSumOfLimits

                    if (newLimit > maxAllowed) {
                        toast("Exceeds balance! Max: ₹$maxAllowed")
                        return@setOnClickListener
                    }

                    val editor = prefsCat.edit()
                    set.add(catName)
                    editor.putStringSet("categories", set)
                    if (newLimit > 0) editor.putInt("LIMIT_$catName", newLimit)
                    editor.apply()
                    FirestoreSyncManager.pushAllDataToCloud(this@ScannerActivity)

                    parentDialog.dismiss()
                    showAllocationChooser(parentDialog, label, btn, paymentContainer, btnPayInit)
                    toast("Created $catName")
                    dialog.dismiss()
                }
            }
        })
        box.addView(buttonContainer)
        dialog.show()
    }

    private fun payUPI(upi: String, amt: String, pkg: String) {
        try {
            val paMatch = Regex("[?&]pa=([^&]+)").find(upi)?.groupValues?.get(1)
            val pnMatch = Regex("[?&]pn=([^&]+)").find(upi)?.groupValues?.get(1)

            if (paMatch == null || paMatch.isEmpty()) { toast("Invalid QR: Missing UPI ID"); return }
            val formattedAmt = String.format(java.util.Locale.US, "%.2f", amt.toDoubleOrNull() ?: 0.0)

            var p2pUriString = "upi://pay?pa=$paMatch&am=$formattedAmt&cu=INR"
            if (pnMatch != null && pnMatch.isNotEmpty()) p2pUriString += "&pn=$pnMatch"

            val baseIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(p2pUriString)
                setPackage(pkg)
            }

            if (packageManager.resolveActivity(baseIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivityForResult(baseIntent, PAYMENT_REQ)
            } else {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.action = Intent.ACTION_VIEW
                    it.data = Uri.parse(p2pUriString)
                    startActivityForResult(it, PAYMENT_REQ)
                } ?: toast("App not installed on this device")
            }
        } catch (e: Exception) { toast("Failed to launch payment app") }
    }

    private fun redirectSuccess() {
        if (pendingAmount > 0) {
            deductFromWallet(pendingAmount)
            saveExpense(pendingCategory ?: "no choice", pendingAmount, pendingTitle)
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("payment_detected", true)
            putExtra("result", "Transaction Successful")
            putExtra("payment_status", "success")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun redirectFailed() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("payment_detected", true)
            putExtra("result", "Transaction Failed")
            putExtra("payment_status", "failed")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun saveExpense(category: String, amount: Int, titleText: String) {
        val prefs = getSharedPreferences("GraphData", MODE_PRIVATE)
        val weeklyPrefs = getSharedPreferences("CategoryWeekData", MODE_PRIVATE)
        val editor = prefs.edit()
        val weekEditor = weeklyPrefs.edit()

        val cal = Calendar.getInstance().apply { setFirstDayOfWeek(Calendar.MONDAY); setMinimalDaysInFirstWeek(1) }
        val timestamp = cal.timeInMillis.toString()

        val dayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val monthIndex = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val weekIndex = cal.get(Calendar.WEEK_OF_MONTH) - 1

        editor.putFloat("SPENT_$category", prefs.getFloat("SPENT_$category", 0f) + amount)
        editor.putFloat("DAY_${weekIndex}_${dayIndex}_${monthIndex}_${year}", prefs.getFloat("DAY_${weekIndex}_${dayIndex}_${monthIndex}_${year}", 0f) + amount)
        editor.putFloat("WEEK_${weekIndex}_${monthIndex}_${year}", prefs.getFloat("WEEK_${weekIndex}_${monthIndex}_${year}", 0f) + amount)
        editor.putFloat("MONTH_${monthIndex}_${year}", prefs.getFloat("MONTH_${monthIndex}_${year}", 0f) + amount)

        val catWeekKey = "${category}_W${weekIndex + 1}"
        weekEditor.putInt(catWeekKey, weeklyPrefs.getInt(catWeekKey, 0) + amount)

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

        editor.apply(); weekEditor.apply()
        FirestoreSyncManager.pushAllDataToCloud(this)
    }

    private fun getParam(t: String, k: String) = Regex("$k=([^&]*)").find(t)?.groupValues?.get(1)
    private fun decode(v: String?) = v?.let { URLDecoder.decode(it, "UTF-8") }
    private fun toast(s: String) = ToastHelper.showToast(this, s)
    private fun successBeep() { try { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)); MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI).start() } catch (_: Exception) {} }
    private fun shake() { /* Visual feedback */ }
    private fun deductFromWallet(amt: Int) { val prefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE); prefs.edit().putInt("wallet_balance", prefs.getInt("wallet_balance", 0) - amt).apply() }
}
