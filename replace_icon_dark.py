import os

files = [
    r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout\item_category.xml",
    r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout\item_rigor_category.xml"
]

count = 0
for f in files:
    with open(f, "r", encoding="utf-8") as file:
        content = file.read()
    
    if '@drawable/bg_icon_dark"' in content:
        new_content = content.replace('@drawable/bg_icon_dark"', '?attr/iconBackground"')
        with open(f, "w", encoding="utf-8") as file:
            file.write(new_content)
        count += 1

print(f"Replaced in {count} files.")
