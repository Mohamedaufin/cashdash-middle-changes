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
import com.google.android.material.bottomsheet.BottomSheetDialog

class AllocatorActivity : ThemedActivity() {

    private lateinit var categoryContainer: LinearLayout
    private val PREFS = "CategoryPrefs"
    private val KEY = "categories"

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allocator)

        TutorialManager.showTutorialIfNeeded(
            this,
            "tut_allocator",
            "Money Allocator",
            "Here, you can set new allocations (Shopping, Food, etc.) and set limits for the category.\n\n1. Tap '+ Add New' to create a new category\n\n2. Press done and set an optional spending limit for it"
        )

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
        val sortedList = savedList.sortedBy { it.lowercase() }
        for (name in sortedList) addCategoryCard(name)
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
            hint = "Enter category name (Eg: Food)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 10)
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
                    input.error = "'Overall' is a reserved name"
                    return@setOnClickListener
                }

                val currentSaved = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()
                val exists = currentSaved.any { it.equals(name, ignoreCase = true) }
                if (exists) {
                    input.error = "Allocation name already exists"
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

    internal fun refreshUI() {
        categoryContainer.removeAllViews()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedList = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        if (savedList.isNotEmpty()) {
            val density = resources.displayMetrics.density
            val hint1 = TextView(this).apply {
                text = "Tap on any allocator to view detailed insights"
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (12 * density).toInt(), 0, 0)
                }
            }
            val hint2 = TextView(this).apply {
                text = "Press and hold on any allocator to edit it"
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (2 * density).toInt(), 0, (16 * density).toInt())
                }
            }
            categoryContainer.addView(hint1)
            categoryContainer.addView(hint2)
        }

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

        view.setOnFastLongClickListener {
            showAllocationOptions(name)
        }

        categoryContainer.addView(view)
    }

    private fun showRenameCategoryDialog(name: String) {
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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.CYAN)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 40)
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
                    input.error = "'Overall' is a reserved name"
                    return@setOnClickListener
                }
                
                // If it's a completely different name (case-insensitive) but already exists
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val currentSaved = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
                if (!newName.equals(name, ignoreCase = true) && currentSaved.any { it.equals(newName, ignoreCase = true) }) {
                    input.error = "Allocation name already exists"
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
    }

    private fun showDeleteCategoryConfirmation(name: String) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Delete Allocation - $name?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        box.addView(titleView)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = android.widget.Button(this).apply {
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
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnCancel)

        val btnDelete = android.widget.Button(this).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textRedColor))
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
    }

    private fun showAllocationOptions(name: String) {
        val bottomSheet = BottomSheetDialog(this, ThemeHelper.getBottomSheetTheme(this))

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * density).toInt()
            setPadding(p, p, p, (32 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val title = TextView(this).apply {
            text = name
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (24 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        container.addView(title)

        // CHANGE ICON OPTION
        val btnChangeIcon = android.widget.Button(this).apply {
            text = "Change Allocator Icon"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@AllocatorActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                showIconPickerDialog(name)
            }
        }
        container.addView(btnChangeIcon)

        // RENAME OPTION
        val btnRename = android.widget.Button(this).apply {
            text = "Rename Allocation"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@AllocatorActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                showRenameCategoryDialog(name)
            }
        }
        container.addView(btnRename)

        // DELETE OPTION
        val btnDelete = android.widget.Button(this).apply {
            text = "Delete Allocation"
            isAllCaps = false
            setTextColor(ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textRedColor))
            val tv = android.util.TypedValue()
            this@AllocatorActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
            setOnClickListener {
                bottomSheet.dismiss()
                showDeleteCategoryConfirmation(name)
            }
        }
        container.addView(btnDelete)

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun showIconPickerDialog(categoryName: String) {
        val density = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Select Category Icon"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val icons = listOf(
            Pair("Food", R.drawable.ic_category_food),
            Pair("Shopping", R.drawable.ic_category_shopping),
            Pair("Fuel", R.drawable.ic_category_fuel),
            Pair("Transport", R.drawable.ic_category_transport),
            Pair("Water", R.drawable.ic_category_water),
            Pair("Others", R.drawable.ic_edit)
        )

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        icons.forEach { (name, resId) ->
            val btn = android.widget.Button(this).apply {
                text = name
                isAllCaps = false
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))

                if (resId == R.drawable.ic_edit) {
                    val drw = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, resId)
                    drw?.let {
                        val iconSize = (24 * density).toInt()
                        val h = if (it.intrinsicWidth > 0) (iconSize * it.intrinsicHeight) / it.intrinsicWidth else iconSize
                        it.setBounds(0, 0, iconSize, h)
                    }
                    val tinted = com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(this@AllocatorActivity, drw)
                    setCompoundDrawables(tinted, null, null, null)
                } else {
                    val drw = androidx.core.content.ContextCompat.getDrawable(this@AllocatorActivity, resId)
                    val tinted = com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(this@AllocatorActivity, drw)
                    setCompoundDrawablesWithIntrinsicBounds(tinted, null, null, null)
                }

                compoundDrawablePadding = (12 * density).toInt()
                setPadding((16 * density).toInt(), 0, 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()
                ).apply { setMargins(0, 0, 0, (12 * density).toInt()) }

                setOnClickListener {
                    val prefs = getSharedPreferences("CategoryPrefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putInt("ICON_$categoryName", resId).apply()
                    
                    // Push to Firestore & broadcast change to other activities immediately
                    FirestoreSyncManager.pushAllDataToCloud(this@AllocatorActivity)
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this@AllocatorActivity)
                        .sendBroadcast(android.content.Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))
                    
                    refreshUI()
                    dialog.dismiss()
                }
            }
            box.addView(btn)
        }

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@AllocatorActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (50 * density).toInt()
            )
            setOnClickListener { dialog.dismiss() }
        }
        box.addView(btnCancel)

        dialog.show()
    }
}