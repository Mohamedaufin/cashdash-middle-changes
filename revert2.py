import re
with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'r', encoding='utf-8') as f:
    text = f.read()

glare_target = '''            <!-- 3D Glare effect -->
            <View
                android:id="@+id/introScanGlare"
                android:layout_width="600dp"
                android:layout_height="200dp"
                android:layout_gravity="center"
                android:rotation="45"
                android:translationY="-400dp"
                android:background="@drawable/bg_intro_glare"
                android:alpha="0" />'''

text = text.replace(glare_target, '')

with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'w', encoding='utf-8') as f:
    f.write(text)
