import os
import glob
import re

java_dir = r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash"
files = glob.glob(os.path.join(java_dir, "**", "*.kt"), recursive=True)

count = 0
for f in files:
    if f.endswith("ThemeHelper.kt"):
        continue
    
    with open(f, "r", encoding="utf-8") as file:
        content = file.read()
    
    # We will specifically target the ContextCompat calls to safely extract the context variable
    new_content = re.sub(
        r'ContextCompat\.getDrawable\(([^,]+),\s*R\.drawable\.bg_glass_input\)', 
        r'ContextCompat.getDrawable(\1, com.cash.dash.ThemeHelper.getDrawable(\1, R.drawable.bg_glass_input))', 
        content
    )
    
    # Target raw references that were missed (e.g. returning the ID directly)
    if new_content == content and 'R.drawable.bg_glass_input' in content:
        # For the raw references, we'll try to just wrap it assuming `this` or context is available.
        # But wait, looking at DetailHistoryActivity line 554, it might be in a when block.
        # Let's just do a naive replacement to `ThemeHelper.getDrawable(this, ...)` and fall back to manual if needed.
        new_content = re.sub(r'(?<!ThemeHelper\.getDrawable\([^,]+, )R\.drawable\.bg_glass_input', r'com.cash.dash.ThemeHelper.getDrawable(this, R.drawable.bg_glass_input)', new_content)

    if new_content != content:
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        count += 1

print(f"Replaced in {count} Kotlin files.")
