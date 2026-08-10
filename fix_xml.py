import sys

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\res\layout\activity_notification.xml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old_rv = '''        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvNotifications"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:paddingBottom="16dp"
            android:clipToPadding="false"
            android:paddingStart="25dp"
            android:paddingEnd="25dp"
            android:paddingTop="4dp"
            android:isScrollContainer="true"
            android:scrollbarStyle="outsideOverlay" />

        <TextView
            android:id="@+id/tvEmptyNotifications"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:padding="40dp"
            android:text="No notifications yet.\\n\\nWe'll notify you when your support\\nqueries are resolved!"
            android:textColor="?attr/textMutedColor"
            android:textSize="@dimen/text_subhead"
            android:visibility="gone"/>'''

new_vp = '''        <androidx.viewpager2.widget.ViewPager2
            android:id="@+id/viewPagerNotifications"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />'''

content = content.replace(old_rv, new_vp)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
