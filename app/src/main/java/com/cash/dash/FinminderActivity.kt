package com.cash.dash

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import android.os.CountDownTimer

class FinminderActivity : ThemedActivity() {

    private lateinit var rvFinminder: RecyclerView
    private lateinit var adapter: FinminderAdapter
    private lateinit var headerRow: View
    private var currentTab = "CASH_OUT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finminder)

        val btnBack = findViewById<View>(R.id.btnBack)
        val toggleMode = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleMode)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        rvFinminder = findViewById(R.id.rvFinminder)
        headerRow = findViewById(R.id.headerRow)

        btnBack.setOnClickListener { finish() }

        adapter = FinminderAdapter { item ->
            deleteWithUndo(item)
        }
        rvFinminder.layoutManager = LinearLayoutManager(this)
        rvFinminder.adapter = adapter

        toggleMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnCashOuts) {
                    currentTab = "CASH_OUT"
                } else if (checkedId == R.id.btnCashIns) {
                    currentTab = "CASH_IN"
                }
                loadData()
            }
        }

        btnAdd.setOnClickListener {
            val intent = Intent(this, AddFinminderActivity::class.java)
            intent.putExtra("TAB", currentTab)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }



    private fun loadData() {
        val allItems = FinminderRepository.getItems(this)
        var filtered = allItems.filter { it.type == currentTab }
        
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        val today = java.util.Calendar.getInstance()
        today.set(java.util.Calendar.HOUR_OF_DAY, 0)
        today.set(java.util.Calendar.MINUTE, 0)
        today.set(java.util.Calendar.SECOND, 0)
        today.set(java.util.Calendar.MILLISECOND, 0)

        filtered = filtered.sortedWith { a, b ->
            val aIsOverdue = if (a.frequency == "One time") {
                try {
                    val aDate = sdf.parse(a.dateInfo)
                    aDate != null && aDate.time <= today.timeInMillis
                } catch (e: Exception) { false }
            } else false

            val bIsOverdue = if (b.frequency == "One time") {
                try {
                    val bDate = sdf.parse(b.dateInfo)
                    bDate != null && bDate.time <= today.timeInMillis
                } catch (e: Exception) { false }
            } else false

            if (aIsOverdue && !bIsOverdue) -1
            else if (!aIsOverdue && bIsOverdue) 1
            else b.timestamp.compareTo(a.timestamp)
        }

        headerRow.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(filtered)
    }

    private fun deleteWithUndo(item: FinminderItem) {
        FinminderRepository.deleteItem(this, item.id)
        loadData() // Refresh list

        val snackbar = Snackbar.make(rvFinminder, "Tap here to undo this cashout", Snackbar.LENGTH_INDEFINITE)
        snackbar.view.setBackgroundColor(android.graphics.Color.WHITE)
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(android.graphics.Color.BLACK)
        
        var timer: CountDownTimer? = null
        
        snackbar.setAction("UNDO (7)") {
            FinminderRepository.saveItem(this, item)
            loadData()
            snackbar.dismiss()
        }
        snackbar.setActionTextColor(android.graphics.Color.RED)

        timer = object : CountDownTimer(7000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                snackbar.setAction("UNDO ($sec)") {
                    FinminderRepository.saveItem(this@FinminderActivity, item)
                    loadData()
                    snackbar.dismiss()
                }
            }
            override fun onFinish() {
                snackbar.dismiss()
            }
        }
        timer.start()

        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                timer?.cancel()
            }
        })

        snackbar.show()
    }
}

class FinminderAdapter(private val onDelete: (FinminderItem) -> Unit) : RecyclerView.Adapter<FinminderAdapter.ViewHolder>() {

    private var items = listOf<FinminderItem>()

