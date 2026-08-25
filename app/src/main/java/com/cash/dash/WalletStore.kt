package com.cash.dash

import android.content.Context
import android.content.SharedPreferences

/**
 * Single access point for the wallet preference store.
 *
 * This file holds balance and financial state. It used to be plaintext
 * MODE_PRIVATE SharedPreferences, which on a rooted device could be read and
 * edited freely. It is now encrypted with the same scheme AdminManager uses
 * for the admin permission cache.
 *
 * Encryption is applied to a *new* file rather than in place, because
 * EncryptedSharedPreferences cannot read a plaintext file: the old contents
 * are copied across once and the old file is then cleared. Rewriting in place
 * would risk losing a user's balance if the process died mid-rewrite.
 *
 * The instance is cached deliberately. EncryptedSharedPreferences.create()
 * does keystore work on every call, and the wallet is read on hot paths —
 * widget refreshes and every HomeFragment bind — so creating it per call site
 * would be a visible performance regression.
 */
object WalletStore {

    private const val FILE = "WalletPrefs_v2"

    /** The pre-encryption plaintext file. Public so the account-wipe paths can clear it too. */
    const val LEGACY_FILE = "WalletPrefs"

    /** The current file, for the account-wipe paths that enumerate prefs by name. */
    const val FILE_NAME = FILE

    @Volatile
    private var cached: SharedPreferences? = null

    @JvmStatic
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
                FILE,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Some OEM images and older devices fail to provision a keystore key.
            // Unlike the admin permission cache, wallet state cannot simply be
            // refetched, so fall back to a plaintext file rather than lose it.
            // Same filename, so a device that later gains keystore support does
            // not silently start from an empty wallet.
            android.util.Log.e("WalletStore", "Encrypted prefs unavailable, using plaintext: ${e.message}")
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Copies the old plaintext wallet across exactly once, then clears it.
     *
     * Uses commit() rather than apply() so the new file is durable *before* the
     * old one is emptied — a crash between the two would otherwise lose the
     * user's balance. If the write fails, the legacy file is left untouched and
     * the copy is retried on the next launch.
     */
    private fun migrateIfNeeded(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
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
