import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Disable user input
setup_vp_old = 'viewPager.adapter = pagerAdapter'
setup_vp_new = 'viewPager.adapter = pagerAdapter\n        viewPager.isUserInputEnabled = false'
content = content.replace(setup_vp_old, setup_vp_new)

# Add GestureDetector and dispatchTouchEvent
gd_code = '''    private val gestureDetector by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe Left to Right (Backswipe)
                            handlePageSwipeLeftToRight()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun handlePageSwipeLeftToRight() {
        when (currentFilter) {
            "pending" -> setFilter("responded")
            "responded" -> setFilter("all")
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setupFilters() {'''

content = content.replace('    private fun setupFilters() {', gd_code)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
