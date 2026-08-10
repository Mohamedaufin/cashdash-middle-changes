import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old_block = '''        filterBar.visibility = View.VISIBLE
        if (filteredNotifications.isEmpty()) {
            rv.visibility = View.GONE
            tvEmpty.text = if (currentFilter == "pending") "All your queries have been responded!" else "No queries have been answered yet."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
            // adapter.updateList

            if (!hasScrolledToUnread) {'''

new_block = '''        filterBar.visibility = View.VISIBLE
        
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.allList = allNotifications
            pagerAdapter.respondedList = allNotifications.filter { !it.isPending }
            pagerAdapter.pendingList = allNotifications.filter { it.isPending }
            pagerAdapter.notifyDataSetChanged()

            // Force update currently visible page viewholders
            val innerRv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            if (innerRv != null) {
                for (i in 0 until innerRv.childCount) {
                    val child = innerRv.getChildAt(i)
                    val holder = innerRv.getChildViewHolder(child) as? NotificationPagerAdapter.PageViewHolder
                    if (holder != null) {
                        val pos = holder.bindingAdapterPosition
                        if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            val items = when (pos) {
                                0 -> pagerAdapter.allList
                                1 -> pagerAdapter.respondedList
                                2 -> pagerAdapter.pendingList
                                else -> pagerAdapter.allList
                            }
                            holder.adapter.updateList(items)
                            if (items.isEmpty()) {
                                holder.tvPageEmptyState.visibility = android.view.View.VISIBLE
                                holder.tvPageEmptyState.text = when (pos) {
                                    1 -> "No queries have been answered yet."
                                    2 -> "All your queries have been responded!"
                                    else -> "No notifications yet.\\n\\nWe'll notify you when your support\\nqueries are resolved!"
                                }
                            } else {
                                holder.tvPageEmptyState.visibility = android.view.View.GONE
                            }
                        }
                    }
                }
            }
        }

        if (filteredNotifications.isEmpty()) {
            rv.visibility = View.GONE
            tvEmpty.text = if (currentFilter == "pending") "All your queries have been responded!" else "No queries have been answered yet."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE

            if (!hasScrolledToUnread) {'''

content = content.replace(old_block, new_block)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
