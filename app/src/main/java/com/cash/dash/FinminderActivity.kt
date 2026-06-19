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
import android.graphics.Color
import com.google.android.material.bottomsheet.BottomSheetDialog

class FinminderActivity : ThemedActivity() {

    private lateinit var rvFinminder: RecyclerView
    private lateinit var adapter: FinminderAdapter
    private lateinit var headerRow: View
    private var currentTab = "CASH_OUT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finminder)

        val btnBack = findViewById<View>(R.id.btnBack)
        val btnMore = findViewById<android.widget.ImageButton>(R.id.btnMore)
        val toggleMode = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleMode)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        rvFinminder = findViewById(R.id.rvFinminder)
        headerRow = findViewById(R.id.headerRow)

        btnBack.setOnClickListener { finish() }
        
        btnMore.setOnClickListener {
            createShortcut()
        }

        adapter = FinminderAdapter { item ->
            deleteWithUndo(item)
        }
        rvFinminder.layoutManager = LinearLayoutManager(this)
        rvFinminder.adapter = adapter

        val tvInstruction = findViewById<TextView>(R.id.tvInstruction)

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
        
        val tvInstruction = findViewById<TextView>(R.id.tvInstruction)
        val tvEmptyState = findViewById<TextView>(R.id.tvEmptyState)
        if (filtered.isEmpty()) {
            tvInstruction.visibility = View.GONE
            tvEmptyState?.visibility = View.VISIBLE
        } else {
            tvInstruction.visibility = View.VISIBLE
            tvInstruction.text = "Press and hold on any transaction to edit."
            tvEmptyState?.visibility = View.GONE
        }
        
        adapter.submitList(filtered)
    }

    private fun deleteWithUndo(item: FinminderItem) {
        FinminderRepository.deleteItem(this, item.id)
        FirestoreSyncManager.pushAllDataToCloud(this)
        loadData() // Refresh list

        val snackbar = Snackbar.make(rvFinminder, "Tap here to undo this cashout", Snackbar.LENGTH_INDEFINITE)
        snackbar.view.setBackgroundColor(android.graphics.Color.WHITE)
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(android.graphics.Color.BLACK)
        
        var timer: CountDownTimer? = null
        
        snackbar.setAction("UNDO (7)") {
            FinminderRepository.saveItem(this, item)
            FirestoreSyncManager.pushAllDataToCloud(this)
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

    private fun createShortcut() {
        val isXiaomi = "xiaomi".equals(android.os.Build.MANUFACTURER, ignoreCase = true) || 
            "poco".equals(android.os.Build.MANUFACTURER, ignoreCase = true) || 
            "redmi".equals(android.os.Build.MANUFACTURER, ignoreCase = true)

        if (isXiaomi && !isMiuiBackgroundStartActivityAllowed(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Xiaomi/POCO Device Detected")
                .setMessage("To ensure the Finminder Widget and background popups work perfectly, please enable 'Display pop-up windows while running in the background' in App Permissions.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    try {
                        val intent = android.content.Intent("miui.intent.action.APP_PERM_EDITOR")
                        intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        intent.putExtra("extra_pkgname", packageName)
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    triggerPinWidget()
                }
                .setNegativeButton("Later", null)
                .show()
        } else {
            triggerPinWidget()
        }
    }

    private fun isMiuiBackgroundStartActivityAllowed(context: android.content.Context): Boolean {
        return try {
            val ops = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val method = ops.javaClass.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(ops, 10021, android.os.Process.myUid(), context.packageName) as Int
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun triggerPinWidget() {
        val appWidgetManager = getSystemService(android.appwidget.AppWidgetManager::class.java)
        val myProvider = android.content.ComponentName(this, Finminder::class.java)

        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val intent = android.content.Intent(this, WalletWidgetPinReceiver::class.java)
            val successCallback = android.app.PendingIntent.getBroadcast(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            val bundle = android.os.Bundle()
            val preview = android.widget.RemoteViews(packageName, R.layout.layout_finminder_pin_preview)
            bundle.putParcelable(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview)
            
            val success = appWidgetManager.requestPinAppWidget(myProvider, bundle, successCallback)
            if (!success) {
                ToastHelper.showToast(this, "Your launcher doesn't support adding widgets from here")
            }
        } else {
            ToastHelper.showToast(this, "Widget pinning not supported by your launcher")
        }
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
        val tvFrequencyInfo: TextView = view.findViewById(R.id.tvFrequencyInfo)
        val layoutExtendedInfo: android.widget.LinearLayout = view.findViewById(R.id.layoutExtendedInfo)
        val tvExtendedInfo1: TextView = view.findViewById(R.id.tvExtendedInfo1)
        val tvExtendedInfo2: TextView = view.findViewById(R.id.tvExtendedInfo2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_finminder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvQuantData.text = item.quantity
        
        val freqText = when (item.frequency) {
            "Weekly" -> "(weekly)"
            "Monthly" -> "(monthly)"
            else -> "(one time)"
        }
        holder.tvFrequencyInfo.text = freqText
        
        val typeText = if (item.type == "CASH_OUT") "cash-out" else "cash-in"
        holder.layoutExtendedInfo.visibility = if (item.frequency == "One time") View.GONE else View.VISIBLE

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
                    val date1 = sdf.format(today.time)
                    today.add(java.util.Calendar.DATE, 7)
                    val date2 = sdf.format(today.time)
                    
                    holder.tvExtendedInfo1.text = "• Weekly $typeText is set on every ${item.dateInfo}"
                    holder.tvExtendedInfo2.text = "• Next target dates are $date1 and $date2"
                    date1
                } else {
                    holder.layoutExtendedInfo.visibility = View.GONE
                    item.dateInfo
                }
            } catch (e: Exception) { 
                holder.layoutExtendedInfo.visibility = View.GONE
                item.dateInfo 
            }
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
                val date1 = sdf.format(today.time)
                
                today.add(java.util.Calendar.MONTH, 1)
                val maxDaysNext = today.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                val dayToSetNext = if (targetDay > maxDaysNext) maxDaysNext else targetDay
                today.set(java.util.Calendar.DAY_OF_MONTH, dayToSetNext)
                val date2 = sdf.format(today.time)
                
                val suffix = if (targetDay in 11..13) "th" else when (targetDay % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
                holder.tvExtendedInfo1.text = "• Monthly $typeText is set on the $targetDay$suffix of every month"
                holder.tvExtendedInfo2.text = "• Next target dates are $date1 and $date2"
                
                date1
            } catch (e: Exception) { 
                holder.layoutExtendedInfo.visibility = View.GONE
                item.dateInfo 
            }
        } else {
            holder.layoutExtendedInfo.visibility = View.GONE
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
        
        val density = holder.itemView.context.resources.displayMetrics.density


        if (isOverdue) {
            // Use the exact same glass-red drawable as the Skip Allocation button in Scanner.
            holder.itemView.setBackgroundResource(R.drawable.bg_glass_3d_red)
            holder.itemView.backgroundTintList = null
        } else {
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(R.attr.transactionBackground, typedValue, true)
            holder.itemView.setBackgroundResource(typedValue.resourceId)
            holder.itemView.backgroundTintList = null
        }
        holder.itemView.foreground = null
        // Restore XML-defined padding — setBackgroundResource can overwrite it.
        val p = (16 * density).toInt()
        holder.itemView.setPadding(p, p, p, p)
        
        holder.itemView.setOnLongClickListener {
            showOptionsBottomSheet(holder.itemView.context, item)
            true
        }
    }

    private fun showOptionsBottomSheet(context: android.content.Context, item: FinminderItem) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val density = context.resources.displayMetrics.density
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (24 * density).toInt()
            setPadding(p, p, p, (32 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val title = TextView(context).apply {
            text = item.title
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (24 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        container.addView(title)

        // EDIT OPTION
        val btnEdit = android.widget.Button(context).apply {
            val modeText = if (item.type == "CASH_OUT") "cash-out" else "cash-in"
            text = "Edit $modeText"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                val intent = android.content.Intent(context, AddFinminderActivity::class.java)
                intent.putExtra("TAB", item.type)
                intent.putExtra("ITEM_ID", item.id)
                context.startActivity(intent)
            }
        }
        container.addView(btnEdit)

        // COMPLETE / DELETE OPTION
        val btnAction = android.widget.Button(context).apply {
            isAllCaps = false
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            
            if (item.frequency == "One time") {
                text = "Mark task as completed"
                setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                val modeText = if (item.type == "CASH_OUT") "cash-out" else "cash-in"
                text = "Delete $modeText"
                setTextColor(android.graphics.Color.parseColor("#FF4D4D")) // Red color for delete
            }
            
            setOnClickListener {
                bottomSheet.dismiss()
                onDelete(item)
            }
        }
        container.addView(btnAction)
        bottomSheet.setContentView(container)
        bottomSheet.show()
    }


    override fun getItemCount() = items.size
}
