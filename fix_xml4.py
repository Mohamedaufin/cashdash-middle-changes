import os
import re

def do_fix(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    for i in range(1, 5):
        # We need to match <LinearLayout ... android:id="@+id/slotImageX" ...>
        # using a regex that escapes the special characters.
        # But wait, + is special in regex, so we must escape it: \+
        # Let's just avoid regex for finding the start, and use string find.
        
        # Look for android:id="@+id/slotImageX"
        search_str = f'android:id="@+id/slotImage{i}"'
        
        idx = content.find(search_str)
        if idx == -1:
            continue
            
        # Find the start of the <LinearLayout that encloses this.
        # Search backwards for <LinearLayout
        start_idx = content.rfind('<LinearLayout', 0, idx)
        
        # Just to be safe, make sure it's a LinearLayout being replaced.
        # If it's a FrameLayout (already fixed), skip.
        start_tag_test = content.rfind('<FrameLayout', 0, idx)
        if start_tag_test > start_idx:
            # It's inside a FrameLayout, already fixed!
            continue
            
        end_idx = content.find('</LinearLayout>', idx)
        if end_idx == -1:
            continue
        end_idx += len('</LinearLayout>')
        
        block = content[start_idx:end_idx]
        
        margin_end_match = re.search(r'android:layout_marginEnd="(\d+dp)"', block[:block.find('>')])
        margin_end = f'\n                    android:layout_marginEnd="{margin_end_match.group(1)}"' if margin_end_match else ''
        
        frame_image_match = re.search(f'(<FrameLayout\s+android:id="@+id/frameImage{i}".*?</FrameLayout>)', block, re.DOTALL)
        btn_trash_match = re.search(f'(<ImageButton\s+android:id="@+id/btnTrash{i}".*?/>)', block, re.DOTALL)
        img_eye_match = re.search(f'(<ImageView\s+android:id="@+id/imgEye{i}".*?/>)', block, re.DOTALL)
        tv_view_match = re.search(f'(<TextView\s+android:id="@+id/tvView{i}".*?/>)', block, re.DOTALL)
        
        if not (frame_image_match and btn_trash_match and img_eye_match and tv_view_match):
            continue
            
        frame_image = frame_image_match.group(1).replace('android:layout_gravity="bottom|start"\n                            ', '')
        frame_image = frame_image.replace('android:layout_gravity="bottom|start"', '')
        btn_trash = btn_trash_match.group(1)
        img_eye = img_eye_match.group(1)
        tv_view = tv_view_match.group(1)
        
        indent = "                " if "dialog_contact_us" in file_path else "                    "
        
        new_block = f'''<FrameLayout
{indent}    android:id="@+id/slotImage{i}"
{indent}    android:layout_width="wrap_content"
{indent}    android:layout_height="wrap_content"{margin_end}
{indent}    android:clipChildren="false"
{indent}    android:clipToPadding="false">

{indent}    <LinearLayout
{indent}        android:layout_width="wrap_content"
{indent}        android:layout_height="wrap_content"
{indent}        android:orientation="vertical"
{indent}        android:gravity="center_horizontal"
{indent}        android:layout_marginTop="6dp"
{indent}        android:layout_marginEnd="6dp">

{indent}        {frame_image}

{indent}        {img_eye}

{indent}        {tv_view}
{indent}    </LinearLayout>

{indent}    {btn_trash}
{indent}</FrameLayout>'''
        
        content = content[:start_idx] + new_block + content[end_idx:]

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)

do_fix('app/src/main/res/layout/activity_contact_support.xml')
do_fix('app/src/main/res/layout/dialog_contact_us.xml')
