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

    /**
     * Plaintext fallback for devices with no working keystore.
     *
     * A *separate* file, deliberately. It originally shared [fileName], on the
     * reasoning that a device which later gained keystore support would not lose
     * its data — but that produces a file holding a mix of encrypted and
     * plaintext entries, and EncryptedSharedPreferences throws on the first
     * entry it cannot decrypt. Keeping them apart makes that impossible. Data
     * written while in fallback is migrated in by [migrateIfNeeded].
     */
    private val fallbackFileName: String = "${fileName}_plain"

    @Volatile
    private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: create(context.applicationContext).also {
                migrateIfNeeded(context.applicationContext, it, legacyFileName)
                migrateIfNeeded(context.applicationContext, it, fallbackFileName)
                cached = it
            }
        }
    }

    /**
     * Opens the encrypted store, and **verifies it is actually readable** before
     * handing it out.
     *
     * The verification is the point. `EncryptedSharedPreferences.create()`
     * succeeds even when the file cannot be decrypted — the failure surfaces
     * later, on the first read or on `edit().clear()`, which calls `getAll()`
     * internally. That turned a recoverable state into a crash on launch:
     * the keyset and the master key can drift apart (keystore reset, a restored
     * data directory, a regenerated keyset), and from then on every start threw
     * SecurityException from deep inside the sync path.
     *
     * If the store is unreadable the file is deleted and rebuilt. That discards
     * the local cache, which is the right trade: this data is mirrored in
     * Firestore and re-pulls on the next sync, whereas the alternative is an app
     * that cannot open at all.
     */
    private fun create(context: Context): SharedPreferences {
        try {
            return buildVerifiedEncrypted(context)
        } catch (e: Exception) {
            android.util.Log.e(logTag, "Encrypted prefs unreadable, rebuilding: ${e.message}")
        }

        // The keyset lives inside the same file, so deleting it clears both the
        // data and the keys that can no longer read it.
        try {
            context.deleteSharedPreferences(fileName)
            return buildVerifiedEncrypted(context)
        } catch (e: Exception) {
            android.util.Log.e(logTag, "Encrypted prefs unavailable, using plaintext: ${e.message}")
        }

        return context.getSharedPreferences(fallbackFileName, Context.MODE_PRIVATE)
    }

    /**
     * Clears the store completely and drops the cached instance.
     *
     * **Account-wipe paths must call this** rather than
     * `getSharedPreferences(fileName).edit().clear()`.
     *
     * Clearing this file as *plain* preferences also deletes the Tink keysets
     * that live inside it, while a cached [android.content.SharedPreferences]
     * keeps the old keyset in memory. Subsequent writes then encrypt with a
     * keyset the file no longer holds, and the next launch — which generates a
     * fresh keyset — cannot decrypt them. That was the launch crash of
     * 2026-08-26: `SecurityException: Could not decrypt key` on every start.
     *
     * Deleting the files and dropping the cache leaves no way for the two to
     * disagree.
     */
    fun wipe(context: Context) {
        synchronized(this) {
            cached = null
            val ctx = context.applicationContext
            ctx.deleteSharedPreferences(fileName)
            ctx.deleteSharedPreferences(fallbackFileName)
            ctx.deleteSharedPreferences(legacyFileName)
        }
    }

    private fun buildVerifiedEncrypted(context: Context): SharedPreferences {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Forces decryption of every key. Throws here, where it can be handled,
        // rather than later on a caller's edit().clear().
        prefs.all
        return prefs
    }

    /**
     * Copies the old plaintext file across exactly once, then clears it.
     *
     * Uses commit() rather than apply() so the new file is durable *before* the
     * old one is emptied — a crash between the two would otherwise lose the
     * data. If the write fails, the legacy file is left untouched and the copy
     * is retried on the next launch.
     */
    private fun migrateIfNeeded(context: Context, target: SharedPreferences, sourceName: String) {
        // Nothing to do when the store itself fell back to plaintext: source and
        // target would be the same file.
        if (sourceName == fallbackFileName && target === context.getSharedPreferences(fallbackFileName, Context.MODE_PRIVATE)) {
            return
        }

        val legacy = context.getSharedPreferences(sourceName, Context.MODE_PRIVATE)
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
