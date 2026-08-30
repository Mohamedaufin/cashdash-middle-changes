import re
with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'r', encoding='utf-8') as f:
    text = f.read()

bad_glare = '''                <!-- 3D Glare effect -->
                <View
                    android:id="@+id/introScanGlare"
                    android:layout_width="600dp"
                    android:layout_height="200dp"
                    android:layout_gravity="center"
                    android:rotation="45"
                    android:translationY="-400dp"
                    android:background="@drawable/bg_intro_glare"
                    android:alpha="0" />
            </FrameLayout>'''

text = text.replace(bad_glare, '            </FrameLayout>')

correct_glare = '''                </LinearLayout>
            </FrameLayout>

            <!-- 3D Glare effect -->
            <View
                android:id="@+id/introScanGlare"
                android:layout_width="600dp"
                android:layout_height="200dp"
                android:layout_gravity="center"
                android:rotation="45"
                android:translationY="-400dp"
                android:background="@drawable/bg_intro_glare"
                android:alpha="0" />
        </FrameLayout>'''

text = text.replace('                </LinearLayout>\n            </FrameLayout>\n        </FrameLayout>', correct_glare)

with open('app/src/main/res/layout/view_intro_scene_scan.xml', 'w', encoding='utf-8') as f:
    f.write(text)
