package com.cash.dash

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import java.util.*
import kotlinx.coroutines.*

class HomeFragment : Fragment() {

    private val PREFS = "AppPrefs"
    private val KEY_NAME = "user_name"
    private val PREFS_WALLET = "WalletPrefs"
    private val KEY_BALANCE = "wallet_balance"
    private val PREFS_SCHEDULE = "MoneySchedulePrefs"
    private val KEY_NEXT_DATE = "next_date"
    private val KEY_FREQUENCY = "frequency"
    
    // Cache for optimization
    private var lastLoadedName: String? = null
    private var lastLoadedBalance: String? = null
    private var lastLoadedBarMode: String? = null
    private var lastLoadedBarType: String? = null
    private var lastLoadedDateStr: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadUserName(view)
        
        // Ensure username is visible for the shared element transition
        view.findViewById<TextView>(R.id.tvUsernameHome)?.apply {
            visibility = View.VISIBLE
            alpha = 1f
        }

        loadBalance(view)
        updateNextMoneyDays(view)

        // Set theme-aware icons
        view.findViewById<ImageView>(R.id.btnMenu)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_glass_menu_vector))
        view.findViewById<ImageView>(R.id.iconScanner)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_scanner))
        view.findViewById<ImageView>(R.id.iconRigorTracker)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_rigor_tracker))
        view.findViewById<ImageView>(R.id.btnProfile)?.setImageResource(ThemeHelper.getDrawable(requireContext(), R.drawable.ic_profile))

        view.findViewById<LinearLayout>(R.id.cardScanner).setOnClickListener {
            animateAndStart(view.findViewById<ImageView>(R.id.iconScanner)) {
                startActivity(Intent(requireContext(), ScannerActivity::class.java))
            }
        }

        view.findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            startActivity(Intent(requireContext(), MenuActivity::class.java))
        }

        view.findViewById<ImageView>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        view.findViewById<LinearLayout>(R.id.cardRigor).setOnClickListener {
            animateAndStart(view.findViewById<ImageView>(R.id.iconRigorTracker)) {
                startActivity(Intent(requireContext(), RigorActivity::class.java))
            }
        }


        // Start the postponed transition from MainActivity once this view is ready
        view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                activity?.supportStartPostponedEnterTransition()
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshUI()

        // Show setup dialog quickly after transition (800ms)
        view?.postDelayed({
            if (isAdded) checkInitialSetup()
        }, 800)
    }

    private fun checkInitialSetup() {
        val density = resources.displayMetrics.density
        val prefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
        val initialBalance = prefs.getInt("initial_balance", -1)

        if (initialBalance == -1) {
            val box = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val p = (28 * density).toInt()
                setPadding(p, p, p, (24 * density).toInt())
                setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
            }

            val titleView = TextView(requireContext()).apply {
                text = "Welcome to CashDash! ⚡"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (20 * density).toInt())
            }
            box.addView(titleView)

            val messageView = TextView(requireContext()).apply {
                text = "To start tracking your money, please set up your initial wallet balance."
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textMutedColor))
                gravity = android.view.Gravity.CENTER
                setLineSpacing(8f, 1f)
                setPadding(0, 0, 0, (40 * density).toInt())
            }
            box.addView(messageView)

            val dialog = AlertDialog.Builder(requireContext())
                .setView(box)
                .setCancelable(false)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val btnSetup = android.widget.Button(requireContext()).apply {
                text = "Set Up Wallet"
                isAllCaps = false
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
                setOnClickListener {
                    startActivity(Intent(requireContext(), BalanceSetupActivity::class.java))
                    dialog.dismiss()
                }
            }
            box.addView(btnSetup)
            dialog.show()
        }
    }

    fun refreshUI() {
        view?.let {
            loadUserName(it)
            loadBalance(it)
            updateNextMoneyDays(it)
        }
    }

    private fun loadUserName(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, "User") ?: "User"
        if (name != lastLoadedName) {
            view.findViewById<TextView>(R.id.tvUsernameHome)?.text = name
            lastLoadedName = name
        }
    }

    private fun loadBalance(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
        val bal = prefs.getInt(KEY_BALANCE, 0)
        val initialRaw = prefs.getInt("initial_balance", -1)

        val initial = if (initialRaw == -1) 0 else initialRaw
        val displayInitial = if (initial == 0 && bal == 0) 0 else initial.coerceAtLeast(1)
        
        val mode = prefs.getString("balance_bar_mode", "gradient") ?: "gradient"
        val type = prefs.getString("balance_bar_type", "gradient1") ?: "gradient1"
        
        val balanceStr = "₹$bal/$displayInitial"
        if (balanceStr != lastLoadedBalance || mode != lastLoadedBarMode || type != lastLoadedBarType) {
            view.findViewById<TextView>(R.id.tvBalance)?.text = balanceStr
            val progressPercent = if (displayInitial > 0) ((bal.toFloat() / displayInitial.toFloat()) * 100).toInt().coerceIn(0, 100) else 0
            
            val pBar = view.findViewById<com.cash.dash.GradientCircularProgressView>(R.id.walletProgress)
            pBar?.setColorConfig(mode, type)
            pBar?.setProgressCompat(progressPercent, true)
            
            lastLoadedBalance = balanceStr
            lastLoadedBarMode = mode
            lastLoadedBarType = type
        }
    }

    private fun updateNextMoneyDays(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS_SCHEDULE, android.content.Context.MODE_PRIVATE)
        val nextDate = prefs.getLong(KEY_NEXT_DATE, -1)
        val freq = prefs.getInt(KEY_FREQUENCY, -1)
        val textView = view.findViewById<TextView>(R.id.tvNextMoney) ?: return

        if (nextDate <= 0L || freq == -1) {
            textView.text = "Next money: schedule not set"
            return
        }

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val next = Calendar.getInstance().apply {
            timeInMillis = nextDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (today.after(next)) {
            // Perform Cycle Reset (Offloaded to background thread)
            CoroutineScope(Dispatchers.IO).launch {
                while (next.before(today)) {
                    next.add(Calendar.DAY_OF_YEAR, freq)
                }

                val newNextDateMs = next.timeInMillis
                prefs.edit().putLong(KEY_NEXT_DATE, newNextDateMs).apply()

                val categoryPrefs = requireContext().getSharedPreferences("CategoryPrefs", android.content.Context.MODE_PRIVATE)
                val categories = categoryPrefs.getStringSet("categories", emptySet()) ?: emptySet()
                val graphPrefs = requireContext().getSharedPreferences("GraphData", android.content.Context.MODE_PRIVATE)
                val graphEditor = graphPrefs.edit()
                for (cat in categories) {
                    graphEditor.putFloat("SPENT_$cat", 0f)
                }
                graphEditor.putFloat("SPENT_no choice", 0f)
                graphEditor.apply()

                val wPrefs = requireContext().getSharedPreferences(PREFS_WALLET, android.content.Context.MODE_PRIVATE)
                val initialBal = wPrefs.getInt("initial_balance", 0)
                wPrefs.edit().putInt("wallet_balance", initialBal).apply()

                FirestoreSyncManager.pushAllDataToCloud(requireContext())

                val newNextDateStr = "%02d/%02d/%04d".format(
                    next.get(Calendar.DAY_OF_MONTH),
                    next.get(Calendar.MONTH) + 1,
                    next.get(Calendar.YEAR)
                )

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        textView.text = "This money is tentatively till $newNextDateStr"
                        lastLoadedDateStr = newNextDateStr
                        loadBalance(requireView())
                    }
                }
            }
        } else {
            val nextDateStr = "%02d/%02d/%04d".format(
                next.get(Calendar.DAY_OF_MONTH),
                next.get(Calendar.MONTH) + 1,
                next.get(Calendar.YEAR)
            )
            if (nextDateStr != lastLoadedDateStr) {
                textView.text = "This money is tentatively till $nextDateStr"
                lastLoadedDateStr = nextDateStr
            }
        }
    }

    private fun animateAndStart(view: View, action: () -> Unit) {
        view.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(7)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(8)
                    .withEndAction {
                        action()
                    }
                    .start()
            }
            .start()
    }
}
