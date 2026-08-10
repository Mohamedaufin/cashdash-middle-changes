import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Image Preview Glide Fixes (thumbnail and override)
# In updateReplyPreviews:
content = content.replace('Glide.with(holder.itemView.context).load(uri).into(imgView)', 'Glide.with(holder.itemView.context).load(uri).override(800).thumbnail(0.1f).into(imgView)')

# In renderAttachments:
content = content.replace('Glide.with(context)\n                    .load(url)\n                    .into(', 'Glide.with(context)\n                    .load(url)\n                    .override(800)\n                    .thumbnail(0.1f)\n                    .into(')

# In showFullscreenImagePreview
content = content.replace('Glide.with(this@NotificationActivity).load(uri).into(imgView)', 'Glide.with(this@NotificationActivity).load(uri).override(800).thumbnail(0.1f).into(imgView)')

# 2. Image Preview Instant Dismiss (GestureDetector)
# In renderAttachments:
old_click_1 = 'closeBtn.setOnClickListener { dialog.dismiss() }\n                    fullImgContainer.setOnClickListener { dialog.dismiss() }'
new_click_1 = '''closeBtn.setOnClickListener { dialog.dismiss() }
                    val gestureDetector = android.view.GestureDetector(fullImg.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                            dialog.dismiss()
                            return true
                        }
                    })
                    fullImg.setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)
                        false
                    }
                    fullImgContainer.setOnClickListener { dialog.dismiss() }'''
content = content.replace(old_click_1, new_click_1)

# In showFullscreenImagePreview:
old_click_2 = 'closeBtn.setOnClickListener { dialog.dismiss() }\n        container.setOnClickListener { dialog.dismiss() }'
new_click_2 = '''closeBtn.setOnClickListener { dialog.dismiss() }
        val gestureDetector = android.view.GestureDetector(imgView.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                dialog.dismiss()
                return true
            }
        })
        imgView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
        container.setOnClickListener { dialog.dismiss() }'''
content = content.replace(old_click_2, new_click_2)


# 3. Global Backswipe Gesture (Left-to-Right only)
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


# 4. Smooth Animation on Filter Change
old_apply_filter = '''    private fun applyFilter() {
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        if (allNotifications.isEmpty()) {'''

new_apply_filter = '''    private fun applyFilter() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        val animId = if (currentFilter == "pending") R.anim.slide_in_right else R.anim.slide_in_left
        val anim = android.view.animation.AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
        val controller = android.view.animation.LayoutAnimationController(anim)
        controller.delay = 0.1f
        controller.order = android.view.animation.LayoutAnimationController.ORDER_NORMAL
        rv.layoutAnimation = controller

        val tvEmpty = findViewById<TextView>(R.id.tvEmptyNotifications)
        if (allNotifications.isEmpty()) {'''
content = content.replace(old_apply_filter, new_apply_filter)


with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
