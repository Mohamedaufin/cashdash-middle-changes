package com.cash.dash

import android.content.Context
import android.content.SharedPreferences

/**
 * A SharedPreferences file encrypted at rest, with a one-time migration off the
 * plaintext file it replaces.
 *
 * Encryption is applied to a *new* file rather than in place, because
 * EncryptedSharedPreferences cannot read a plaintext file: the old contents are
 * copied across once and the old file is then cleared. Rewriting in place would
 * risk losing data if the process died mid-rewrite.
 *
 * Instances are cached deliberately. EncryptedSharedPreferences.create() does
 * keystore work on every call, and these files are read on hot paths — widget
 * refreshes, fragment binds, scanner screens — so creating one per call site
 * would be a visible performance regression.
 *
 * Unlike the admin permission cache in [AdminManager], these stores fall back to
 * a plaintext file when the keystore is unavailable. That is a deliberate
 * asymmetry: the permission cache is disposable and can simply be skipped, but
 * the data here cannot be refetched, so losing it is worse than storing it in
 * the clear on the minority of devices that fail to provision a key.
 */
open class SecurePrefsStore(
    /** The encrypted file. */
    val fileName: String,
    /** The pre-encryption plaintext file, kept only so the one-time copy can find it. */
    val legacyFileName: String,
    private val logTag: String
) {

    @Volatile
    private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: create(context.applicationContext).also {
                migrateIfNeeded(context.applicationContext, it)
                cached = it
            }
        }
    }

    private fun create(context: Context): SharedPreferences {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Same filename, so a device that later gains keystore support does
            // not silently start from an empty store.
            android.util.Log.e(logTag, "Encrypted prefs unavailable, using plaintext: ${e.message}")
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
    }

    /**
     * Copies the old plaintext file across exactly once, then clears it.
     *
     * Uses commit() rather than apply() so the new file is durable *before* the
     * old one is emptied — a crash between the two would otherwise lose the
     * data. If the write fails, the legacy file is left untouched and the copy
     * is retried on the next launch.
     */
    private fun migrateIfNeeded(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(legacyFileName, Context.MODE_PRIVATE)
        val entries = legacy.all
        if (entries.isEmpty()) return

        val editor = target.edit()
        for ((key, value) in entries) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> Unit
            }
        }

        if (editor.commit()) {
            legacy.edit().clear().commit()
        }
    }
}

/**
 * Balance and financial state. Was plaintext `MODE_PRIVATE` prefs, readable and
 * editable on any rooted device.
 */
object WalletStore : SecurePrefsStore("WalletPrefs_v2", "WalletPrefs", "WalletStore")

/**
 * Scanner state, including `last_upi` — the last UPI payment address used. Same
 * exposure as the wallet: plaintext on disk, editable on a rooted device.
 *
 * Note this file's contents are also mirrored to Firestore under
 * `config/undo_details` for the undo feature, where the hardened rules make it
 * owner-only. Encrypting locally closes the on-device half.
 */
object ScanStore : SecurePrefsStore("LocalScanPrefs_v2", "LocalScanPrefs", "ScanStore")
