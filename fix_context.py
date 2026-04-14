import os
import glob
import re

java_dir = r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash"
kt_files = glob.glob(os.path.join(java_dir, "**", "*.kt"), recursive=True)

kt_count = 0
for f in kt_files:
    if f.endswith("ThemeHelper.kt"):
        continue
    with open(f, "r", encoding="utf-8") as file:
        content = file.read()
    
    if 'ThemeHelper.getDrawable(this, R.drawable.bg_transaction)' in content:
        new_content = content.replace('ThemeHelper.getDrawable(this, R.drawable.bg_transaction)', 
                                      'ThemeHelper.getDrawable(context, R.drawable.bg_transaction)')
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        kt_count += 1

print(f"Fixed Context issue in {kt_count} files.")
