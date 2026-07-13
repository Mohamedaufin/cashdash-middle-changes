import re
def fix_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # We will match the entire block
    pattern = re.compile(
        r'<LinearLayout\s+android:id="@+id/slotImage(\d+)"\s+android:layout_width="wrap_content"\s+android:layout_height="wrap_content"\s+android:orientation="vertical"\s+android:gravity="center_horizontal"(\s+android:layout_marginEnd="16dp")?>\s*<FrameLayout\s+android:layout_width="61dp"\s+android:layout_height="61dp"\s+android:clipChildren="false"\s+android:clipToPadding="false">\s*<FrameLayout\s+android:id="@+id/frameImage\1"\s+android:layout_width="55dp"\s+android:layout_height="55dp"\s+android:layout_gravity="bottom\|start"(.*?)>(.*?)</FrameLayout>\s*<ImageButton\s+android:id="@+id/btnTrash\1"(.*?)/>\s*</FrameLayout>\s*<ImageView\s+android:id="@+id/imgEye\1"(.*?)\s+android:layout_marginTop="10dp"(.*?)\/>\s*<TextView\s+android:id="@+id/tvView\1"(.*?)\s+android:layout_marginTop="2dp"(.*?)\/>\s*</LinearLayout>',
        re.DOTALL
    )
    
    replacement = r'''<FrameLayout
                    android:id="@+id/slotImage\1"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"\2
                    android:clipChildren="false"
                    android:clipToPadding="false">

                    <LinearLayout
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:gravity="center_horizontal"
                        android:layout_marginTop="6dp"
                        android:layout_marginEnd="6dp">

                        <FrameLayout
                            android:id="@+id/frameImage\1"
                            android:layout_width="55dp"
                            android:layout_height="55dp"\3>\4</FrameLayout>

                        <ImageView
                            android:id="@+id/imgEye\1"\6
                            android:layout_marginTop="10dp"\7/>

                        <TextView
                            android:id="@+id/tvView\1"\8
                            android:layout_marginTop="2dp"\9/>
                    </LinearLayout>

                    <ImageButton
                        android:id="@+id/btnTrash\1"\5/>
                </FrameLayout>'''

    new_content, count = pattern.subn(replacement, content)
    print(f'Replaced {count} instances in {file_path}')
    if count > 0:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(new_content)

fix_file('app/src/main/res/layout/activity_contact_support.xml')
fix_file('app/src/main/res/layout/dialog_contact_us.xml')
