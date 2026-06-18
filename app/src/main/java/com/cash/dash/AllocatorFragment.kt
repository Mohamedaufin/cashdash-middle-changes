@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetDialog

class AllocatorFragment : Fragment() {

    private lateinit var categoryContainer: LinearLayout
    private val PREFS = "CategoryPrefs"
    private val KEY = "categories"

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_allocator, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        categoryContainer = view.findViewById(R.id.categoryContainer)
        refreshUI()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            syncReceiver, IntentFilter(FirestoreSyncManager.ACTION_SYNC_UPDATE)
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(syncReceiver)
    }

    override fun onResume() {
        super.onResume()

        TutorialManager.showTutorialIfNeeded(
            requireActivity(),
            "tut_allocator",
            "Category Allocator",
            "Here, you can set new allocations (Shopping, Food, etc.) and set limits for the category.\n\n1. Tap '+ Add New' to create a new category\n2. Press done and set an optional spending limit for it\n\n*(Note: You can revisit these instructions anytime in the 'Help' section! Tap the Menu icon located next to 'Hello' on your Home dashboard to find it.)*"
        )

        refreshUI()
    }

    private fun refreshUI() {
        if (!::categoryContainer.isInitialized) return
        categoryContainer.removeAllViews()

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedList = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        if (savedList.isNotEmpty()) {
            val density = requireContext().resources.displayMetrics.density
            val hint1 = TextView(requireContext()).apply {
                text = "Tap on any allocator to view detailed insights"
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textMutedColor))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (12 * density).toInt(), 0, 0)
                }
            }
            val hint2 = TextView(requireContext()).apply {
                text = "Press and hold on any allocator to edit it"
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textMutedColor))
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

    private fun loadCategories() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

        val addNameText = addView.findViewById<TextView>(R.id.categoryName)
        val addIcon = addView.findViewById<ImageView>(R.id.iconEdit)
        addNameText.text = "Add new"
        addIcon.setImageResource(R.drawable.ic_plus)
        addView.findViewById<TextView>(R.id.categoryLimit).visibility = View.GONE
        addView.findViewById<Button>(R.id.btnLimit).visibility = View.GONE

        addView.setOnClickListener { showAddCategoryDialog() }
        categoryContainer.addView(addView)
    }

    private fun showAddCategoryDialog() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

        val density = requireContext().resources.displayMetrics.density
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(requireContext(), R.drawable.bg_transaction))
        }

        val titleView = TextView(requireContext()).apply {
            text = "Add Category"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, requireContext().resources.getDimension(R.dimen.text_subhead)) 
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val input = EditText(requireContext()).apply {
            hint = "Enter category name (Eg: Food)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(requireContext(), com.cash.dash.ThemeHelper.getDrawable(requireContext(), R.drawable.bg_glass_input))
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, (28 * density).toInt())
            }
        }
        box.addView(input)

        val errorView = TextView(requireContext()).apply {
            setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(10, 0, 0, 40)
            }
        }
        box.addView(errorView)

        val buttonContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(requireContext()).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnAdd = Button(requireContext()).apply {
            text = "Add"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            setOnClickListener {
                errorView.visibility = android.view.View.GONE
                val name = input.text.toString().trim().replace("|", "-")
                if (name.equals("Overall", ignoreCase = true)) {
                    input.error = "'Overall' is a reserved name"
                    errorView.text = "'Overall' is a reserved name"
                    errorView.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                
                val currentSaved = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()
                val exists = currentSaved.any { it.equals(name, ignoreCase = true) }
                if (exists) {
                    input.error = "Allocation name already exists"
                    errorView.text = "Allocation name already exists"
                    errorView.visibility = android.view.View.VISIBLE
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
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        saved.add(name)
        prefs.edit().putStringSet(KEY, saved).apply()

        prefs.edit().putInt("LIMIT_$name", 0).apply()
        FirestoreSyncManager.pushAllDataToCloud(requireContext())
    }

    private fun deleteCategory(name: String) {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        saved.remove(name)
        prefs.edit().putStringSet(KEY, saved).apply()
        prefs.edit().remove("LIMIT_$name").apply()

        HistoryDataManager.deleteCategory(requireContext(), name)
    }

    private fun renameCategory(oldName: String, newName: String) {
        val catPrefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(catPrefs.getStringSet(KEY, emptySet()) ?: emptySet())

        if (saved.remove(oldName)) {
            saved.add(newName)
            catPrefs.edit().putStringSet(KEY, saved).apply()

            val oldLimit = catPrefs.getInt("LIMIT_$oldName", 0)
            catPrefs.edit().putInt("LIMIT_$newName", oldLimit).remove("LIMIT_$oldName").apply()

            HistoryDataManager.renameCategory(requireContext(), oldName, newName)
        }
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
        val iconView = view.findViewById<ImageView>(R.id.iconEdit)
        iconView.setImageResource(CategoryIconHelper.getIconForCategory(requireContext(), name))

        view.setOnClickListener {
            val intent = Intent(requireContext(), CategoryAnalysisActivity::class.java)
            intent.putExtra("CATEGORY_NAME", name)
            startActivity(intent)
        }

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val limit = prefs.getInt("LIMIT_$name", 0)
        if (limit > 0) {
            limitText.text = "Limit : ₹$limit"
            limitText.visibility = View.VISIBLE
        } else {
            limitText.visibility = View.GONE
        }

        btnLimit.setOnClickListener {
            val intent = Intent(requireContext(), SetLimitActivity::class.java)
            intent.putExtra("CATEGORY_NAME", name)
            startActivity(intent)
        }

        view.setOnLongClickListener {
            showAllocationOptions(name)
            true
        }

        categoryContainer.addView(view)
    }

    private fun showRenameDialog(name: String) {
        val density = requireContext().resources.displayMetrics.density
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val titleView = TextView(requireContext()).apply {
            text = "Rename Category"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val input = EditText(requireContext()).apply {
            setText(name)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_glass_input))
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 10)
            }
        }
        box.addView(input)

        val errorView = TextView(requireContext()).apply {
            setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(10, 0, 0, 40)
            }
        }
        box.addView(errorView)

        val buttonContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val dialog = AlertDialog.Builder(requireContext()).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(requireContext()).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
            minHeight = 150
            setPadding(30,30,30,30)
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = Button(requireContext()).apply {
            text = "Save"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
            minHeight = 150
            setPadding(30,30,30,30)
            setOnClickListener {
                errorView.visibility = android.view.View.GONE
                val newName = input.text.toString().trim().replace("|", "-")
                if (newName.equals("Overall", ignoreCase = true)) {
                    input.error = "'Overall' is a reserved name"
                    errorView.text = "'Overall' is a reserved name"
                    errorView.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                
                val currentSaved = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()
                if (!newName.equals(name, ignoreCase = true) && currentSaved.any { it.equals(newName, ignoreCase = true) }) {
                    input.error = "Allocation name already exists"
                    errorView.text = "Allocation name already exists"
                    errorView.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }

                if (newName.isNotEmpty()) {
                    renameCategory(name, newName)
                    FirestoreSyncManager.pushAllDataToCloud(requireContext())
                    refreshUI()
                }
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnSave)
        box.addView(buttonContainer)
        dialog.show()
    }

    private fun showDeleteConfirmDialog(name: String) {
        val density = requireContext().resources.displayMetrics.density
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val titleView = TextView(requireContext()).apply {
            text = "Delete Allocation - $name?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, requireContext().resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        box.addView(titleView)

        val messageView = TextView(requireContext()).apply {
            text = "Deleting this allocation will also delete all associated expenses of $name. Continue?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, requireContext().resources.getDimension(R.dimen.text_body))
            setTextColor(ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (32 * density).toInt())
            setLineSpacing(8f, 1f)
        }
        box.addView(messageView)

        val buttonContainer = LinearLayout(requireContext()).apply { 
            orientation = LinearLayout.HORIZONTAL 
            clipChildren = false
            clipToPadding = false
        }

        val dialog = AlertDialog.Builder(requireContext()).setView(box).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(requireContext()).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
            minHeight = 150
            setPadding(30,30,30,30)
            setOnClickListener {
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnCancel)

        val btnDelete = Button(requireContext()).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
            val tv = android.util.TypedValue(); context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
            minHeight = 150
            setPadding(30,30,30,30)
            setOnClickListener {
                deleteCategory(name)
                FirestoreSyncManager.pushAllDataToCloud(requireContext())
                refreshUI()
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnDelete)
        box.addView(buttonContainer)
        dialog.show()
    }

    private fun showAllocationOptions(name: String) {
        val bottomSheet = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        
        val density = requireContext().resources.displayMetrics.density
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * density).toInt()
            setPadding(p, p, p, (32 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val title = TextView(requireContext()).apply {
            text = name
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (24 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        container.addView(title)

        // RENAME OPTION
        val btnRename = Button(requireContext()).apply {
            text = "Rename Allocation"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                showRenameDialog(name)
            }
        }
        container.addView(btnRename)

        // CHANGE ICON OPTION
        val btnChangeIcon = Button(requireContext()).apply {
            text = "Change Allocator Icon"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                // Launch custom picker logic directly using fragment context
                val box = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (28 * density).toInt()
                    setPadding(p, p, p, (24 * density).toInt())
                    setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
                }

                val titleView = TextView(requireContext()).apply {
                    text = "Select Category Icon"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
                    setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
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

                val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setView(box)
                    .create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                icons.forEach { (iconName, resId) ->
                    val btn = Button(requireContext()).apply {
                        text = iconName
                        isAllCaps = false
                        setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))

                        if (resId == R.drawable.ic_edit) {
                            val drw = androidx.core.content.ContextCompat.getDrawable(requireContext(), resId)
                            drw?.let {
                                val iconSize = (24 * density).toInt()
                                val h = if (it.intrinsicWidth > 0) (iconSize * it.intrinsicHeight) / it.intrinsicWidth else iconSize
                                it.setBounds(0, 0, iconSize, h)
                            }
                            val tinted = com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(requireContext(), drw)
                            setCompoundDrawables(tinted, null, null, null)
                        } else {
                            val drw = androidx.core.content.ContextCompat.getDrawable(requireContext(), resId)
                            val tinted = com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(requireContext(), drw)
                            setCompoundDrawablesWithIntrinsicBounds(tinted, null, null, null)
                        }

                        compoundDrawablePadding = (12 * density).toInt()
                        setPadding((16 * density).toInt(), 0, 0, 0)
                        gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                        background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()
                        ).apply { setMargins(0, 0, 0, (12 * density).toInt()) }

                        setOnClickListener {
                            val prefs = requireContext().getSharedPreferences("CategoryPrefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putInt("ICON_$name", resId).apply()
                            
                            // Sync back immediately
                            FirestoreSyncManager.pushAllDataToCloud(requireContext())
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                                .sendBroadcast(android.content.Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))
                            
                            refreshUI()
                            dialog.dismiss()
                        }
                    }
                    box.addView(btn)
                }

                val btnCancel = Button(requireContext()).apply {
                    text = "Cancel"
                    isAllCaps = false
                    setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(requireContext(), R.attr.textPrimaryColor))
                    background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (50 * density).toInt()
                    )
                    setOnClickListener { dialog.dismiss() }
                }
                box.addView(btnCancel)

                dialog.show()
            }
        }
        container.addView(btnChangeIcon)

        // DELETE OPTION
        val btnDelete = Button(requireContext()).apply {
            text = "Delete Allocation"
            isAllCaps = false
            setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(requireContext(), tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
            setOnClickListener {
                bottomSheet.dismiss()
                showDeleteConfirmDialog(name)
            }
        }
        container.addView(btnDelete)

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }
}
