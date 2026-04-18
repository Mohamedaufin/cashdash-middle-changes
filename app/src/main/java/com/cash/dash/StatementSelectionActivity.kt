package com.cash.dash

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.*

class StatementSelectionActivity : ThemedActivity() {

    private lateinit var tvStartDate: TextView
    private lateinit var tvEndDate: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var btnGenerate: MaterialButton

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

        tvStartDate = findViewById(R.id.tvStartDate)
        tvEndDate = findViewById(R.id.tvEndDate)
        chipGroup = findViewById(R.id.chipGroupAllocations)
        btnGenerate = findViewById(R.id.btnGenerate)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        updateDateLabels()

        findViewById<LinearLayout>(R.id.btnStartDate).setOnClickListener {
            showDatePicker(true)
        }

        findViewById<LinearLayout>(R.id.btnEndDate).setOnClickListener {
            showDatePicker(false)
        }

        populateCategories()

        btnGenerate.setOnClickListener {
            if (endCal.before(startCal)) {
                Toast.makeText(this, "End date must be after start date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedChipId = chipGroup.checkedChipId
            val selectedChip = findViewById<Chip>(selectedChipId)
            val category = selectedChip?.text?.toString() ?: "Overall"

            val intent = Intent(this, StatementActivity::class.java)
            intent.putExtra("START_MILLIS", startCal.timeInMillis)
            intent.putExtra("END_MILLIS", endCal.timeInMillis)
            intent.putExtra("CATEGORY_FILTER", category)
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

    private fun populateCategories() {
        val prefsCat = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val saved = prefsCat.getStringSet("categories", emptySet()) ?: emptySet()
        val categories = saved.toList().sorted()

        val isDark = ThemeHelper.isDarkMode(this)
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val chipBg = if (isDark) Color.parseColor("#1F1F2B") else Color.parseColor("#F0F0F5")

        categories.forEach { cat ->
            val chip = Chip(this)
            chip.text = cat
            chip.isCheckable = true
            chip.isCheckedIconVisible = false
            chip.chipBackgroundColor = ColorStateList.valueOf(chipBg)
            chip.setTextColor(textColor)
            chip.setChipStrokeColorResource(R.color.border)
            chip.setChipStrokeWidthResource(R.dimen.chip_stroke_width)
            
            // Apply similar style to "Overall"
            chipGroup.addView(chip)
        }
        
        // Dynamic \"no choice\" handling
        val prefsGraph = getSharedPreferences("GraphData", MODE_PRIVATE)
        val historySet = prefsGraph.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()
        val hasNoChoice = historySet.any { it.contains("|no choice|") }
        if (hasNoChoice) {
            val chip = Chip(this)
            chip.text = "no choice"
            chip.isCheckable = true
            chip.isCheckedIconVisible = false
            chip.chipBackgroundColor = ColorStateList.valueOf(chipBg)
            chip.setTextColor(textColor)
            chipGroup.addView(chip)
        }
    }
}
