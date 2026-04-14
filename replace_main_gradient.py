import os
import glob

layout_dir = r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout"
files = glob.glob(os.path.join(layout_dir, "*.xml"))

count = 0
for f in files:
    with open(f, "r", encoding="utf-8") as file:
        content = file.read()
    
    if '@drawable/bg_main_gradient"' in content:
        new_content = content.replace('@drawable/bg_main_gradient"', '?attr/mainBackground"')
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        count += 1

print(f"Replaced in {count} files.")
