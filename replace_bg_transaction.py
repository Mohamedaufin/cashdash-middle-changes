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
    
    # Replace variable.setBackgroundResource(R.drawable.bg_transaction)
    # -> variable.setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(variable.context, R.drawable.bg_transaction))
    new_content = re.sub(
        r'([a-zA-Z0-9_]+)\.setBackgroundResource\(R\.drawable\.bg_transaction\)',
        r'\1.setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(\1.context, R.drawable.bg_transaction))',
        content
    )
    
    # Replace direct setBackgroundResource(R.drawable.bg_transaction)
    # -> setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this, R.drawable.bg_transaction))
    new_content = re.sub(
        r'(?<![a-zA-Z0-9_]\.)setBackgroundResource\(R\.drawable\.bg_transaction\)',
        r'setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this, R.drawable.bg_transaction))',
        new_content
    )
    
    if new_content != content:
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        kt_count += 1

print(f"Replaced bg_transaction in {kt_count} Kotlin files.")

# XML Files
layout_dir = r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout"
xml_files = glob.glob(os.path.join(layout_dir, "*.xml"))

xml_count = 0
for f in xml_files:
    with open(f, "r", encoding="utf-8") as file:
        content = file.read()
    
    if '@drawable/bg_transaction"' in content:
        new_content = content.replace('@drawable/bg_transaction"', '?attr/transactionItemBackground"')
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        xml_count += 1

print(f"Replaced bg_transaction in {xml_count} XML files.")
