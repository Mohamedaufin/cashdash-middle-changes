import re

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace all rotationX, rotationY, translationZ, scaleX, scaleY animations on phoneContainer
content = re.sub(r'phoneContainer\.animate\(\)\.scaleX\([^)]+\)\.scaleY\([^)]+\)\.rotationX\([^)]+\)\.rotationY\([^)]+\)\.translationY\([^)]+\)\.translationZ\([^)]+\)', 'phoneContainer.animate().translationY(0f)', content)
content = re.sub(r'\.setDuration\(\d+\)\.setInterpolator\([^)]+\)\.start\(\)', '.setDuration(300).start()', content)

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'w', encoding='utf-8') as f:
    f.write(content)
