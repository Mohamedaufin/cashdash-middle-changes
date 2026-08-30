import re
with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'r', encoding='utf-8') as f:
    text = f.read()

# Row 3 changes first (to avoid conflict)
text = text.replace('android:id="@+id/introScanAllocShopping"', 'android:id="@+id/introScanAllocTravel"')
# The icon for row 3 is currently ic_category_shopping
# There is only one ic_category_shopping before we add the new one.
text = text.replace('android:src="@drawable/ic_category_shopping"', 'android:src="@drawable/ic_category_transport"')
# The text for row 3 is currently "Shopping"
# "Shopping" might appear elsewhere? Only in row 3 currently.
text = text.replace('android:text="Shopping"', 'android:text="Travel"')

# Row 2 changes
text = text.replace('android:text="Lottu losuku"', 'android:text="Shopping"')
text = text.replace('android:src="@drawable/ic_pencil"', 'android:src="@drawable/ic_category_shopping"')
text = text.replace('android:text="Limit: \u20b92"', 'android:text="Limit: \u20b9200"')

with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'w', encoding='utf-8') as f:
    f.write(text)
