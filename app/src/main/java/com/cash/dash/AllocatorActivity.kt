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

class AllocatorActivity : AppCompatActivity() {

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

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

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

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            setBackgroundResource(R.drawable.bg_transaction)
        }

        val titleView = TextView(this).apply {
            text = "Add Category"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        box.addView(titleView)

        val input = EditText(this).apply {
            hint = "Enter category name"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
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

        val btnCancel = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                setMargins(0, 0, 15, 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnAdd = Button(this).apply {
            text = "Add"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
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

            // 4. Update History List Entries
            val historySet = (graphPrefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()).toMutableSet()
            val newHistorySet = mutableSetOf<String>()
            var updatedCount = 0

            historySet.forEach { entry ->
                val parts = entry.split("|").toMutableList()
                if (parts.size >= 4 && parts[3] == oldName) {
                    parts[3] = newName
                    newHistorySet.add(parts.joinToString("|"))

                    // Also update the individual TRANS_ lookup key if it exists
                    val timestamp = parts[1]
                    graphPrefs.edit().putString("TRANS_${timestamp}_CATEGORY", newName).apply()
                    updatedCount++
                } else {
                    newHistorySet.add(entry)
                }
            }

            if (updatedCount > 0) {
                graphPrefs.edit().putStringSet("HISTORY_LIST", newHistorySet).apply()
            }

            // android.util.Log.d("AllocatorActivity", "Renamed $oldName to $newName. Migrated $updatedCount history entries.")
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
        val iconRes = CategoryIconHelper.getIconForCategory(name)
        iconView.setImageResource(iconRes)

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
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(60, 60, 60, 50)
            box.setBackgroundResource(R.drawable.bg_transaction)

            val titleView = TextView(this).apply {
                text = "Rename Category"
                textSize = 22f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 40)
            }
            box.addView(titleView)

            val input = EditText(this).apply {
                setText(name)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
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
                setTextColor(Color.WHITE)
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
                layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                    setMargins(0, 0, 15, 0)
                }
                setOnClickListener { dialog.dismiss() }
            }
            buttonContainer.addView(btnCancel)

            val btnSave = android.widget.Button(this).apply {
                text = "Save"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
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

                    // Lenient Right-to-Left check: horizontal, moving left, moderate velocity
                    if (Math.abs(deltaX) > Math.abs(deltaY) && deltaX > 80 && Math.abs(vx) > 50) {
                        view.animate().translationX(-view.width.toFloat()).alpha(0f).setDuration(250)
                            .withEndAction {
                                val box = LinearLayout(this@AllocatorActivity)
                                box.orientation = LinearLayout.VERTICAL
                                box.setPadding(60, 60, 60, 50)
                                box.setBackgroundResource(R.drawable.bg_transaction)

                                val titleView = TextView(this@AllocatorActivity).apply {
                                    text = "Delete Allocation - $name?"
                                    textSize = 22f
                                    setTextColor(Color.WHITE)
                                    setTypeface(null, android.graphics.Typeface.BOLD)
                                    gravity = android.view.Gravity.CENTER
                                    setPadding(0, 0, 0, 120) // Increased gap
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
                                    setTextColor(Color.WHITE)
                                    background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
                                    layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                                        setMargins(0, 0, 15, 0)
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
                                    setTextColor(Color.WHITE)
                                    background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
                                    layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                                        setMargins(15, 0, 0, 0)
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
                    // If horizontal movement is detected, immediately block ANY parent ScrollView or ViewPager
                    if (dX > 10) {
                        isSwiping = true
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
