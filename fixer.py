
import re

with open("app/src/main/java/com/cash/dash/ReportActivity.kt", "r", encoding="utf-8") as f:
    lines = f.readlines()

new_lines = []
skip = False
for i, line in enumerate(lines):
    if "private lateinit var layoutContent: LinearLayout" in line:
        new_lines.append("    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2\n")
    elif "private lateinit var gestureDetector:" in line:
        continue
    elif "layoutContent = findViewById(R.id.reportContent)" in line:
        new_lines.append("        viewPager = findViewById(R.id.viewPager)\n")
        new_lines.append("        viewPager.adapter = ReportPagerAdapter()\n")
        new_lines.append("        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {\n")
        new_lines.append("            override fun onPageSelected(position: Int) {\n")
        new_lines.append("                val btnId = when(position) { 0 -> R.id.btnWeekly; 1 -> R.id.btnMonthly; else -> R.id.btnCustom }\n")
        new_lines.append("                if (toggleMode.checkedButtonId != btnId) toggleMode.check(btnId)\n")
        new_lines.append("            }\n")
        new_lines.append("        })\n")
    elif "gestureDetector = android.view.GestureDetector" in line:
        skip = True
    elif skip and "})" in line and "return false" in lines[i-1]:
        skip = False
    elif skip and "return super.dispatchTouchEvent(ev)" in line and "}" in lines[i+1]:
        # wait, gestureDetector onTouchEvent was in dispatchTouchEvent
        pass
    else:
        if not skip:
            new_lines.append(line)

# Handle toggleMode addOnButtonCheckedListener to change viewpager item
code = "".join(new_lines)
toggle_replace = """toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isMonthlyMode = (checkedId == R.id.btnMonthly)
                isCustomMode = (checkedId == R.id.btnCustom)
                updateToggleHighlight(checkedId)
                
                val targetPage = when (checkedId) {
                    R.id.btnWeekly -> 0
                    R.id.btnMonthly -> 1
                    else -> 2
                }
                if (viewPager.currentItem != targetPage) {
                    viewPager.currentItem = targetPage
                }

                if (isCustomMode) {
                    btnPeriodSelect.visibility = View.GONE
                    layoutCustomDates.visibility = View.VISIBLE
                    if (customStartMillis > 0 && customEndMillis > 0) {
                        viewPager.adapter?.notifyDataSetChanged()
                    }
                } else {
                    btnPeriodSelect.visibility = View.VISIBLE
                    layoutCustomDates.visibility = View.GONE
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!isMonthlyMode) {
                            selectedWeekIndex = getWeekIndexForNow()
                        }
                        withContext(Dispatchers.Main) {
                            updatePeriodLabel()
                        }
                    }
                }
            }
        }"""
code = re.sub(r"toggleMode\.addOnButtonCheckedListener \{.*?\}\n        \}", toggle_replace, code, flags=re.DOTALL)

# Delete dispatchTouchEvent and navigateTab
code = re.sub(r"    override fun dispatchTouchEvent.*?return super\.dispatchTouchEvent\(ev\)\n    \}\n", "", code, flags=re.DOTALL)
code = re.sub(r"    private fun navigateTab\(forward: Boolean\).*?    \}\n", "", code, flags=re.DOTALL)

# Add ReportPagerAdapter class before updateToggleHighlight
adapter_class = """
    inner class ReportPagerAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<ReportPagerAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val reportContent: LinearLayout = view.findViewById(R.id.reportContent)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_report_page, parent, false)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            loadReportForPage(holder.reportContent, position)
        }
        override fun getItemCount() = 3
    }
"""
code = code.replace("    /** Applies a strong active-tab highlight", adapter_class + "\n    /** Applies a strong active-tab highlight")

# Modify loadReport to loadReportForPage
code = code.replace("private fun loadReport() {", "private fun loadReportForPage(container: LinearLayout, pageIndex: Int) {")
code = code.replace("layoutContent.removeAllViews()", "container.removeAllViews()")
code = re.sub(r"(private fun loadReportForPage.*?try \{)(.*?)(withContext\(Dispatchers\.Main\) \{)",
    r"\1\n                val isMonthly = (pageIndex == 1)\n                val isCustom = (pageIndex == 2)\n                val insights = FinancialInsightsManager.generateReport(\n                    this@ReportActivity, isMonthly, isCustom, customStartMillis, customEndMillis, currentMonth, currentYear, if (isMonthly) -1 else selectedWeekIndex\n                )\n                \3", code, flags=re.DOTALL)

code = code.replace("renderReport(insights)", "renderReport(insights, container)")
code = code.replace("private fun renderReport(insights: FinancialInsightsManager.AdvisoryInsights) {", "private fun renderReport(insights: FinancialInsightsManager.AdvisoryInsights, container: LinearLayout) {")
code = code.replace("addEmptyStateCard()", "addEmptyStateCard(container)")
code = code.replace("injectPieChartCard(insights)", "injectPieChartCard(insights, container)")
code = code.replace("addCard(", "addCard(container, ")

# fix function signatures
code = code.replace("private fun addEmptyStateCard() {", "private fun addEmptyStateCard(container: LinearLayout) {")
code = code.replace("private fun addCard(title: String, value: String, subtitle: String, iconRes: Int, builder: (LinearLayout.() -> Unit)? = null) {", "private fun addCard(container: LinearLayout, title: String, value: String, subtitle: String, iconRes: Int, builder: (LinearLayout.() -> Unit)? = null) {")
code = code.replace("private fun injectPieChartCard(insights: FinancialInsightsManager.AdvisoryInsights) {", "private fun injectPieChartCard(insights: FinancialInsightsManager.AdvisoryInsights, container: LinearLayout) {")

# fix layoutContent.addView
code = code.replace("layoutContent.addView", "container.addView")
# fix layoutInflater for card
code = code.replace("layoutInflater.inflate(R.layout.item_report_card, layoutContent, false)", "layoutInflater.inflate(R.layout.item_report_card, container, false)")

# Replace stray loadReport() with viewPager updates
code = code.replace("loadReport()", "viewPager.adapter?.notifyDataSetChanged()")

with open("app/src/main/java/com/cash/dash/ReportActivity.kt", "w", encoding="utf-8") as f:
    f.write(code)
