import re

path = r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\drawable\bg_3d_dropdown_blue.xml"
with open(path, "r", encoding="utf-8-sig") as f:
    text = f.read()

match = re.search(r"<layer-list.*</layer-list>", text, re.DOTALL)
if match:
    new_text = '<?xml version="1.0" encoding="utf-8"?>\n' + match.group(0) + '\n'
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(new_text)
    print("Sanitized successfully.")
