import re

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

out = []
for line in lines:
    line = line.replace('allocShopping', 'allocTravel')
    if 'phoneContainer.animate()' in line and 'rotationX' in line:
        continue # delete the line completely!
    out.append(line)

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'w', encoding='utf-8') as f:
    f.writelines(out)
