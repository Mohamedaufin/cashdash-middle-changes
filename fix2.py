import re
with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Add glare and interpolator vars
text = text.replace('private val phoneContainer: View', 'private val phoneContainer: View\n    private val glare: View')
text = text.replace('phoneContainer = findViewById(R.id.introScanPhone)', 'phoneContainer = findViewById(R.id.introScanPhone)\n        glare = findViewById(R.id.introScanGlare)')
text = text.replace('val all = listOf(phoneContainer, frame,', 'val all = listOf(phoneContainer, glare, frame,')

# 2. Reset glare and phone elevation in resetScene
text = text.replace('phoneContainer.translationY = 0f', 'phoneContainer.translationY = 0f\n        phoneContainer.translationZ = 0f\n        glare.alpha = 0f\n        glare.translationY = -400f.dp')

# 3. Use an OvershootInterpolator!
text = text.replace('private companion object {', 'private companion object {\n        val OVERSHOOT = android.view.animation.OvershootInterpolator(1.2f)')

# 4. Beat 2 3D transform (Amount entry)
beat2_old = '''        schedule(beat2Start) {
            phoneContainer.animate().scaleX(1.1f).scaleY(1.1f).rotationX(12f).rotationY(-3f).translationY(-10f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
beat2_new = '''        schedule(beat2Start) {
            phoneContainer.animate().scaleX(1.12f).scaleY(1.12f).rotationX(16f).rotationY(-5f).translationY(-15f.dp).translationZ(60f.dp)
                .setDuration(1200).setInterpolator(OVERSHOOT).start()
            
            // Sweep glare
            glare.translationY = -400f.dp
            glare.animate().alpha(1f).translationY(100f.dp).setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction { glare.animate().alpha(0f).setDuration(200).start() }.start()

            // Parallax children slightly
            amountGroup.animate().translationY(-8f.dp).setDuration(1200).setInterpolator(OVERSHOOT).start()
        }'''
text = text.replace(beat2_old, beat2_new)

# 5. Beat 3 3D transform (Allocation chooser)
beat3_old = '''        schedule(beat3Start) {
            phoneContainer.animate().scaleX(1.25f).scaleY(1.25f).rotationX(5f).rotationY(4f).translationY(-40f.dp)
                .setDuration(1400).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
beat3_new = '''        schedule(beat3Start) {
            phoneContainer.animate().scaleX(1.22f).scaleY(1.22f).rotationX(8f).rotationY(4f).translationY(-50f.dp).translationZ(80f.dp)
                .setDuration(1400).setInterpolator(OVERSHOOT).start()
                
            glare.translationY = -400f.dp
            glare.animate().alpha(1f).translationY(200f.dp).setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction { glare.animate().alpha(0f).setDuration(200).start() }.start()

            // Reverse parallax
            allocGroup.animate().translationY(10f.dp).setDuration(1400).setInterpolator(OVERSHOOT).start()
        }'''
text = text.replace(beat3_old, beat3_new)

# 6. Beat 4 (Pay Now)
beat4_old = '''        schedule(beat4Start) {
            phoneContainer.animate().scaleX(1.05f).scaleY(1.05f).rotationX(8f).rotationY(-2f).translationY(-20f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
beat4_new = '''        schedule(beat4Start) {
            phoneContainer.animate().scaleX(1.05f).scaleY(1.05f).rotationX(4f).rotationY(-2f).translationY(-20f.dp).translationZ(30f.dp)
                .setDuration(1200).setInterpolator(OVERSHOOT).start()
                
            glare.translationY = -400f.dp
            glare.animate().alpha(0.6f).translationY(50f.dp).setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction { glare.animate().alpha(0f).setDuration(200).start() }.start()
        }'''
text = text.replace(beat4_old, beat4_new)

# 7. Beat 5 (Success)
beat5_old = '''        schedule(beat5Start) {
            phoneContainer.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationY(0f)
                .setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }'''
beat5_new = '''        schedule(beat5Start) {
            phoneContainer.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationY(0f).translationZ(0f)
                .setDuration(1000).setInterpolator(OVERSHOOT).start()
        }'''
text = text.replace(beat5_old, beat5_new)

with open('app/src/main/java/com/cash/dash/IntroScanScene.kt', 'w', encoding='utf-8') as f:
    f.write(text)
