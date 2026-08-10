
import re

with open("app/src/main/java/com/cash/dash/ReportActivity.kt", "r", encoding="utf-8") as f:
    code = f.read()

# Replace global layoutContent with viewPager setup
code = re.sub(r"private lateinit var layoutContent: LinearLayout\n", "private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2\n", code)

# In onCreate, replace layoutContent = ... and gesture detection
code = re.sub(r"layoutContent = findViewById\(R\.id\.reportContent\)\n.*setupSwipeGestures\(\)", "viewPager = findViewById(R.id.viewPager)\n        setupViewPager()", code, flags=re.DOTALL)

# Add setupViewPager
setup_vp_code = """    private fun setupViewPager() {
        viewPager.adapter = ReportPagerAdapter()
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val btnId = when(position) {
                    0 -> R.id.btnWeekly
                    1 -> R.id.btnMonthly
                    else -> R.id.btnCustom
                }
                if (toggleMode.checkedButtonId != btnId) {
                    toggleMode.check(btnId)
                }
            }
        })
    }
"""
code = code.replace("private fun setupSwipeGestures() {", setup_vp_code + "\n    private fun setupSwipeGestures() {")

# Modify toggleMode listener
toggle_code = """        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
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
                        viewPager.adapter?.notifyItemChanged(2)
                    }
                } else {
                    btnPeriodSelect.visibility = View.VISIBLE
                    layoutCustomDates.visibility = View.GONE
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        if (!isMonthlyMode) {
                            selectedWeekIndex = getWeekIndexForNow()
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            updatePeriodLabel()
                        }
                    }
                }
            }
        }"""
code = re.sub(r"toggleMode\.addOnButtonCheckedListener \{.*?\n        \}", toggle_code, code, flags=re.DOTALL)

with open("app/src/main/java/com/cash/dash/ReportActivity.kt", "w", encoding="utf-8") as f:
    f.write(code)
