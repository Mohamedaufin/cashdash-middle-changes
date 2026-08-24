package com.cash.dash

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object VersionCheckManager {

    /**
     * Checks online Firestore configuration to see if the installed version is outdated.
     * If force_update_enabled is false, bypasses the lock completely.
     * Otherwise, checks if installed version is below min_supported_version_name or > 1 version behind.
     */
    fun checkAppVersion(context: Context, onProceed: () -> Unit) {
        val installedVersionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.4.7"
        } catch (e: Exception) { "0.4.7" }

        val installedVersionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (e: Exception) { 0L }

        FirebaseFirestore.getInstance().collection("system").document("config")
            .get()
            .addOnSuccessListener { doc ->
                val forceUpdateEnabled = doc.getBoolean("force_update_enabled") ?: false
                
                // If update lock is explicitly turned off by Admin -> allow all users to proceed without lock
                if (!forceUpdateEnabled) {
                    onProceed()
                    return@addOnSuccessListener
                }

                // Default version history list
                val defaultList = listOf(
                    "0.4.8", "0.4.7", "0.4.6", "0.4.5", "0.4.4", "0.4.3", "0.4.2", "0.4.1", "0.4.0",
                    "0.3.9", "0.3.8", "0.3.7", "0.3.6", "0.3.5", "0.3.4", "0.3.3", "0.3.2",
                    "0.3.1", "0.3.0", "0.2.0", "0.1.0"
                )
                val cloudList = doc.get("version_history") as? List<String> ?: emptyList()
                val versionHistory = (cloudList + defaultList + listOf(installedVersionName)).distinct().sortedDescending()

                // Auto-sync current app's versionName to Firestore version_history if missing
                if (!versionHistory.contains(installedVersionName)) {
                    doc.reference.set(
                        mapOf(
                            "version_history" to FieldValue.arrayUnion(installedVersionName),
                            "latest_version_code" to installedVersionCode,
                            "last_updated_at" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }

                val rawMinVersion = doc.getString("min_supported_version_name") ?: "0.4.0"
                val minSupportedVersionName = rawMinVersion.replace(" (Current)", "").trim()
                val latestPlayStoreVersionCode = doc.getLong("latest_version_code") ?: 0L

                // Version name index comparison (list is sorted descending: index 0 is newest)
                val installedIndex = versionHistory.indexOf(installedVersionName)
                val minIndex = versionHistory.indexOf(minSupportedVersionName)

                // A larger index means it's older (further down the descending list)
                val isBelowMinVersion = minIndex != -1 && (installedIndex != -1 && installedIndex > minIndex)
                val isMoreThanOneVersionBehind = latestPlayStoreVersionCode > 0 && (latestPlayStoreVersionCode - installedVersionCode) > 1

                if (isBelowMinVersion || isMoreThanOneVersionBehind) {
                    val title = doc.getString("update_title")
                    val subtitle = doc.getString("update_subtitle")
                    val intent = Intent(context, ForceUpdateActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        title?.let { putExtra(ForceUpdateActivity.EXTRA_TITLE, it) }
                        subtitle?.let { putExtra(ForceUpdateActivity.EXTRA_SUBTITLE, it) }
                    }
                    context.startActivity(intent)
                    if (context is Activity) {
                        context.finish()
                    }
                } else {
                    onProceed()
                }
            }
            .addOnFailureListener {
                onProceed()
            }
    }
}
