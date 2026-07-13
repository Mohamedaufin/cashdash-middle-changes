import os

def fix_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    for i in range(1, 5):
        # We look for the start of the block for slotImage i
        # Since the spacing might vary, let's just do standard string replacements.
        # But wait, this is risky. Let's just write a script that does it correctly by finding indices.
        
        start_tag = f'<LinearLayout\n                    android:id="@+id/slotImage{i}"'
        if start_tag not in content:
            start_tag = f'<LinearLayout\n                android:id="@+id/slotImage{i}"'
            
        if start_tag not in content:
            continue
            
        start_idx = content.find(start_tag)
        end_idx = content.find('</LinearLayout>', start_idx) + len('</LinearLayout>')
        
        block = content[start_idx:end_idx]
        
        # We know the inner structure of the block
        # Let's extract the pieces we need
        import re
        margin_end_match = re.search(r'android:layout_marginEnd="(\d+dp)"', block[:block.find('>')])
        margin_end = f'\n                    android:layout_marginEnd="{margin_end_match.group(1)}"' if margin_end_match else ''
        
        frame_image_match = re.search(f'(<FrameLayout\s+android:id="@+id/frameImage{i}".*?</FrameLayout>)', block, re.DOTALL)
        btn_trash_match = re.search(f'(<ImageButton\s+android:id="@+id/btnTrash{i}".*?/>)', block, re.DOTALL)
        img_eye_match = re.search(f'(<ImageView\s+android:id="@+id/imgEye{i}".*?/>)', block, re.DOTALL)
        tv_view_match = re.search(f'(<TextView\s+android:id="@+id/tvView{i}".*?/>)', block, re.DOTALL)
        
        if not (frame_image_match and btn_trash_match and img_eye_match and tv_view_match):
            print(f"Skipping {i} in {file_path}, parts not found")
            continue
            
        frame_image = frame_image_match.group(1).replace('android:layout_gravity="bottom|start"', '')
        btn_trash = btn_trash_match.group(1)
        img_eye = img_eye_match.group(1)
        tv_view = tv_view_match.group(1)
        
        # Construct new block
        # Let's indent properly
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
    print(f"Updated {file_path}")

fix_file('app/src/main/res/layout/activity_contact_support.xml')
fix_file('app/src/main/res/layout/dialog_contact_us.xml')
