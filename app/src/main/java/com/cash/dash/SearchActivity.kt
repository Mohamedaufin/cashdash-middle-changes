package com.cash.dash

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class SearchActivity : ThemedActivity() {

    private lateinit var rvSearchResults: RecyclerView
    private lateinit var edtSearch: EditText
    private lateinit var tvNoResults: TextView
    private var allTransactions = mutableListOf<SearchTransactionItemWithTime>()
    private var displayList = mutableListOf<SearchListItem>()
    private lateinit var adapter: SearchAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        rvSearchResults = findViewById(R.id.rvSearchResults)
        edtSearch = findViewById(R.id.edtSearch)
        tvNoResults = findViewById(R.id.tvNoResults)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        setupRecyclerView()
        loadAllTransactions()

        edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterTransactions(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        edtSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(edtSearch.windowToken, 0)
        edtSearch.clearFocus()
    }

    private fun setupRecyclerView() {
        adapter = SearchAdapter(displayList)
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        rvSearchResults.adapter = adapter
    }

    private fun loadAllTransactions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@SearchActivity)
            val transactions = db.transactionDao().getTransactionsInRange(0L, Long.MAX_VALUE)
            
            allTransactions.clear()
            transactions.forEach { entity ->
                allTransactions.add(SearchTransactionItemWithTime(
                    entity.title,
                    entity.category,
                    entity.amount,
                    entity.timestamp
                ))
            }

            // Sort by time descending (Room query already does this if we want, but keeping it explicit)
            allTransactions.sortByDescending { it.timestamp }
            
            withContext(Dispatchers.Main) {
                filterTransactions("")
            }
        }
    }

    private fun filterTransactions(query: String) {
        val filtered = if (query.isEmpty()) {
            allTransactions
        } else {
            val lowerQuery = query.lowercase(Locale.getDefault())
            allTransactions.filter { 
                it.title.lowercase(Locale.getDefault()).contains(lowerQuery) 
            }
        }

        groupAndDisplay(filtered)
    }

    private fun groupAndDisplay(transactions: List<SearchTransactionItemWithTime>) {
        displayList.clear()
        
        var currentHeader: String? = null
        val headerSdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val itemSdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

        for (item in transactions) {
            val headerTitle = if (item.timestamp > 0) {
                headerSdf.format(Date(item.timestamp))
            } else {
                "Unknown Date"
            }

            if (headerTitle != currentHeader) {
                displayList.add(SearchListItem.Header(headerTitle))
                currentHeader = headerTitle
            }

            val dateStr = if (item.timestamp > 0) {
                itemSdf.format(Date(item.timestamp))
            } else {
                "Unknown Date"
            }

            displayList.add(SearchListItem.Transaction(
                SearchTransactionItem(item.title, item.category, item.amount, dateStr)
            ))
        }

        adapter.notifyDataSetChanged()
        updateVisibility()
    }

    private fun updateVisibility() {
        if (displayList.isEmpty()) {
            tvNoResults.visibility = android.view.View.VISIBLE
            rvSearchResults.visibility = android.view.View.GONE
        } else {
            tvNoResults.visibility = android.view.View.GONE
            rvSearchResults.visibility = android.view.View.VISIBLE
        }
    }
}

data class SearchTransactionItem(val title: String, val category: String, val amount: Int, val date: String)

data class SearchTransactionItemWithTime(val title: String, val category: String, val amount: Int, val timestamp: Long)

sealed class SearchListItem {
    data class Header(val title: String) : SearchListItem()
    data class Transaction(val item: SearchTransactionItem) : SearchListItem()
}
