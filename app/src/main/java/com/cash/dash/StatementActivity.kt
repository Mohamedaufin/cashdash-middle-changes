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

class StatementActivity : ThemedActivity() {

    private lateinit var tvDateRange: TextView
    private lateinit var tvTotal: TextView
    private lateinit var rvTransactions: RecyclerView
    private var startMillis: Long = 0
    private var endMillis: Long = 0
    private var categoryFilter: String = "Overall"

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
            btnDownload.setPadding(0, 0, 0, navBarHeight)
            val btnParams = btnDownload.layoutParams
            btnParams.height = (64 * resources.displayMetrics.density).toInt() + navBarHeight
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
            PdfReportManager.generateStandaloneStatement(this, startMillis, endMillis, categoryFilter)
        }

        setupUI()
        loadTransactions()
    }

    private fun setupUI() {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        tvDateRange.text = "${sdf.format(Date(startMillis))} – ${sdf.format(Date(endMillis))}"
    }

    private fun loadTransactions() {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(this, startMillis, endMillis, categoryFilter)
        // Ascending sort (April 1, April 2, etc.)
        val statementList = breakdown.transactions.sortedBy { entry ->
            val p = entry.rawEntry.split("|")
            if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
        }

        val totalSpent = statementList.sumOf { it.amount.toDouble() }.toFloat()
        val tvTotalLabel = findViewById<TextView>(R.id.tvTotalLabel)

        tvTotal.text = "₹${String.format("%,.2f", totalSpent)}"

        if (categoryFilter == "Overall") {
            tvTotalLabel.text = "CUMULATIVE EXPENDITURE"
        } else {
            tvTotalLabel.text = "${categoryFilter.uppercase()} EXPENDITURE"
        }

        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvTransactions.adapter = TransactionAdapter(statementList, showTimestamp = true)
    }
}
