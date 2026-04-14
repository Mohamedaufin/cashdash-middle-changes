import os

files = [
    r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout\activity_moneyschedule.xml",
    r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout\activity_balance_setup.xml",
    r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout\success_popup.xml"
]

count = 0
for f in files:
    with open(f, "r", encoding="utf-8") as file:
        content = file.read()
    
    if '@drawable/bg_glass_panel"' in content:
        new_content = content.replace('@drawable/bg_glass_panel"', '?attr/panelBackground"')
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        count += 1

print(f"Replaced in {count} files.")
