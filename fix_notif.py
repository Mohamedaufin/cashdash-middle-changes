import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Variables
content = content.replace('private lateinit var adapter: NotificationAdapter', 'private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2\n    private lateinit var pagerAdapter: NotificationPagerAdapter')

# 2. setupRecyclerView call
content = content.replace('setupRecyclerView()', 'setupViewPager()')

# 3. filter setting
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

# 4. updateList logic
old_updatelist = '''        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
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

# Fix applyFilter method name
content = content.replace('adapter.updateList(filteredNotifications)', '// adapter.updateList(filteredNotifications)')
content = content.replace('adapter.notifyDataSetChanged()', 'if (::pagerAdapter.isInitialized) pagerAdapter.notifyDataSetChanged()')
content = content.replace('adapter = NotificationAdapter(mutableListOf(),\n            onDelete = { model -> showDeleteConfirmDialog(model) }\n        )\n        rv.adapter = adapter', '')

# 5. Add setupViewPager and PagerAdapter at the end of class
setup_vp = '''    private fun setupViewPager() {
        viewPager = findViewById(R.id.viewPagerNotifications)
        pagerAdapter = NotificationPagerAdapter()
        viewPager.adapter = pagerAdapter
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
content = content.replace('    private fun setupRecyclerView() {\n        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)\n        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)\n        \n    }', '')

content = content.rsplit('}', 1)[0] + setup_vp

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
