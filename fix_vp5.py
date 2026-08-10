import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Enable User Input on ViewPager2
content = content.replace('viewPager.isUserInputEnabled = false', 'viewPager.isUserInputEnabled = true')


# 2. Add OnItemTouchListener to inner RecyclerView
old_init = '''            init {
                rvPage.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NotificationActivity)
                rvPage.adapter = adapter
            }'''

new_init = '''            init {
                rvPage.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NotificationActivity)
                rvPage.adapter = adapter
                
                // Magic touch interceptor to allow right-to-left swipe-to-delete
                // while preserving left-to-right ViewPager page swiping
                rvPage.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
                    var startX = 0f
                    var startY = 0f
                    
                    override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
                        when (e.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                startX = e.x
                                startY = e.y
                                // Don't disallow yet, wait for move
                                rv.parent.requestDisallowInterceptTouchEvent(false)
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val dx = e.x - startX
                                val dy = e.y - startY
                                // If scrolling horizontally more than vertically
                                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 10f) {
                                    if (dx < 0) {
                                        // User is swiping right-to-left (negative dx) -> Delete item!
                                        // Disallow ViewPager from intercepting so ItemTouchHelper gets the event
                                        rv.parent.requestDisallowInterceptTouchEvent(true)
                                    } else {
                                        // User is swiping left-to-right (positive dx) -> Change page!
                                        // Let ViewPager intercept it
                                        rv.parent.requestDisallowInterceptTouchEvent(false)
                                    }
                                }
                            }
                        }
                        return false
                    }

                    override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent) {}
                    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
                })
            }'''

content = content.replace(old_init, new_init)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
