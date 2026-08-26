package com.cash.dash

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the plaintext -> encrypted migration on a real device, against a real
 * Android keystore. This is the path that [WalletStore] and [ScanStore] run on
 * first launch after the encryption change, and the one that can lose a user's
 * balance if it is wrong.
 *
 * The tests build their own [SecurePrefsStore] instances rather than using the
 * WalletStore / ScanStore singletons, because those cache their SharedPreferences
 * after the first get() and would not re-run the migration between test cases.
 * The class under test is identical either way; only the filenames differ.
 * [walletAndScanStoresUseExpectedFiles] pins the singletons to the right names.
 */
@RunWith(AndroidJUnit4::class)
class SecurePrefsStoreMigrationTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val legacyName = "test_migration_legacy"
    private val encryptedName = "test_migration_legacy_v2"

    private fun newStore() = SecurePrefsStore(encryptedName, legacyName, "MigrationTest")

    @Before
    fun clean() = wipe()

    @After
    fun cleanUp() = wipe()

    private fun wipe() {
        ctx.deleteSharedPreferences(legacyName)
        ctx.deleteSharedPreferences(encryptedName)
    }

    private fun seedLegacy() {
        ctx.getSharedPreferences(legacyName, Context.MODE_PRIVATE).edit()
            .putInt("wallet_balance", 4213)
            .putInt("initial_balance", 5000)
            .putString("balance_bar_mode", "gradient")
            .putString("last_upi", "someone@okhdfcbank")
            .putBoolean("a_flag", true)
            .putLong("a_long", 1787651197070L)
            .putFloat("a_float", 12.5f)
            .putStringSet("a_set", setOf("x", "y"))
            .commit()
    }

    /** Every value survives, with its type intact. This is the data-loss case. */
    @Test
    fun migrationCopiesAllValuesAndTypes() {
        seedLegacy()

        val prefs = newStore().get(ctx)

        assertEquals(4213, prefs.getInt("wallet_balance", -1))
        assertEquals(5000, prefs.getInt("initial_balance", -1))
        assertEquals("gradient", prefs.getString("balance_bar_mode", null))
        assertEquals("someone@okhdfcbank", prefs.getString("last_upi", null))
        assertTrue(prefs.getBoolean("a_flag", false))
        assertEquals(1787651197070L, prefs.getLong("a_long", -1L))
        assertEquals(12.5f, prefs.getFloat("a_float", -1f), 0.0001f)
        assertEquals(setOf("x", "y"), prefs.getStringSet("a_set", emptySet()))
    }

    /** The old plaintext copy must not survive the migration. */
    @Test
    fun migrationClearsTheLegacyFile() {
        seedLegacy()

        newStore().get(ctx)

        val legacy = ctx.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        assertTrue("legacy plaintext file still holds data", legacy.all.isEmpty())
    }

    /**
     * The point of the exercise: reading the new file without the keystore must
     * not reveal the values. Guards against the store silently falling back to
     * plaintext on this device.
     */
    @Test
    fun dataAtRestIsActuallyEncrypted() {
        seedLegacy()

        newStore().get(ctx)

        val raw = ctx.getSharedPreferences(encryptedName, Context.MODE_PRIVATE).all
        assertFalse("plaintext key name present in encrypted store", raw.containsKey("wallet_balance"))
        assertFalse("plaintext key name present in encrypted store", raw.containsKey("last_upi"))

        val blob = raw.entries.joinToString { "${it.key}=${it.value}" }
        assertFalse("UPI address readable in raw file", blob.contains("okhdfcbank"))
        assertFalse("balance readable in raw file", blob.contains("4213"))
        assertTrue("expected some encrypted entries", raw.isNotEmpty())
    }

    /** A fresh install has no legacy file; migration must be a no-op, not a wipe. */
    @Test
    fun noLegacyDataIsANoOp() {
        val prefs = newStore().get(ctx)
        prefs.edit().putInt("wallet_balance", 77).commit()

        // A second store over the same files, as if the app relaunched.
        val reopened = newStore().get(ctx)
        assertEquals(77, reopened.getInt("wallet_balance", -1))
    }

    /**
     * Once migrated, a later relaunch must not resurrect or clobber anything —
     * the user's newer balance has to win over the emptied legacy file.
     */
    @Test
    fun migrationDoesNotClobberNewerDataOnRelaunch() {
        seedLegacy()
        newStore().get(ctx)

        val prefs = newStore().get(ctx)
        prefs.edit().putInt("wallet_balance", 999).commit()

        val afterRelaunch = newStore().get(ctx)
        assertEquals(999, afterRelaunch.getInt("wallet_balance", -1))
    }

    /** The singletons must point at the filenames the wipe and sync lists use. */
    @Test
    fun walletAndScanStoresUseExpectedFiles() {
        assertEquals("WalletPrefs_v2", WalletStore.fileName)
        assertEquals("WalletPrefs", WalletStore.legacyFileName)
        assertEquals("LocalScanPrefs_v2", ScanStore.fileName)
        assertEquals("LocalScanPrefs", ScanStore.legacyFileName)
    }

    /**
     * The wallet listener in CashDashApplication compares plaintext key names.
     * If EncryptedSharedPreferences dispatched encrypted keys, widget refreshes
     * would silently stop. Verified from bytecode during implementation; this
     * pins it on the device so a library upgrade cannot regress it unnoticed.
     */
    @Test
    fun changeListenerReceivesPlaintextKeys() {
        val prefs = newStore().get(ctx)
        val seen = mutableListOf<String>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) seen.add(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            prefs.edit().putInt("wallet_balance", 1234).commit()
            assertTrue(
                "listener got $seen, expected the plaintext key wallet_balance",
                seen.contains("wallet_balance")
            )
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
