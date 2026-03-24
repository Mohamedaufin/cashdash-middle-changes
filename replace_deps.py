import os
import glob
import re

files = glob.glob('app/src/main/java/com/aufin/cashdash/*.kt')
pattern = re.compile(r'window\.decorView\.systemUiVisibility\s*=\s*[^;\n]*SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN[^\n]*', re.MULTILINE)
# Since the assignments might span multiple lines, let's just do a simpler replacement

# Read first and write later
def replace_in_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # We will search for window.decorView.systemUiVisibility = ... until FULLSCREEN
    # Because it spans lines, we use DOTALL or just a carefully crafted regex
    
    new_content = re.sub(
        r'window\.decorView\.systemUiVisibility\s*=\s*.*?SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN\)?',
        'androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)',
        content,
        flags=re.DOTALL
    )
    
    if new_content != content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print("Updated: " + path)

for f in files:
    replace_in_file(f)
