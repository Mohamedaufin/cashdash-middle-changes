package com.cash.dash

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatementActivity : ThemedActivity() {

    private lateinit var tvDateRange: TextView
    private lateinit var tvTotal: TextView
    private lateinit var rvTransactions: RecyclerView
    private var startMillis: Long = 0
    private var endMillis: Long = 0
    private var categoryFilter: String = "Overall"

    private fun setupUI() {
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        tvDateRange.text = "${sdf.format(java.util.Date(startMillis))} – ${sdf.format(java.util.Date(endMillis))}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statement)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val topBarView = findViewById<View>(R.id.topBar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.stmtViewRoot)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            // Top Bar Margin
            val params = topBarView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topMargin = systemBars.top
            topBarView.layoutParams = params

            // Sticky Download Bar (Absolute Edge-to-Edge)
            val btnDownload = findViewById<View>(R.id.btnDownload)
            val btnParams = btnDownload.layoutParams as android.view.ViewGroup.MarginLayoutParams
            btnParams.bottomMargin = navBarHeight
            btnDownload.layoutParams = btnParams

            insets
        }


        tvDateRange = findViewById(R.id.tvDateRange)
        tvTotal = findViewById(R.id.tvTotalAmount)
        rvTransactions = findViewById(R.id.rvTransactions)

        startMillis = intent.getLongExtra("START_MILLIS", 0)
        endMillis = intent.getLongExtra("END_MILLIS", 0)
        categoryFilter = intent.getStringExtra("CATEGORY_FILTER") ?: "Overall"

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDownload).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                PdfReportManager.generateStandaloneStatement(this@StatementActivity, startMillis, endMillis, categoryFilter)
            }
        }

        setupUI()
        
        // Fix: Use Coroutines to fetch data from Room (No Main Thread access)
        lifecycleScope.launch(Dispatchers.IO) {
            val breakdown = HistoryDataManager.getCategoryBreakdownForRange(
                this@StatementActivity, startMillis, endMillis, categoryFilter
            )
            
            // Ascending sort (April 1, April 2, etc.)
            val statementList = breakdown.transactions.sortedBy { entry ->
                val p = entry.rawEntry.split("|")
                if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            }
            
            val totalSpent = statementList.sumOf { it.amount.toDouble() }.toFloat()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateUI(statementList, totalSpent)
            }
        }
    }

    private fun updateUI(statementList: List<TransactionItem>, totalSpent: Float) {
        tvTotal.text = "₹${String.format("%,.2f", totalSpent)}"

        val tvTotalLabel = findViewById<TextView>(R.id.tvTotalLabel)
        if (categoryFilter == "Overall") {
            tvTotalLabel.text = "CUMULATIVE EXPENDITURE"
        } else {
            val displayCat = if (categoryFilter.equals("no choice", ignoreCase = true)) "No Allocation" else categoryFilter
            tvTotalLabel.text = "${displayCat.uppercase()} EXPENDITURE"
        }

        rvTransactions.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvTransactions.adapter = TransactionAdapter(statementList, showTimestamp = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("startMillis", startMillis)
        outState.putLong("endMillis", endMillis)
        outState.putString("categoryFilter", categoryFilter)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        startMillis = savedInstanceState.getLong("startMillis", startMillis)
        endMillis = savedInstanceState.getLong("endMillis", endMillis)
        categoryFilter = savedInstanceState.getString("categoryFilter", "Overall")
        
        setupUI()
        lifecycleScope.launch(Dispatchers.IO) {
            val breakdown = HistoryDataManager.getCategoryBreakdownForRange(
                this@StatementActivity, startMillis, endMillis, categoryFilter
            )
            val statementList = breakdown.transactions.sortedBy { entry ->
                val p = entry.rawEntry.split("|")
                if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            }
            val totalSpent = statementList.sumOf { it.amount.toDouble() }.toFloat()

            withContext(Dispatchers.Main) {
                updateUI(statementList, totalSpent)
            }
        }
    }
}
