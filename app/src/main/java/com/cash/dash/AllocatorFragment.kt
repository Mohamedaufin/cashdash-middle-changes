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

class AllocatorFragment : Fragment() {

    private lateinit var categoryContainer: LinearLayout
    private val PREFS = "CategoryPrefs"
    private val KEY = "categories"
    private val MAX_CATEGORIES = 7

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
        refreshUI()
    }

    private fun refreshUI() {
        if (!::categoryContainer.isInitialized) return
        categoryContainer.removeAllViews()
        loadCategories()
        addAddNewButton()
    }

    private fun loadCategories() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

        if (saved.size >= MAX_CATEGORIES) {
            ToastHelper.showToast(requireContext(), "Maximum 7 categories allowed")
            return
        }

        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            setBackgroundResource(R.drawable.bg_transaction)
        }

        val titleView = TextView(requireContext()).apply {
            text = "Add Category"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        box.addView(titleView)

        val input = EditText(requireContext()).apply {
            hint = "Enter category name"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 50)
            }
        }
        box.addView(input)

        val buttonContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(requireContext()).apply {
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

        val btnAdd = Button(requireContext()).apply {
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
                    ToastHelper.showToast(requireContext(), "'Overall' is a reserved name")
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

        val graphPrefs = requireContext().getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        graphPrefs.edit().remove("SPENT_$name").apply()

        val weekPrefs = requireContext().getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
        val weekEditor = weekPrefs.edit()
        for (w in 1..5) {
            weekEditor.remove("${name}_W$w")
        }
        weekEditor.apply()
        FirestoreSyncManager.pushAllDataToCloud(requireContext())
    }

    private fun renameCategory(oldName: String, newName: String) {
        val catPrefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = HashSet(catPrefs.getStringSet(KEY, emptySet()) ?: emptySet())

        if (saved.remove(oldName)) {
            saved.add(newName)
            catPrefs.edit().putStringSet(KEY, saved).apply()

            val oldLimit = catPrefs.getInt("LIMIT_$oldName", 0)
            catPrefs.edit().putInt("LIMIT_$newName", oldLimit).remove("LIMIT_$oldName").apply()

            val graphPrefs = requireContext().getSharedPreferences("GraphData", Context.MODE_PRIVATE)
            val oldSpent = graphPrefs.getFloat("SPENT_$oldName", 0f)
            graphPrefs.edit().putFloat("SPENT_$newName", oldSpent).remove("SPENT_$oldName").apply()

            val weekPrefs = requireContext().getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
            val weekEditor = weekPrefs.edit()
            for (w in 1..5) {
                val oldVal = weekPrefs.getInt("${oldName}_W$w", 0)
                if (oldVal > 0) {
                    weekEditor.putInt("${newName}_W$w", oldVal).remove("${oldName}_W$w")
                }
            }
            weekEditor.apply()

            val historySet = (graphPrefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()).toMutableSet()
            val newHistorySet = mutableSetOf<String>()
            var updatedCount = 0

            historySet.forEach { entry ->
                val parts = entry.split("|").toMutableList()
                if (parts.size >= 4 && parts[3] == oldName) {
                    parts[3] = newName
                    newHistorySet.add(parts.joinToString("|"))
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
        iconView.setImageResource(CategoryIconHelper.getIconForCategory(name))

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
            showRenameDialog(name)
            true
        }

        setupSwipeToDelete(view, name)
        categoryContainer.addView(view)
    }

    private fun showRenameDialog(name: String) {
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(60, 60, 60, 50)
        box.setBackgroundResource(R.drawable.bg_transaction)

        val titleView = TextView(requireContext()).apply {
            text = "Rename Category"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        box.addView(titleView)

        val input = EditText(requireContext()).apply {
            setText(name)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 50)
            }
        }
        box.addView(input)

        val buttonContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val dialog = AlertDialog.Builder(requireContext()).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(requireContext()).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(0, 0, 15, 0) }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = Button(requireContext()).apply {
            text = "Save"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(15, 0, 0, 0) }
            setOnClickListener {
                val newName = input.text.toString().trim().replace("|", "-")
                if (newName.equals("Overall", ignoreCase = true)) {
                    ToastHelper.showToast(requireContext(), "'Overall' is a reserved name")
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

    private fun setupSwipeToDelete(view: View, name: String) {
        val swipeDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null) {
                    val deltaX = e1.x - e2.x
                    val deltaY = e1.y - e2.y
                    // Highly intentional left-swipe: deltaX positive, velocity negative
                    if (Math.abs(deltaX) > Math.abs(deltaY) && deltaX > 50 && vx < -150) {
                        // "Add New" shouldn't actually be deletable
                        if (name != "ADD_NEW_PLACEHOLDER_NO_DELETE") {
                            view.animate().translationX(-view.width.toFloat()).alpha(0f).setDuration(250)
                                .withEndAction {
                                    showDeleteConfirmDialog(view, name)
                                }.start()
                        }
                        return true
                    }
                }
                return false
            }
        })

        var startX = 0f
        var startY = 0f
        var isSwiping = false
        
        val commonTouchListener = View.OnTouchListener { v, event ->
            val vp = activity?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isSwiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dX = Math.abs(event.x - startX)
                    val dY = Math.abs(event.y - startY)
                    
                    // Enhanced 200% touch detection: detect horizontal intent almost immediately
                    if (dX > 5 && dX > dY * 1.5) {
                        isSwiping = true
                        view.cancelLongPress() 
                        view.parent?.requestDisallowInterceptTouchEvent(true) 
                        vp?.isUserInputEnabled = false 
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vp?.isUserInputEnabled = true
                }
            }
            
            val handled = swipeDetector.onTouchEvent(event)
            // If we detected swiping intent, consume the event cleanly so clicks don't fire
            handled || isSwiping
        }

        view.setOnTouchListener(commonTouchListener)
        
        // Apply to interactive children to prevent them from blocking swipes, 
        // using the extracted listener to avoid recursion.
        view.findViewById<View>(R.id.btnLimit)?.setOnTouchListener { _, event ->
            commonTouchListener.onTouch(view, event)
        }
        view.findViewById<View>(R.id.iconEdit)?.setOnTouchListener { _, event ->
            commonTouchListener.onTouch(view, event)
        }
        view.findViewById<View>(R.id.categoryIcon)?.setOnTouchListener { _, event ->
            commonTouchListener.onTouch(view, event)
        }
    }

    private fun showDeleteConfirmDialog(view: View, name: String) {
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(60, 60, 60, 50)
        box.setBackgroundResource(R.drawable.bg_transaction)

        val titleView = TextView(requireContext()).apply {
            text = "Delete Allocation - $name?"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        box.addView(titleView)

        val messageView = TextView(requireContext()).apply {
            text = "Deleting this allocation will also delete all associated expenses of $name. Continue?"
            textSize = 16f
            setTextColor(Color.parseColor("#A8B5D1"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }
        box.addView(messageView)

        val buttonContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }

        val dialog = AlertDialog.Builder(requireContext()).setView(box).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = Button(requireContext()).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(0, 0, 15, 0) }
            setOnClickListener {
                view.animate().translationX(0f).alpha(1f).setDuration(200).start()
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnCancel)

        val btnDelete = Button(requireContext()).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(15, 0, 0, 0) }
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
}