    fun submitList(newItems: List<FinminderItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvQuantData: TextView = view.findViewById(R.id.tvQuantData)
        val tvDateInfo: TextView = view.findViewById(R.id.tvDateInfo)
        val cbFinminder: CheckBox = view.findViewById(R.id.cbFinminder)
        val ivRepeatIcon: android.widget.ImageView = view.findViewById(R.id.ivRepeatIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_finminder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvQuantData.text = item.quantity
        
        val displayDate = if (item.frequency == "Weekly") {
            try {
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                val today = java.util.Calendar.getInstance()
                
                val daysMap = mapOf(
                    "Sunday" to java.util.Calendar.SUNDAY,
                    "Monday" to java.util.Calendar.MONDAY,
                    "Tuesday" to java.util.Calendar.TUESDAY,
                    "Wednesday" to java.util.Calendar.WEDNESDAY,
                    "Thursday" to java.util.Calendar.THURSDAY,
                    "Friday" to java.util.Calendar.FRIDAY,
                    "Saturday" to java.util.Calendar.SATURDAY
                )
                
                val targetDayOfWeek = daysMap[item.dateInfo]
                if (targetDayOfWeek != null) {
                    val currentDayOfWeek = today.get(java.util.Calendar.DAY_OF_WEEK)
                    var daysToAdd = targetDayOfWeek - currentDayOfWeek
                    if (daysToAdd < 0) {
                        daysToAdd += 7
                    }
                    today.add(java.util.Calendar.DATE, daysToAdd)
                    sdf.format(today.time)
                } else {
                    item.dateInfo
                }
            } catch (e: Exception) { item.dateInfo }
        } else if (item.frequency == "Monthly") {
            try {
                val targetDay = item.dateInfo.toInt()
                val today = java.util.Calendar.getInstance()
                val currentDay = today.get(java.util.Calendar.DAY_OF_MONTH)
                if (currentDay > targetDay) {
                    today.add(java.util.Calendar.MONTH, 1)
                }
                
                val maxDays = today.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                val dayToSet = if (targetDay > maxDays) maxDays else targetDay
                today.set(java.util.Calendar.DAY_OF_MONTH, dayToSet)
                
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                sdf.format(today.time)
            } catch (e: Exception) { item.dateInfo }
        } else {
            item.dateInfo
        }

        holder.tvDateInfo.text = displayDate
        
        var isOverdue = false
        if (item.frequency == "One time") {
            try {
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                val today = java.util.Calendar.getInstance()
                today.set(java.util.Calendar.HOUR_OF_DAY, 0)
                today.set(java.util.Calendar.MINUTE, 0)
                today.set(java.util.Calendar.SECOND, 0)
                today.set(java.util.Calendar.MILLISECOND, 0)
                val itemDate = sdf.parse(item.dateInfo)
                if (itemDate != null && itemDate.time <= today.timeInMillis) {
                    isOverdue = true
                }
            } catch (e: Exception) {}
        }
        
        if (isOverdue) {
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(R.attr.transactionBackground, typedValue, true)
            holder.itemView.setBackgroundResource(R.drawable.bg_transaction_red)
            holder.itemView.backgroundTintList = null
        } else {
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(R.attr.transactionBackground, typedValue, true)
            holder.itemView.setBackgroundResource(typedValue.resourceId)
            holder.itemView.backgroundTintList = null
        }
        
        holder.cbFinminder.setOnCheckedChangeListener(null)
        holder.cbFinminder.isChecked = item.isChecked
        
        if (item.frequency == "One time") {
            holder.cbFinminder.visibility = View.VISIBLE
            holder.ivRepeatIcon.visibility = View.GONE
        } else {
            holder.cbFinminder.visibility = View.GONE
            holder.ivRepeatIcon.visibility = View.VISIBLE
        }

        val clickListener = View.OnClickListener {
            onDelete(item)
        }
        holder.cbFinminder.setOnClickListener(clickListener)
        holder.ivRepeatIcon.setOnClickListener(clickListener)
    }

    override fun getItemCount() = items.size
}
