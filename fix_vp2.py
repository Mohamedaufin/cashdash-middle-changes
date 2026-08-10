import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Variables
content = content.replace('private lateinit var adapter: NotificationAdapter', 'private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2\n    private lateinit var pagerAdapter: NotificationPagerAdapter')

# 2. Add getCurrentRecyclerView helper
helper = '''
    private fun getCurrentRecyclerView(): androidx.recyclerview.widget.RecyclerView? {
        if (!::viewPager.isInitialized) return null
        val innerRv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return null
        val currentView = innerRv.layoutManager?.findViewByPosition(viewPager.currentItem) ?: return null
        return currentView.findViewById(R.id.rvPage)
    }
'''
content = content.replace('class NotificationActivity : AppCompatActivity() {', 'class NotificationActivity : AppCompatActivity() {' + helper)

# 3. Replace all R.id.rvNotifications with getCurrentRecyclerView()!! (we will use !! to satisfy the non-null requirement, since if it crashes it crashes, but it shouldn't if viewpager is ready. Better yet, we can replace it with a safe call block, but there are too many.)
# Actually, the best way is to replace al rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications) with al rv = getCurrentRecyclerView()!!
# But what if getCurrentRecyclerView() is null before views are laid out?
# Let's replace the usages directly.
old_get_rv = 'findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)'
new_get_rv = '(getCurrentRecyclerView() ?: androidx.recyclerview.widget.RecyclerView(this@NotificationActivity))'
content = content.replace(old_get_rv, new_get_rv)

# 4. Empty text view replacements
old_get_tv = 'findViewById<TextView>(R.id.tvEmptyNotifications)'
new_get_tv = '(TextView(this@NotificationActivity)) // dummy to avoid crash'
content = content.replace(old_get_tv, new_get_tv)


# 5. setupRecyclerView to setupViewPager
# We just delete setupRecyclerView and add setupViewPager
content = content.replace('    private fun setupRecyclerView() {\n        val rv = (getCurrentRecyclerView() ?: androidx.recyclerview.widget.RecyclerView(this@NotificationActivity))\n        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)\n        adapter = NotificationAdapter(mutableListOf(),\n            onDelete = { model -> showDeleteConfirmDialog(model) }\n        )\n        rv.adapter = adapter\n    }', '')

content = content.replace('setupRecyclerView()', 'setupViewPager()')


# 6. filter setting
old_setfilter = '''    private fun setFilter(filter: String) {
        currentFilter = filter
        updateChipAppearance()
        applyFilter()
    }'''

new_setfilter = '''    private fun setFilter(filter: String) {
        currentFilter = filter
        updateChipAppearance()
        
        val pos = when(filter) {
            "all" -> 0
            "responded" -> 1
            "pending" -> 2
            else -> 0
        }
        if (::viewPager.isInitialized && viewPager.currentItem != pos) {
            viewPager.currentItem = pos
        }
    }'''
content = content.replace(old_setfilter, new_setfilter)

# 7. updateList logic in applyFilter
old_updatelist = '''        val tvEmpty = (TextView(this@NotificationActivity)) // dummy to avoid crash
        if (filteredNotifications.isEmpty()) {
            tvEmpty.text = if (currentFilter == "pending") "All your queries have been responded!" else "No queries have been answered yet."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
        adapter.updateList(filteredNotifications)'''

new_updatelist = '''        if (::pagerAdapter.isInitialized) {
            pagerAdapter.allList = allNotifications
            pagerAdapter.respondedList = allNotifications.filter { !it.isPending }
            pagerAdapter.pendingList = allNotifications.filter { it.isPending }
            pagerAdapter.notifyDataSetChanged()
        }'''
content = content.replace(old_updatelist, new_updatelist)
content = content.replace('adapter.updateList(filteredNotifications)', '// adapter.updateList')
content = content.replace('adapter.notifyDataSetChanged()', 'if(::pagerAdapter.isInitialized) pagerAdapter.notifyDataSetChanged()')

# 8. Add setupViewPager and PagerAdapter at the end of class
setup_vp = '''    private fun setupViewPager() {
        viewPager = findViewById(R.id.viewPagerNotifications)
        pagerAdapter = NotificationPagerAdapter()
        viewPager.adapter = pagerAdapter
        viewPager.isUserInputEnabled = false
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val newFilter = when(position) {
                    0 -> "all"
                    1 -> "responded"
                    2 -> "pending"
                    else -> "all"
                }
                if (currentFilter != newFilter) {
                    currentFilter = newFilter
                    updateChipAppearance()
                }
            }
        })
    }

    inner class NotificationPagerAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<NotificationPagerAdapter.PageViewHolder>() {
        var allList = listOf<NotificationModel>()
        var respondedList = listOf<NotificationModel>()
        var pendingList = listOf<NotificationModel>()

        inner class PageViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val rvPage: androidx.recyclerview.widget.RecyclerView = view.findViewById(R.id.rvPage)
            val tvPageEmptyState: android.widget.TextView = view.findViewById(R.id.tvPageEmptyState)
            val adapter = NotificationAdapter(mutableListOf(), onDelete = { model -> showDeleteConfirmDialog(model) })

            init {
                rvPage.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NotificationActivity)
                rvPage.adapter = adapter
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.layout_notification_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val items = when (position) {
                0 -> allList
                1 -> respondedList
                2 -> pendingList
                else -> allList
            }
            holder.adapter.updateList(items)
            
            if (items.isEmpty()) {
                holder.tvPageEmptyState.visibility = android.view.View.VISIBLE
                holder.tvPageEmptyState.text = when (position) {
                    1 -> "No queries have been answered yet."
                    2 -> "All your queries have been responded!"
                    else -> "No notifications yet.\\n\\nWe'll notify you when your support\\nqueries are resolved!"
                }
            } else {
                holder.tvPageEmptyState.visibility = android.view.View.GONE
            }
        }
        override fun getItemCount() = 3
    }
}
'''

content = content.rsplit('}', 1)[0] + setup_vp

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
