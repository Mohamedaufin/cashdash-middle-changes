import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\NotificationActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

helper = '''
    private fun getCurrentRecyclerView(): androidx.recyclerview.widget.RecyclerView? {
        if (!::viewPager.isInitialized) return null
        val innerRv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return null
        val currentView = innerRv.layoutManager?.findViewByPosition(viewPager.currentItem) ?: return null
        return currentView.findViewById(R.id.rvPage)
    }
'''
content = content.replace('class NotificationActivity : ThemedActivity() {', 'class NotificationActivity : ThemedActivity() {' + helper)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
