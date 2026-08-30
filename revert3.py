import re

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'r', encoding='utf-8') as f:
    text = f.read()

beat2_target = '''        schedule(beat2Start) {
            phoneContainer.animate().scaleX(1.12f).scaleY(1.12f).translationY(-15f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat2_target, '')

beat3_target = '''        schedule(beat3Start) {
            phoneContainer.animate().scaleX(1.22f).scaleY(1.22f).translationY(-50f.dp)
                .setDuration(1400).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat3_target, '')

beat4_target = '''        schedule(beat4Start) {
            phoneContainer.animate().scaleX(1.05f).scaleY(1.05f).translationY(-20f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat4_target, '')

beat5_target = '''        schedule(beat5Start) {
            phoneContainer.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat5_target, '')

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'w', encoding='utf-8') as f:
    f.write(text)
