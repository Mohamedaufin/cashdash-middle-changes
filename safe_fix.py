import re

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

out = []
i = 0
while i < len(lines):
    line = lines[i]
    if 'phoneContainer.animate().scaleX' in line and 'rotationX' in line:
        out.append('// ' + line)
        # Check if the next line starts with .setDuration
        if i + 1 < len(lines) and '.setDuration' in lines[i+1]:
            out.append('// ' + lines[i+1])
            i += 1
    else:
        out.append(line)
    i += 1

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'w', encoding='utf-8') as f:
    f.writelines(out)
