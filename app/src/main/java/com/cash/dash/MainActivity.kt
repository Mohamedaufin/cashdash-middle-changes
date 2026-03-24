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

class MainActivity : AppCompatActivity() {

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
    private val colorActive = Color.WHITE
    private val colorInactive = Color.parseColor("#D0E0FF")
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

        if (intent.getBooleanExtra("from_splash", false)) {
            supportPostponeEnterTransition()
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        density = resources.displayMetrics.density
        iconHeightPx = 84 * density

        initNavbar()
        initViewPager()

        ensureAccountCreationTime()

        if (intent.extras?.containsKey("google.message_id") == true) {
            val notifIntent = Intent(this, NotificationActivity::class.java)
            startActivity(notifIntent)
        }

        SecurityManager.startListening(this)
        requestNotificationPermission()
        registerFCMToken()

        FirestoreSyncManager.startRealTimeSync(this)
        
        // 🔄 MIGRATION TRIGGER: Ensure existing logged-in users have their data pushed to the new Email-based document ID
        FirestoreSyncManager.pushAllDataToCloud(this)
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

        tabAllocator.setOnClickListener { navigateTo(0) }
        tabHome.setOnClickListener { navigateTo(1) }
        tabHistory.setOnClickListener { navigateTo(2) }

        updateNavbarStateBetween(1, 1, 0f)
    }

    private fun navigateTo(index: Int) {
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

        // Only update shadow if state changed to save on redraws
        val radius = if (alpha > 0.8f) 6f else 4f
        val shadowColor = if (color == Color.WHITE) Color.parseColor("#3A6AFF") else Color.parseColor("#000C40")
        
        if (radius != lastShadowRadius || shadowColor != lastShadowColor) {
            text.setShadowLayer(radius, 0f, 2f, shadowColor)
            lastShadowRadius = radius
            lastShadowColor = shadowColor
        }
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
        val result = intent.getStringExtra("payment_status")
        when (result) {
            "success" -> Snackbar.make(findViewById(android.R.id.content), "✔ Payment Successful", Snackbar.LENGTH_LONG).show()
            "failed" -> Snackbar.make(findViewById(android.R.id.content), "❌ Payment Failed or Cancelled", Snackbar.LENGTH_LONG).show()
        }
        intent.removeExtra("payment_status")
    }
}
