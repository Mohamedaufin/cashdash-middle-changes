
import re

with open("app/src/main/java/com/cash/dash/ReportActivity.kt", "r", encoding="utf-8") as f:
    code = f.read()

# Add adapter class
adapter_code = """
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
code = code.replace("    private fun setupViewPager() {", adapter_code + "\n    private fun setupViewPager() {")

# Update loadReport to loadReportForPage
code = code.replace("private fun loadReport() {", "private fun loadReportForPage(container: LinearLayout, pageIndex: Int) {")
# Remove layoutContent.removeAllViews() inside loadReportForPage
code = code.replace("layoutContent.removeAllViews()\n", "container.removeAllViews()\n")

# Replace isMonthlyMode and isCustomMode usage inside loadReportForPage
load_report_body_regex = r"(private fun loadReportForPage\(container: LinearLayout, pageIndex: Int\) \{.*?try \{)(.*?)(withContext\(Dispatchers\.Main\) \{)"
def load_rep_sub(m):
    return m.group(1) + """
                val isMonthly = (pageIndex == 1)
                val isCustom = (pageIndex == 2)
                val insights = FinancialInsightsManager.generateReport(
                    this@ReportActivity, isMonthly, isCustom, customStartMillis, customEndMillis, currentMonth, currentYear, if (isMonthly) -1 else selectedWeekIndex
                )
                """ + m.group(3)
code = re.sub(load_report_body_regex, load_rep_sub, code, flags=re.DOTALL)

# Add container arg to renderReport
code = code.replace("renderReport(insights)", "renderReport(insights, container)")
code = code.replace("private fun renderReport(insights: FinancialInsightsManager.AdvisoryInsights) {", "private fun renderReport(insights: FinancialInsightsManager.AdvisoryInsights, container: LinearLayout) {")
code = code.replace("layoutContent.removeAllViews()", "container.removeAllViews()")
code = code.replace("addEmptyStateCard()", "addEmptyStateCard(container)")
code = code.replace("injectPieChartCard(insights)", "injectPieChartCard(insights, container)")
code = code.replace("addCard(", "addCard(container, ")
code = code.replace("addTopCategoriesList(insights)", "addTopCategoriesList(insights, container)")
code = code.replace("addDonutChart(insights)", "addDonutChart(insights, container)")
code = code.replace("addBarChart(insights)", "addBarChart(insights, container)")

# Modify addCard signature
code = code.replace("private fun addCard(title: String, value: String, subtitle: String, iconRes: Int, builder: (LinearLayout.() -> Unit)? = null) {", "private fun addCard(container: LinearLayout, title: String, value: String, subtitle: String, iconRes: Int, builder: (LinearLayout.() -> Unit)? = null) {")
code = code.replace("layoutContent.addView(card)", "container.addView(card)")

# Modify addEmptyStateCard
code = code.replace("private fun addEmptyStateCard() {", "private fun addEmptyStateCard(container: LinearLayout) {")
code = code.replace("layoutContent.addView(cardWrapper, 0)", "container.addView(cardWrapper, 0)")

# Modify injectPieChartCard
code = code.replace("private fun injectPieChartCard(insights: FinancialInsightsManager.AdvisoryInsights) {", "private fun injectPieChartCard(insights: FinancialInsightsManager.AdvisoryInsights, container: LinearLayout) {")
code = code.replace("layoutContent.addView(cardWrapper)", "container.addView(cardWrapper)")
code = code.replace("layoutContent.addView(legendContainer)", "container.addView(legendContainer)")

# Replace layoutContent globally in functions where it was used
# Wait, some places still call layoutContent.addView.
# Let us replace all remaining layoutContent references with container where container is in scope.
with open("app/src/main/java/com/cash/dash/ReportActivity.kt", "w", encoding="utf-8") as f:
    f.write(code)
