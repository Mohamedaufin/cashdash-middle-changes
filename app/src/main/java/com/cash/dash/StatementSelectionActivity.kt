package com.cash.dash

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class StatementSelectionActivity : ThemedActivity() {

    private lateinit var tvStartDate: TextView
    private lateinit var tvEndDate: TextView
    private lateinit var rvAllocations: RecyclerView
    private lateinit var btnGenerate: MaterialButton

    private var selectedCategory: String = "Overall"

    private var startCal = Calendar.getInstance().apply { 
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    private var endCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
    }

    private val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statement_selection)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val topBar = findViewById<View>(R.id.topBar)

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            val params = view.layoutParams as ConstraintLayout.LayoutParams
            params.topMargin = statusBarHeight
            view.layoutParams = params

            insets
        }

        tvStartDate = findViewById(R.id.tvStartDate)
        tvEndDate = findViewById(R.id.tvEndDate)
        rvAllocations = findViewById(R.id.rvAllocations)
        btnGenerate = findViewById(R.id.btnGenerate)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        updateDateLabels()

        findViewById<LinearLayout>(R.id.btnStartDate).setOnClickListener {
            showDatePicker(true)
        }

        findViewById<LinearLayout>(R.id.btnEndDate).setOnClickListener {
            showDatePicker(false)
        }

        setupAllocationsGrid()

        btnGenerate.setOnClickListener {
            if (endCal.before(startCal)) {
                Toast.makeText(this, "End date must be after start date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, StatementActivity::class.java)
            intent.putExtra("START_MILLIS", startCal.timeInMillis)
            intent.putExtra("END_MILLIS", endCal.timeInMillis)
            intent.putExtra("CATEGORY_FILTER", selectedCategory)
            startActivity(intent)
        }
    }

    private fun showDatePicker(isStart: Boolean) {
        val current = if (isStart) startCal else endCal
        val picker = DatePickerDialog(this, ThemeHelper.getDatePickerTheme(this), { _, y, m, d ->
            if (isStart) {
                startCal.set(y, m, d, 0, 0, 0)
            } else {
                endCal.set(y, m, d, 23, 59, 59)
            }
            updateDateLabels()
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH))
        
        picker.setTitle(if (isStart) "Select Start Date" else "Select End Date")
        picker.show()
    }

    private fun updateDateLabels() {
        tvStartDate.text = sdf.format(startCal.time)
        tvEndDate.text = sdf.format(endCal.time)
    }

    private fun setupAllocationsGrid() {
        val prefsCat = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val saved = prefsCat.getStringSet("categories", emptySet()) ?: emptySet()
        val categories = mutableListOf("Overall")
        categories.addAll(saved.toList().sorted())

        val prefsGraph = getSharedPreferences("GraphData", MODE_PRIVATE)
        val historySet = prefsGraph.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()
        if (historySet.any { it.contains("|no choice|") }) {
            categories.add("no choice")
        }

        rvAllocations.layoutManager = GridLayoutManager(this, 2)
        rvAllocations.adapter = AllocationAdapter(categories)
    }

    inner class AllocationAdapter(private val items: List<String>) : 
        RecyclerView.Adapter<AllocationAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_allocation_text_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = if (item == "no choice") "No Choice" else item
            
            val isSelected = (item == selectedCategory)
            
            // Simplified Highlight: Text only
            val activeText = ThemeHelper.resolveColorAttr(this@StatementSelectionActivity, R.attr.textPrimaryColor)
            val mutedText = ThemeHelper.resolveColorAttr(this@StatementSelectionActivity, R.attr.textMutedColor)
            
            holder.tvName.setTextColor(if (isSelected) activeText else mutedText)
            holder.tvName.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            
            // Standard background (no selection variant)
            val bgRes = ThemeHelper.getResIdFromAttr(this@StatementSelectionActivity, R.attr.inputBackground)
            holder.container.setBackgroundResource(bgRes)

            holder.container.setOnClickListener {
                if (selectedCategory != item) {
                    val oldIdx = items.indexOf(selectedCategory)
                    selectedCategory = item
                    notifyItemChanged(oldIdx)
                    notifyItemChanged(position)
                }
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvCategoryName)
            val container: View = v.findViewById(R.id.cardContainer)
        }
    }
}
