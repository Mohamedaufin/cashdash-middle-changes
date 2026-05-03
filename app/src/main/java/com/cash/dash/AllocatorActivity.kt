@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.IntentFilter

class AllocatorActivity : ThemedActivity() {

    private lateinit var categoryContainer: LinearLayout
    private val PREFS = "CategoryPrefs"
    private val KEY = "categories"
    private val MAX_CATEGORIES = 7

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allocator)

        categoryContainer = findViewById(R.id.categoryContainer)

        // HOME NAVIGATION
        findViewById<View>(R.id.tabHome)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        // HISTORY NAVIGATION
        findViewById<View>(R.id.tabHistory)?.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        loadCategories()
        addAddNewButton()
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

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun loadCategories() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedList = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        for (name in savedList) addCategoryCard(name)
    }

    private fun addAddNewButton() {
        val addView = layoutInflater.inflate(R.layout.item_category, null)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(22, 28, 22, 40)
        addView.layoutParams = params

        addView.findViewById<TextView>(R.id.categoryName).text = "Add new"
        addView.findViewById<ImageView>(R.id.iconEdit).setImageResource(R.drawable.ic_plus)
        addView.findViewById<TextView>(R.id.categoryLimit).visibility = View.GONE

        addView.findViewById<Button>(R.id.btnLimit).visibility = View.GONE

        addView.setOnClickListener { showAddCategoryDialog() }
        categoryContainer.addView(addView)
    }

    private fun showAddCategoryDialog() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

        if (saved.size >= MAX_CATEGORIES) {
            ToastHelper.showToast(this, "Maximum 7 categories allowed")
            return
        }

        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Add Category"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val input = EditText(this).apply {
            hint = "Enter category name"
            setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 50)
            }
        }
        box.addView(input)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                setMargins(0, 0, 15, 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnAdd = Button(this).apply {
            text = "Add"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                setMargins(15, 0, 0, 0)
            }
            setOnClickListener {
                val name = input.text.toString().trim().replace("|", "-")
                if (name.equals("Overall", ignoreCase = true)) {
                    ToastHelper.showToast(this@AllocatorActivity, "'Overall' is a reserved name")
                    return@setOnClickListener
                }
                if (name.isNotEmpty()) {
                    saveCategory(name)
                    refreshUI()
                    dialog.dismiss()
                }
            }
        }
        buttonContainer.addView(btnAdd)
        box.addView(buttonContainer)

        dialog.show()
    }

    private fun saveCategory(name: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        saved.add(name)
        prefs.edit().putStringSet(KEY, saved).apply()

        prefs.edit().putInt("LIMIT_$name", 0).apply()

        // Sync new category to cloud immediately
        FirestoreSyncManager.pushAllDataToCloud(this)
    }

    private fun deleteCategory(name: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        saved.remove(name)
        prefs.edit().putStringSet(KEY, saved).apply()
        prefs.edit().remove("LIMIT_$name").apply()

        // 🔥 THOROUGH CLEANUP: Reset SPENT and Weekly data for this category
        val graphPrefs = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        graphPrefs.edit().remove("SPENT_$name").apply()

        val weekPrefs = getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
        val weekEditor = weekPrefs.edit()
        for (w in 1..5) {
            weekEditor.remove("${name}_W$w")
        }
        weekEditor.apply()

        // Sync deletion to cloud
        FirestoreSyncManager.pushAllDataToCloud(this)
    }

    private fun renameCategory(oldName: String, newName: String) {
        val catPrefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(catPrefs.getStringSet(KEY, emptySet()) ?: emptySet())

        if (saved.remove(oldName)) {
            saved.add(newName)
            catPrefs.edit().putStringSet(KEY, saved).apply()

            // 1. Migrate Limits
            val oldLimit = catPrefs.getInt("LIMIT_$oldName", 0)
            catPrefs.edit().putInt("LIMIT_$newName", oldLimit).remove("LIMIT_$oldName").apply()

            // 2. Migrate Spent Totals (GraphData)
            val graphPrefs = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
            val oldSpent = graphPrefs.getFloat("SPENT_$oldName", 0f)
            graphPrefs.edit().putFloat("SPENT_$newName", oldSpent).remove("SPENT_$oldName").apply()

            // 3. Migrate Weekly Analytics (CategoryWeekData)
            val weekPrefs = getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
            val weekEditor = weekPrefs.edit()
            for (w in 1..5) {
                val oldVal = weekPrefs.getInt("${oldName}_W$w", 0)
                if (oldVal > 0) {
                    weekEditor.putInt("${newName}_W$w", oldVal).remove("${oldName}_W$w")
                }
            }
            weekEditor.apply()

            HistoryDataManager.renameCategory(this, oldName, newName)
        }
    }

    private fun refreshUI() {
        categoryContainer.removeAllViews()
        loadCategories()
        addAddNewButton()
    }

    private fun addCategoryCard(name: String) {
        val view = layoutInflater.inflate(R.layout.item_category, null)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(22, 28, 22, 0)
        view.layoutParams = params

        val btnLimit = view.findViewById<Button>(R.id.btnLimit)
        val limitText = view.findViewById<TextView>(R.id.categoryLimit)

        view.findViewById<TextView>(R.id.categoryName).text = name

        // 🔮 AI Keyword Custom Icons
        val iconView = view.findViewById<ImageView>(R.id.iconEdit)
        val iconRes = CategoryIconHelper.getIconForCategory(this, name)
        val drw = androidx.core.content.ContextCompat.getDrawable(this, iconRes)
        iconView.setImageDrawable(com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(this, drw))

        // 👉 TAP CARD → OPEN CATEGORY ANALYSIS ACTIVITY
        view.setOnClickListener {
            val intent = Intent(this,CategoryAnalysisActivity::class.java)
            intent.putExtra("CATEGORY_NAME", name)
            startActivity(intent)
        }

        // Load limit
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val limit = prefs.getInt("LIMIT_$name", 0)

        if (limit > 0) {
            limitText.text = "Limit : ₹$limit"
            limitText.visibility = View.VISIBLE
        } else {
            limitText.visibility = View.GONE
        }

        // OPEN LIMIT SET PAGE
        btnLimit.setOnClickListener {
            val intent = Intent(this, SetLimitActivity::class.java)
            intent.putExtra("CATEGORY_NAME", name)
            startActivity(intent)
        }

        view.setOnLongClickListener {
            val density = resources.displayMetrics.density
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (28 * density).toInt()
                setPadding(p, p, p, p)
                setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
            }

            val titleView = TextView(this).apply {
                text = "Rename Category"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subhead))
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (20 * density).toInt())
            }
            box.addView(titleView)

            val input = EditText(this).apply {
                setText(name)
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.CYAN)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 50)
                }
            }
            box.addView(input)

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
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                stateListAnimator = null
                elevation = 0f
                layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                    setMargins(0, 0, 15, 0)
                }
                setOnClickListener { dialog.dismiss() }
            }
            buttonContainer.addView(btnCancel)

            val btnSave = android.widget.Button(this).apply {
                text = "Save"
                isAllCaps = false
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                stateListAnimator = null
                elevation = 0f
                layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                    setMargins(15, 0, 0, 0)
                }
                setOnClickListener {
                    val newName = input.text.toString().trim().replace("|", "-")
                    if (newName.equals("Overall", ignoreCase = true)) {
                        ToastHelper.showToast(this@AllocatorActivity, "'Overall' is a reserved name")
                        return@setOnClickListener
                    }
                    if (newName.isNotEmpty()) {
                        renameCategory(name, newName)
                        FirestoreSyncManager.pushAllDataToCloud(this@AllocatorActivity)
                        refreshUI()
                    }
                    dialog.dismiss()
                }
            }
            buttonContainer.addView(btnSave)
            box.addView(buttonContainer)
            dialog.show()

            true
        }

        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null) {
                    val deltaX = e1.x - e2.x
                    val deltaY = e1.y - e2.y

                    // Highly intentional left-swipe: deltaX positive (swipe left), velocity negative, and strict horizontal thresholds
                    if (Math.abs(deltaX) > Math.abs(deltaY) && deltaX > 50 && vx < -150) {
                        view.animate().translationX(-view.width.toFloat()).alpha(0f).setDuration(250)
                            .withEndAction {
                                val density = resources.displayMetrics.density
                                val box = LinearLayout(this@AllocatorActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    val p = (28 * density).toInt()
                                    setPadding(p, p, p, (24 * density).toInt())
                                    setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this@AllocatorActivity, R.drawable.bg_transaction))
                                }

                                val titleView = TextView(this@AllocatorActivity).apply {
                                    text = "Delete Allocation - $name?"
                                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subhead))
                                    setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                                    setTypeface(null, android.graphics.Typeface.BOLD)
                                    gravity = android.view.Gravity.CENTER
                                    setPadding(0, 0, 0, (16 * density).toInt())
                                }
                                box.addView(titleView)

                                val buttonContainer = LinearLayout(this@AllocatorActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                }

                                val dialog = AlertDialog.Builder(this@AllocatorActivity)
                                    .setView(box)
                                    .setCancelable(false)
                                    .create()
                                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                                val btnCancel = android.widget.Button(this@AllocatorActivity).apply {
                                    text = "Cancel"
                                    isAllCaps = false
                                    setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))
                                    val tv = android.util.TypedValue()
                                    this@AllocatorActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
                                    background = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, tv.resourceId)
                                    stateListAnimator = null
                                    elevation = 0f
                                    layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                                        setMargins(0, 0, (8 * density).toInt(), 0)
                                    }
                                    setOnClickListener {
                                        view.animate().translationX(0f).alpha(1f).setDuration(200).start()
                                        dialog.dismiss()
                                    }
                                }
                                buttonContainer.addView(btnCancel)

                                val btnDelete = android.widget.Button(this@AllocatorActivity).apply {
                                    text = "Delete"
                                    isAllCaps = false
                                    setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
                                    val tv = android.util.TypedValue()
                                    this@AllocatorActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
                                    background = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, tv.resourceId)
                                    stateListAnimator = null
                                    elevation = 0f
                                    layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                                        setMargins((8 * density).toInt(), 0, 0, 0)
                                    }
                                    setOnClickListener {
                                        deleteCategory(name)
                                        FirestoreSyncManager.pushAllDataToCloud(this@AllocatorActivity)
                                        refreshUI()
                                        dialog.dismiss()
                                    }
                                }
                                buttonContainer.addView(btnDelete)
                                box.addView(buttonContainer)
                                dialog.show()
                            }.start()
                        return true
                    }
                }
                return false
            }
        })

        var startX = 0f
        var startY = 0f
        var isSwiping = false

        val swipeTouch = View.OnTouchListener { v, event ->
            var consumeClick = false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isSwiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dX = Math.abs(event.x - startX)
                    val dY = Math.abs(event.y - startY)
                    // If intentional horizontal gesture is detected: block parent scrolls, cancel long click! 
                    if (dX > 15 && dX > dY * 1.5) {
                        isSwiping = true
                        v.cancelLongPress() // Prevents "Rename" dialog from popping up mid-swipe
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwiping) {
                        consumeClick = true // Lock out any normal click triggers
                    }
                }
            }
            swipeDetector.onTouchEvent(event)
            consumeClick
        }

        view.setOnTouchListener(swipeTouch)
        btnLimit.setOnTouchListener(swipeTouch)

        categoryContainer.addView(view)
    }
}
