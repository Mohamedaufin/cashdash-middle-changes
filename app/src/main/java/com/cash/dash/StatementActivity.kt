package com.cash.dash

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statement)

        // Status bar safe padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar)) { v, insets ->
            val statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, statusInsets.top + 25, v.paddingRight, v.paddingBottom)
            insets
        }

        tvDateRange = findViewById(R.id.tvDateRange)
        tvTotal = findViewById(R.id.tvTotalAmount)
        rvTransactions = findViewById(R.id.rvTransactions)

        startMillis = intent.getLongExtra("START_MILLIS", 0)
        endMillis = intent.getLongExtra("END_MILLIS", 0)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDownload).setOnClickListener {
            PdfReportManager.generateStandaloneStatement(this, startMillis, endMillis)
        }

        setupUI()
        loadTransactions()
    }

    private fun setupUI() {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        tvDateRange.text = "${sdf.format(Date(startMillis))} – ${sdf.format(Date(endMillis))}"
    }

    private fun loadTransactions() {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(this, startMillis, endMillis)
        // Ascending sort (April 1, April 2, etc.)
        val statementList = breakdown.transactions.sortedBy { entry ->
            val p = entry.rawEntry.split("|")
            if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
        }

        val totalSpent = statementList.sumOf { it.amount.toDouble() }.toFloat()
        tvTotal.text = "₹${String.format("%,.2f", totalSpent)}"

        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvTransactions.adapter = TransactionAdapter(statementList, showTimestamp = true)
    }
}
