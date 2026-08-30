import re

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Remove glare declaration and assignment
text = text.replace('private val phoneContainer: View\n    private val glare: View', 'private val phoneContainer: View')
text = text.replace('phoneContainer = findViewById(R.id.introScanPhone)\n        glare = findViewById(R.id.introScanGlare)', 'phoneContainer = findViewById(R.id.introScanPhone)')
text = text.replace('val all = listOf(phoneContainer, glare, frame,', 'val all = listOf(phoneContainer, frame,')

# 2. Remove reset settings for glare/Z
reset_old = '''        phoneContainer.translationY = 0f
        phoneContainer.translationZ = 0f
        glare.alpha = 0f
        glare.translationY = -400f.dp'''
reset_new = '''        phoneContainer.translationY = 0f'''
text = text.replace(reset_old, reset_new)

# 3. Remove OVERSHOOT
text = text.replace('private companion object {\n        val OVERSHOOT = android.view.animation.OvershootInterpolator(1.2f)', 'private companion object {')

# 4. Fix Beat 2
beat2_target = '''        schedule(beat2Start) {
            phoneContainer.animate().scaleX(1.12f).scaleY(1.12f).rotationX(16f).rotationY(-5f).translationY(-15f.dp).translationZ(60f.dp)
                .setDuration(1200).setInterpolator(OVERSHOOT).start()
            
            // Sweep glare
            glare.translationY = -400f.dp
            glare.animate().alpha(1f).translationY(100f.dp).setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction { glare.animate().alpha(0f).setDuration(200).start() }.start()

            // Parallax children slightly
            amountGroup.animate().translationY(-8f.dp).setDuration(1200).setInterpolator(OVERSHOOT).start()
        }'''
beat2_new = '''        schedule(beat2Start) {
            phoneContainer.animate().scaleX(1.12f).scaleY(1.12f).translationY(-15f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat2_target, beat2_new)

# 5. Fix Beat 3
beat3_target = '''        schedule(beat3Start) {
            phoneContainer.animate().scaleX(1.22f).scaleY(1.22f).rotationX(8f).rotationY(4f).translationY(-50f.dp).translationZ(80f.dp)
                .setDuration(1400).setInterpolator(OVERSHOOT).start()
                
            glare.translationY = -400f.dp
            glare.animate().alpha(1f).translationY(200f.dp).setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction { glare.animate().alpha(0f).setDuration(200).start() }.start()

            // Reverse parallax
            allocGroup.animate().translationY(10f.dp).setDuration(1400).setInterpolator(OVERSHOOT).start()
        }'''
beat3_new = '''        schedule(beat3Start) {
            phoneContainer.animate().scaleX(1.22f).scaleY(1.22f).translationY(-50f.dp)
                .setDuration(1400).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat3_target, beat3_new)

# 6. Fix Beat 4
beat4_target = '''        schedule(beat4Start) {
            phoneContainer.animate().scaleX(1.05f).scaleY(1.05f).rotationX(4f).rotationY(-2f).translationY(-20f.dp).translationZ(30f.dp)
                .setDuration(1200).setInterpolator(OVERSHOOT).start()
                
            glare.translationY = -400f.dp
            glare.animate().alpha(0.6f).translationY(50f.dp).setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction { glare.animate().alpha(0f).setDuration(200).start() }.start()
        }'''
beat4_new = '''        schedule(beat4Start) {
            phoneContainer.animate().scaleX(1.05f).scaleY(1.05f).translationY(-20f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat4_target, beat4_new)

# 7. Fix Beat 5
beat5_target = '''        schedule(beat5Start) {
            phoneContainer.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationY(0f).translationZ(0f)
                .setDuration(1000).setInterpolator(OVERSHOOT).start()
        }'''
beat5_new = '''        schedule(beat5Start) {
            phoneContainer.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
text = text.replace(beat5_target, beat5_new)

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'w', encoding='utf-8') as f:
    f.write(text)
