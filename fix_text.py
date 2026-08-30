import re
with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'r', encoding='utf-8') as f:
    text = f.read()

# Replace Row 2 text
text = text.replace('android:text="Lottu losuku"', 'android:text="Shopping"')
# Replace Row 2 limit
text = text.replace('android:text="Limit: &#8377;2"', 'android:text="Limit: &#8377;200"')

# Replace Row 3 text
text = text.replace('android:id="@+id/introScanAllocShopping"', 'android:id="@+id/introScanAllocTravel"')
text = text.replace('android:src="@drawable/ic_category_shopping"', 'android:src="@drawable/ic_category_transport"')
# Note: we replaced the second occurrence of Shopping (which was originally row 3)
# Actually, let's be careful.
