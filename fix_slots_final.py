import re

def fix_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    for i in range(1, 5):
        pattern = (r'(<LinearLayout[^>]*?android:id="@+id/slotImage' + str(i) + r'"[^>]*?>)\s*'
                   r'<FrameLayout[^>]*?>\s*'
                   r'(<FrameLayout[^>]*?android:id="@+id/frameImage' + str(i) + r'"[^>]*?>.*?</FrameLayout>)\s*'
                   r'(<ImageButton[^>]*?android:id="@+id/btnTrash' + str(i) + r'"[^>]*?/>)\s*'
                   r'</FrameLayout>\s*'
                   r'(<ImageView[^>]*?android:id="@+id/imgEye' + str(i) + r'"[^>]*?/>)\s*'
                   r'(<TextView[^>]*?android:id="@+id/tvView' + str(i) + r'"[^>]*?/>)\s*'
                   r'</LinearLayout>')
        
        match = re.search(pattern, content, re.DOTALL)
        if match:
            linear_layout = match.group(1)
            frame_image = match.group(2)
            btn_trash = match.group(3)
            img_eye = match.group(4)
            tv_view = match.group(5)
            
            # Remove marginEnd from linear_layout if exists, put it in outer FrameLayout
            margin_end_match = re.search(r'android:layout_marginEnd="([^"]+)"', linear_layout)
            margin_end = f'\n        android:layout_marginEnd="{margin_end_match.group(1)}"' if margin_end_match else ''
            
            frame_image = re.sub(r'android:layout_gravity="bottom\|start"\s*', '', frame_image)
            
            replacement = (f'<FrameLayout\n'
                           f'        android:id="@+id/slotImage{i}"\n'
                           f'        android:layout_width="wrap_content"\n'
                           f'        android:layout_height="wrap_content"{margin_end}\n'
                           f'        android:clipChildren="false"\n'
                           f'        android:clipToPadding="false">\n\n'
                           f'        <LinearLayout\n'
                           f'            android:layout_width="wrap_content"\n'
                           f'            android:layout_height="wrap_content"\n'
                           f'            android:orientation="vertical"\n'
                           f'            android:gravity="center_horizontal"\n'
                           f'            android:layout_marginTop="6dp"\n'
                           f'            android:layout_marginEnd="6dp">\n\n'
                           f'            {frame_image}\n\n'
                           f'            {img_eye}\n\n'
                           f'            {tv_view}\n'
                           f'        </LinearLayout>\n\n'
                           f'        {btn_trash}\n'
                           f'    </FrameLayout>')
            
            content = content[:match.start()] + replacement + content[match.end():]
            print(f"Replaced slotImage{i} in {path}")
            
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

fix_file('app/src/main/res/layout/activity_contact_support.xml')
fix_file('app/src/main/res/layout/dialog_contact_us.xml')
