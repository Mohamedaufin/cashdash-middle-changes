package com.cash.dash

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Device integrity signals: root, emulator, attached debugger, sideload.
 *
 * ## What this is and is not
 *
 * This raises the cost of tampering. It does not prevent it. Every check below
 * runs inside a process the attacker controls, so any of them can be patched
 * out by someone competent with the APK. Treat a clean result as "no casual
 * tampering detected", never as proof the client is honest.
 *
 * The real authorization boundary is server-side Firestore rules. Nothing here
 * is load-bearing for another user's data.
 *
 * ## Why the signals are graded rather than pooled
 *
 * [Result.isCompromised] deliberately excludes emulator and sideload. Both have
 * legitimate explanations — QA devices, an internal-testing APK, a reviewer's
 * emulator — and treating them as compromise would deny service to real users
 * to stop an attacker who is only attacking their own device. They are reported
 * so the signal exists, not enforced.
 *
 * Debug builds are exempt from [isCompromised] entirely; they are debuggable and
 * sideloaded by definition, and would otherwise trip every check on your own
 * workstation.
 */
object TamperCheck {

    data class Result(
        val rooted: Boolean,
        val emulator: Boolean,
        val debuggerAttached: Boolean,
        val debuggableBuild: Boolean,
        val sideloaded: Boolean,
        /** Human-readable list of what actually fired, for logging and support. */
        val signals: List<String>
    ) {
        /**
         * The subset worth acting on. Excludes emulator and sideload by design —
         * see the class comment. Always false in debug builds.
         */
        val isCompromised: Boolean
            get() = !BuildConfig.DEBUG && (rooted || debuggerAttached || debuggableBuild)
    }

    @Volatile
    private var cached: Result? = null

    /**
     * Evaluates once per process and caches. The filesystem probes are cheap but
     * not free, and this is called from every activity's onCreate.
     */
    fun evaluate(context: Context): Result {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val signals = mutableListOf<String>()

                val rooted = detectRoot(signals)
                val emulator = detectEmulator(signals)
                val debugger = detectDebugger(signals)
                val debuggable = detectDebuggableBuild(context, signals)
                val sideloaded = detectSideload(context, signals)

                Result(rooted, emulator, debugger, debuggable, sideloaded, signals).also {
                    cached = it
                }
            }
        }
    }

    // ---------------------------------------------------------------- root

    /**
     * Paths that only exist on a rooted device. File existence is used rather
     * than `Runtime.exec("which su")` deliberately: exec is slow, shows up in
     * logs, and on modern Android is frequently blocked by SELinux anyway, so
     * it produces false negatives while looking like it works.
     */
    private val ROOT_PATHS = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/su/bin/su",
        "/odm/bin/su", "/vendor/bin/su",
        // Magisk
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules",
        "/system/bin/magisk", "/cache/.disable_magisk"
    )

    private fun detectRoot(signals: MutableList<String>): Boolean {
        var found = false

        for (path in ROOT_PATHS) {
            if (safeExists(path)) {
                signals.add("root:path:$path")
                found = true
            }
        }

        // Release images are signed with release-keys. test-keys means a custom
        // or engineering build, which is not root by itself but travels with it.
        if (Build.TAGS?.contains("test-keys") == true) {
            signals.add("root:test-keys")
            found = true
        }

        // On a stock device these are not writable by an app.
        for (path in arrayOf("/system", "/system/bin", "/system/xbin", "/vendor/bin")) {
            if (safeCanWrite(path)) {
                signals.add("root:writable:$path")
                found = true
            }
        }

        return found
    }

    // ------------------------------------------------------------ emulator

    private fun detectEmulator(signals: MutableList<String>): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()

        val hit = fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("vbox") ||
            fingerprint.contains("test-keys") && hardware.contains("goldfish") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for x86") ||
            manufacturer.contains("Genymotion") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk_gphone") ||
            product == "google_sdk" ||
            product == "sdk" ||
            product == "sdk_x86"

        if (hit) signals.add("emulator:$manufacturer/$model/$product/$hardware")
        return hit
    }

    // ------------------------------------------------------------ debugger

    private fun detectDebugger(signals: MutableList<String>): Boolean {
        val attached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        if (attached) signals.add("debugger:attached")
        return attached
    }

    /**
     * A release APK should never carry FLAG_DEBUGGABLE. If it does, the build
     * was tampered with and repackaged — which is exactly how an attacker makes
     * an app inspectable without needing root.
     */
    private fun detectDebuggableBuild(context: Context, signals: MutableList<String>): Boolean {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && !BuildConfig.DEBUG) signals.add("build:debuggable-release")
        return debuggable
    }

    // ------------------------------------------------------------ sideload

    /**
     * Reported, never enforced. Internal-testing and pre-release builds are
     * legitimately sideloaded, and so is every build on your own bench.
     */
    private fun detectSideload(context: Context, signals: MutableList<String>): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }

        val fromPlay = installer == "com.android.vending"
        if (!fromPlay) signals.add("install:${installer ?: "unknown"}")
        return !fromPlay
    }

    // --------------------------------------------------------------- utils

    /**
     * Probing restricted paths throws on some images rather than returning
     * false. An exception is not evidence of root, so it is swallowed.
     */
    private fun safeExists(path: String): Boolean = try {
        File(path).exists()
    } catch (e: Exception) {
        false
    }

    private fun safeCanWrite(path: String): Boolean = try {
        File(path).canWrite()
    } catch (e: Exception) {
        false
    }
}
