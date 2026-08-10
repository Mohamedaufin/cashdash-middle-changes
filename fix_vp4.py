import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: ViewPager inner adapter refresh
old_applyfilter_vp = '''        if (::pagerAdapter.isInitialized) {
            pagerAdapter.allList = allNotifications
            pagerAdapter.respondedList = allNotifications.filter { !it.isPending }
            pagerAdapter.pendingList = allNotifications.filter { it.isPending }
            pagerAdapter.notifyDataSetChanged()
        }'''

new_applyfilter_vp = '''        if (::pagerAdapter.isInitialized) {
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
        }'''
content = content.replace(old_applyfilter_vp, new_applyfilter_vp)

# Fix 2: Duplicate cleanup deleting new queries
old_cleanup = '''                for (group in grouped.values) {
                    if (group.size > 1) {
                        val keeper = group.find { (it.getString("reply") ?: "Waiting for reply...") != "Waiting for reply..." }
                            ?: group.maxByOrNull { it.getLong("timestamp") ?: 0L } ?: group.first()
                        group.forEach { if (it.id != keeper.id) toDelete.add(it) }
                    }
                }'''

new_cleanup = '''                for (group in grouped.values) {
                    if (group.size > 1) {
                        // Only delete duplicates that were created within 5 minutes of each other (prevents double tap)
                        // Or if they are exact duplicates in reply status
                        val sorted = group.sortedByDescending { it.getLong("timestamp") ?: 0L }
                        var keeper = sorted.first()
                        
                        // If any has a reply, prioritize keeping the one with a reply ONLY if they were created very close in time
                        // Actually, let's just avoid deleting NEW queries.
                        // If the newest one is Pending, and an older one is Responded, they are separate queries. Don't group them.
                        // We will only delete if they have the exact same reply status, or if they are within 5 minutes.
                        
                        val keepList = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
                        for (doc in sorted) {
                            val docReply = doc.getString("reply") ?: "Waiting for reply..."
                            val docTime = doc.getLong("timestamp") ?: 0L
                            
                            val isDuplicateOfKept = keepList.any { kept ->
                                val keptReply = kept.getString("reply") ?: "Waiting for reply..."
                                val keptTime = kept.getLong("timestamp") ?: 0L
                                
                                val sameReplyStatus = (docReply == "Waiting for reply...") == (keptReply == "Waiting for reply...")
                                val closeInTime = Math.abs(docTime - keptTime) < 5 * 60 * 1000L // 5 minutes
                                
                                sameReplyStatus || closeInTime
                            }
                            
                            if (!isDuplicateOfKept) {
                                keepList.add(doc)
                            } else {
                                toDelete.add(doc)
                            }
                        }
                    }
                }'''

content = content.replace(old_cleanup, new_cleanup)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
