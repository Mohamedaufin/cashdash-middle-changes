@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import java.util.*

class MainActivity : ThemedActivity() {

    private val PREFS = "AppPrefs"
    private val KEY_NAME = "user_name"
    private val PREFS_WALLET = "WalletPrefs"
    private val KEY_BALANCE = "wallet_balance"

    private val PREFS_SCHEDULE = "MoneySchedulePrefs"
    private val KEY_NEXT_DATE = "next_date"
    private val KEY_FREQUENCY = "frequency"

    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: MainPagerAdapter

    // Navbar Views
    private lateinit var iconHome: ImageView
    private lateinit var tvHome: TextView
    private lateinit var tabHome: View
    private lateinit var iconAllocator: ImageView
    private lateinit var tvAllocator: TextView
    private lateinit var tabAllocator: View
    private lateinit var iconHistory: ImageView
    private lateinit var tvHistory: TextView
    private lateinit var tabHistory: View

    private val inactiveScale = 0.5f
    private var colorActive = Color.WHITE
    private var colorInactive = Color.parseColor("#D0E0FF")
    private val argbEvaluator = android.animation.ArgbEvaluator()

    private var isNavigating = false
    private var navFrom = -1
    private var navTo = -1
    private var density: Float = 0f
    private var iconHeightPx: Float = 0f
    private var lastShadowRadius = -1f
    private var lastShadowColor = -1

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            (supportFragmentManager.findFragmentByTag("f" + viewPager.currentItem) as? HomeFragment)?.refreshUI()
        }
    }




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ONE-TIME FIX: Recover missing metadata for past scanner transactions
        Thread {
            try {
                val prefs = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
                val historyList = prefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()
                val metaPrefs = getSharedPreferences("ScannerMetadataPrefs", Context.MODE_PRIVATE)

                for (raw in historyList) {
                    val parts = raw.split("|")
                    if (parts.size >= 9) {
                        val title = parts[2]
                        val ts = parts[1].toLongOrNull() ?: 0L
                        if (title.startsWith("To: ", ignoreCase = true)) {
                            if (!metaPrefs.contains("APP_$ts")) {
                                // Defaulting lost metadata to CRED as requested
                                metaPrefs.edit().putString("APP_$ts", "CRED").apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) { android.util.Log.e("MainActivity", "Error assigning metadata default fallback", e) }
        }.start()

        // ONE-TIME FIX: Deduplicate Allocator Categories (Case-Insensitive)
        Thread {
            try {
                val catPrefs = getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
                val categories = catPrefs.getStringSet("categories", null)?.toMutableSet()
                if (categories != null) {
                    val lowerCaseMap = mutableMapOf<String, String>()
                    var changed = false
                    val toRemove = mutableListOf<String>()

                    for (cat in categories) {
                        val lower = cat.lowercase()
                        if (lowerCaseMap.containsKey(lower)) {
                            toRemove.add(cat)
                            changed = true
                        } else {
                            lowerCaseMap[lower] = cat
                        }
                    }

                    if (changed) {
                        val editor = catPrefs.edit()
                        for (cat in toRemove) {
                            categories.remove(cat)
                            editor.remove("LIMIT_$cat")
                            editor.remove("ICON_$cat")
                        }
                        editor.putStringSet("categories", categories)
                        editor.apply()
                        FirestoreSyncManager.pushAllDataToCloud(this)
                    }
                }
            } catch (e: Exception) { android.util.Log.e("MainActivity", "Error deduplicating categories", e) }
        }.start()

        if (intent.getBooleanExtra("from_splash", false)) {
            supportPostponeEnterTransition()
        }

        density = resources.displayMetrics.density
        iconHeightPx = 84 * density

        colorActive = ThemeHelper.resolveColorAttr(this, R.attr.navActiveColor)
        colorInactive = ThemeHelper.resolveColorAttr(this, R.attr.navInactiveColor)

        initNavbar()
        initViewPager()

        val bottomNav = findViewById<View>(R.id.bottomNav)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        ensureAccountCreationTime()
        if (intent.extras?.containsKey("google.message_id") == true) {
            val notifIntent = Intent(this, NotificationActivity::class.java)
            startActivity(notifIntent)
        }


        SecurityManager.startListening(this)
        requestNotificationPermission()
        registerFCMToken()

        FirestoreSyncManager.startRealTimeSync(this)
        updateUserMetadata()

        // Start Usage Tracker if enabled
        val smartPrefs = getSharedPreferences("SmartAssistantPrefs", MODE_PRIVATE)
        if (smartPrefs.getBoolean("tracking_enabled", false)) {
            val serviceIntent = Intent(this, AppUsageTrackerService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        }

        // 🔄 MIGRATION TRIGGER: Ensure existing logged-in users have their data pushed to the new Email-based document ID
        val migrationPrefs = getSharedPreferences("MigrationPrefs", MODE_PRIVATE)
        if (!migrationPrefs.getBoolean("email_sync_migrated", false)) {
            FirestoreSyncManager.pushAllDataToCloud(this)
            migrationPrefs.edit().putBoolean("email_sync_migrated", true).apply()
        }
    }

    private fun initNavbar() {
        iconHome = findViewById(R.id.iconHome)
        tvHome = findViewById(R.id.tvHome)
        tabHome = findViewById(R.id.tabHome)
        iconAllocator = findViewById(R.id.iconAllocator)
        tvAllocator = findViewById(R.id.tvAllocator)
        tabAllocator = findViewById(R.id.tabAllocator)
        iconHistory = findViewById(R.id.iconHistory)
        tvHistory = findViewById(R.id.tvHistory)
        tabHistory = findViewById(R.id.tabHistory)

        iconHome.setImageResource(ThemeHelper.getDrawable(this, R.drawable.ic_home))
        iconAllocator.setImageResource(ThemeHelper.getDrawable(this, R.drawable.ic_allocator))
        iconHistory.setImageResource(ThemeHelper.getDrawable(this, R.drawable.ic_history))

        tabAllocator.setOnClickListener { navigateTo(0) }
        tabHome.setOnClickListener { navigateTo(1) }
        tabHistory.setOnClickListener { navigateTo(2) }

        updateNavbarStateBetween(1, 1, 0f)
    }

    fun navigateTo(index: Int) {
        val current = viewPager.currentItem
        if (current == index || isNavigating) return

        // Always use performNonAdjacentSlide for consistent duration and custom skip logic
        performNonAdjacentSlide(current, index)
    }

    private fun performNonAdjacentSlide(from: Int, to: Int) {
        isNavigating = true
        navFrom = from
        navTo = to
        viewPager.isUserInputEnabled = false

        val width = viewPager.width.toFloat()
        val distance = Math.abs(navTo - navFrom).toFloat()
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 180
        animator.interpolator = android.view.animation.DecelerateInterpolator()

        var lastValue = 0f

        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            val deltaProgress = value - lastValue
            lastValue = value

            // Sync navbar
            updateNavbarManualTransition(from, to, value)

            // Sync page drag
            if (viewPager.isFakeDragging || viewPager.beginFakeDrag()) {
                val dragDelta = if (to > from) -deltaProgress * width * distance else deltaProgress * width * distance
                viewPager.fakeDragBy(dragDelta)
            }
        }

        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (viewPager.isFakeDragging) viewPager.endFakeDrag()
                viewPager.setCurrentItem(to, false)
                viewPager.isUserInputEnabled = true

                // Clear state
                isNavigating = false
                navFrom = -1
                navTo = -1

                updateNavbarStateBetween(to, to, 0f)
            }
        })

        animator.start()
    }

    private fun updateNavbarManualTransition(from: Int, to: Int, progress: Float) {
        val activeS = 1.0f
        val inactiveS = inactiveScale

        val scaleFrom = activeS - ((activeS - inactiveS) * progress)
        val alphaFrom = 1.0f - (0.4f * progress)
        val colorFrom = argbEvaluator.evaluate(progress, colorActive, colorInactive) as Int

        val scaleTo = inactiveS + ((activeS - inactiveS) * progress)
        val alphaTo = 0.6f + (0.4f * progress)
        val colorTo = argbEvaluator.evaluate(progress, colorInactive, colorActive) as Int

        // Target specifically the source and destination. Others stay inactive.
        val states = Array(3) { floatArrayOf(inactiveS, 0.6f, colorInactive.toFloat()) }

        states[from] = floatArrayOf(scaleFrom, alphaFrom, colorFrom.toFloat())
        states[to] = floatArrayOf(scaleTo, alphaTo, colorTo.toFloat())

        applyState(iconAllocator, tvAllocator, states[0][0], states[0][1], states[0][2].toInt())
        applyState(iconHome, tvHome, states[1][0], states[1][1], states[1][2].toInt())
        applyState(iconHistory, tvHistory, states[2][0], states[2][1], states[2][2].toInt())
    }

    private fun initViewPager() {
        viewPager = findViewById(R.id.viewPager)
        adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 2
        viewPager.setCurrentItem(1, false)

        viewPager.setPageTransformer { page, position ->
            if (isNavigating && Math.abs(navFrom - navTo) > 1) {
                val pageIndex = when {
                    page.findViewById<View>(R.id.categoryContainer) != null -> 0
                    page.findViewById<View>(R.id.walletContainer) != null -> 1
                    page.findViewById<View>(R.id.dayGraph) != null -> 2
                    else -> -1
                }

                if (pageIndex == 1) {
                    page.alpha = 0f
                } else if (pageIndex == 0 || pageIndex == 2) {
                    page.alpha = 1f
                    // Shift both pages by half the distance to make them adjacent
                    page.translationX = -position * 0.5f * page.width
                }
            } else {
                page.alpha = 1f
                page.translationX = 0f
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (!isNavigating) {
                    updateNavbarStateBetween(position, position + 1, positionOffset)
                }
            }
        })
    }

    private fun updateNavbarStateBetween(pos1: Int, pos2: Int, offset: Float) {
        val scales = floatArrayOf(inactiveScale, inactiveScale, inactiveScale)
        val alphas = floatArrayOf(0.6f, 0.6f, 0.6f)
        val colors = intArrayOf(colorInactive, colorInactive, colorInactive)

        if (pos1 in 0..2) {
            scales[pos1] = 1.0f - ((1.0f - inactiveScale) * offset)
            alphas[pos1] = 1.0f - (0.4f * offset)
            colors[pos1] = argbEvaluator.evaluate(offset, colorActive, colorInactive) as Int
        }

        // Only update pos2 if it's different from pos1 to avoid resetting the active state
        if (pos2 in 0..2 && pos2 != pos1) {
            scales[pos2] = inactiveScale + ((1.0f - inactiveScale) * offset)
            alphas[pos2] = 0.6f + (0.4f * offset)
            colors[pos2] = argbEvaluator.evaluate(offset, colorInactive, colorActive) as Int
        }

        applyState(iconAllocator, tvAllocator, scales[0], alphas[0], colors[0])
        applyState(iconHome, tvHome, scales[1], alphas[1], colors[1])
        applyState(iconHistory, tvHistory, scales[2], alphas[2], colors[2])
    }

    private fun applyState(icon: View, text: TextView, scale: Float, alpha: Float, color: Int) {
        icon.scaleX = scale
        icon.scaleY = scale
        icon.alpha = alpha

        val translationY = -(1.0f - scale) * (iconHeightPx / 2f)
        text.translationY = translationY

        text.setTextColor(color)
        text.alpha = alpha
    }

    private inner class MainPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> AllocatorFragment()
                1 -> HomeFragment()
                else -> HistoryFragment()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            syncReceiver, IntentFilter(FirestoreSyncManager.ACTION_SYNC_UPDATE)
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncReceiver)
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    private fun registerFCMToken() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                if (token != null) {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val email = user.email ?: return@addOnCompleteListener
                    db.collection("users").document(email)
                        .set(hashMapOf("fcmToken" to token, "email" to email), com.google.firebase.firestore.SetOptions.merge())
                }
            }
        }
    }

    private fun ensureAccountCreationTime() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.contains("account_creation_time")) {
            prefs.edit().putLong("account_creation_time", System.currentTimeMillis()).apply()
            FirestoreSyncManager.pushAllDataToCloud(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateUserMetadata()
        
        val result = intent.getStringExtra("payment_status")
        if (result == "failed") {
            val snackbar = Snackbar.make(findViewById(android.R.id.content), "❌ Payment Failed or Cancelled", Snackbar.LENGTH_LONG)
            snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.setTextColor(android.graphics.Color.WHITE)
            snackbar.show()
        }
        intent.removeExtra("payment_status")

        val toastMsg = intent.getStringExtra("toast_msg")
        if (toastMsg != null) {
            ToastHelper.showCustomToast(this, toastMsg, 800L)
            intent.removeExtra("toast_msg")
        }
    }

    private fun updateUserMetadata() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy, h:mm a", java.util.Locale.ENGLISH)
        sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val lastActive = sdf.format(java.util.Date())

        val editor = prefs.edit()
        editor.putString("lastActiveTime", lastActive)
        editor.apply()

        FirestoreSyncManager.pushAllDataToCloud(this)
    }
}
