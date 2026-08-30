import re
with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'r', encoding='utf-8') as f:
    text = f.read()

# Change third row first (from Shopping to Travel)
text = text.replace('android:id="@+id/introScanAllocShopping"', 'android:id="@+id/introScanAllocTravel"')
# In HEAD, the third row has ic_category_shopping and 'Shopping'
text = text.replace('android:src="@drawable/ic_category_shopping"', 'android:src="@drawable/ic_category_transport"')
text = text.replace('android:text="Shopping"', 'android:text="Travel"')

# Change second row (from Lottu losuku to Shopping)
text = text.replace('android:text="Lottu losuku"', 'android:text="Shopping"')
text = text.replace('android:src="@drawable/ic_pencil"', 'android:src="@drawable/ic_category_shopping"')
text = text.replace('android:text="Limit: \u20b92"', 'android:text="Limit: \u20b9200"')

with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'w', encoding='utf-8') as f:
    f.write(text)
