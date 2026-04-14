import os

f = r"c:\Users\moham\AndroidStudioProjects\cashdash\app\src\main\res\layout\activity_set_limit.xml"
with open(f, "r", encoding="utf-8") as file:
    content = file.read()

new_content = content.replace('@drawable/keypad_white_rounded"', '?attr/keypadRoundedBackground"')

with open(f, "w", encoding="utf-8") as file:
    file.write(new_content)

print(f"Replaced keypad_white_rounded in activity_set_limit.")
