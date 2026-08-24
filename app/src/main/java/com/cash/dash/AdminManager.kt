package com.cash.dash

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object AdminManager {

    data class AdminPermissions(
        val isFixedOwner: Boolean = false,
        val isPromotedOwner: Boolean = false,
        val fullAccess: Boolean = false,
        val sendAnnouncements: Boolean = false,
        val sendPromotions: Boolean = false,
        val sendNotifications: Boolean = false,
        val viewLastSeen: Boolean = false,
        val viewAdminLogs: Boolean = false,
        val replyToQueries: Boolean = false,
        val allocateAdmins: Boolean = false,
        val validUntil: Long = 0L
    ) {
        val isOwner: Boolean
            get() = isFixedOwner || isPromotedOwner

        val hasAnyAccess: Boolean
            get() = !isExpired && (isOwner || fullAccess || sendAnnouncements || sendPromotions || sendNotifications || viewLastSeen || viewAdminLogs || replyToQueries || allocateAdmins)

        val isExpired: Boolean
            get() = validUntil > 0L && validUntil < System.currentTimeMillis()

        fun canSendAnnouncements() = !isExpired && (isOwner || fullAccess || sendAnnouncements)
        fun canSendPromotions() = !isExpired && (isOwner || fullAccess || sendPromotions)
        fun canSendNotifications() = !isExpired && (isOwner || fullAccess || sendNotifications)
        fun canViewLastSeen() = !isExpired && (isOwner || fullAccess || viewLastSeen)
        fun canViewAdminLogs() = !isExpired && (isOwner || fullAccess || viewAdminLogs)
        fun canReplyToQueries() = !isExpired && (isOwner || fullAccess || replyToQueries)
        fun canAllocateAdmins() = !isExpired && (isOwner || fullAccess || allocateAdmins)
    }

    private var currentPermissions = AdminPermissions()
    private var listenerRegistration: ListenerRegistration? = null
    private val listeners = mutableListOf<(AdminPermissions) -> Unit>()

    val superAdmins = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")

    // ------------------------------------------------------------------
    // Offline-safe permission cache stored in SharedPreferences.
    // Permissions are restored from cache BEFORE the Firestore listener
    // fires, so pages open without error even when the device is offline.
    // ------------------------------------------------------------------

    private const val PREFS_NAME = "admin_perms_cache_v2"
    private const val LEGACY_PREFS_NAME = "admin_perms_cache"

    /**
     * The permission cache is stored encrypted. It used to be plaintext booleans,
     * which on a rooted device could be flipped to unlock the admin UI locally.
     * Server-side rules are the real defence — this closes the local tamper path.
     *
     * Falls back to plain prefs if the Android keystore is unavailable
     * (some OEM images and older devices fail here). A cache miss is harmless:
     * permissions simply reload from Firestore, so failing soft is correct.
     */
    private fun securePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("AdminManager", "Encrypted prefs unavailable, falling back: ${e.message}")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Drop the old plaintext cache left by previous versions. */
    private fun purgeLegacyPlaintextCache(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            legacy.edit().clear().apply()
        }
    }

    private fun saveToCache(context: Context, email: String, perms: AdminPermissions) {
        val prefs: SharedPreferences = securePrefs(context)
        prefs.edit()
            .putString("cached_email", email)
            .putBoolean("isFixedOwner", perms.isFixedOwner)
            .putBoolean("isPromotedOwner", perms.isPromotedOwner)
            .putBoolean("fullAccess", perms.fullAccess)
            .putBoolean("sendAnnouncements", perms.sendAnnouncements)
            .putBoolean("sendPromotions", perms.sendPromotions)
            .putBoolean("sendNotifications", perms.sendNotifications)
            .putBoolean("viewLastSeen", perms.viewLastSeen)
            .putBoolean("viewAdminLogs", perms.viewAdminLogs)
            .putBoolean("replyToQueries", perms.replyToQueries)
            .putBoolean("allocateAdmins", perms.allocateAdmins)
            .putLong("validUntil", perms.validUntil)
            .apply()
    }

    private fun loadFromCache(context: Context, email: String): AdminPermissions? {
        purgeLegacyPlaintextCache(context)
        val prefs: SharedPreferences = securePrefs(context)
        val cachedEmail = prefs.getString("cached_email", null) ?: return null
        // Only use cached data for the same email account
        if (!cachedEmail.equals(email, ignoreCase = true)) return null
        return AdminPermissions(
            isFixedOwner = prefs.getBoolean("isFixedOwner", false),
            isPromotedOwner = prefs.getBoolean("isPromotedOwner", false),
            fullAccess = prefs.getBoolean("fullAccess", false),
            sendAnnouncements = prefs.getBoolean("sendAnnouncements", false),
            sendPromotions = prefs.getBoolean("sendPromotions", false),
            sendNotifications = prefs.getBoolean("sendNotifications", false),
            viewLastSeen = prefs.getBoolean("viewLastSeen", false),
            viewAdminLogs = prefs.getBoolean("viewAdminLogs", false),
            replyToQueries = prefs.getBoolean("replyToQueries", false),
            allocateAdmins = prefs.getBoolean("allocateAdmins", false),
            validUntil = prefs.getLong("validUntil", 0L)
        )
    }

    private fun clearCache(context: Context) {
        securePrefs(context).edit().clear().apply()
        purgeLegacyPlaintextCache(context)
    }

    /**
     * Call this from Application.onCreate or after login.
     * [context] is needed to read/write the local permissions cache.
     */
    fun init(email: String?, context: Context) {
        listenerRegistration?.remove()
        listenerRegistration = null

        if (email.isNullOrEmpty()) {
            currentPermissions = AdminPermissions()
            notifyListeners()
            return
        }

        val lowercaseEmail = email.lowercase()
        val isFixed = superAdmins.contains(lowercaseEmail)

        // Step 1: Instantly restore from local cache so admins can navigate
        //         without waiting for (or being blocked by) a network round-trip.
        val cached = loadFromCache(context, lowercaseEmail)
        if (cached != null) {
            // Always honour the fixed-owner flag from the superAdmins list even if cache
            // was written when the user wasn't an owner yet.
            currentPermissions = cached.copy(isFixedOwner = isFixed)
            notifyListeners()
        } else if (isFixed) {
            // First-ever launch for a super-admin with no cache: grant full access immediately.
            currentPermissions = AdminPermissions(isFixedOwner = true)
            notifyListeners()
        }

        // Step 2: Sync with Firestore in the background and update + re-cache on success.
        val db = FirebaseFirestore.getInstance()
        listenerRegistration = db.collection("admins").document(lowercaseEmail)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    // Network error — keep whatever we already have (cache or default).
                    // Only update isFixedOwner in case it wasn't set yet.
                    if (!currentPermissions.isFixedOwner && isFixed) {
                        currentPermissions = currentPermissions.copy(isFixedOwner = true)
                        notifyListeners()
                    }
                    return@addSnapshotListener
                }

                val freshPerms = if (snapshot != null && snapshot.exists()) {
                    AdminPermissions(
                        isFixedOwner = isFixed,
                        isPromotedOwner = snapshot.getBoolean("isOwner") ?: false,
                        fullAccess = snapshot.getBoolean("fullAccess") ?: false,
                        sendAnnouncements = snapshot.getBoolean("sendAnnouncements") ?: false,
                        sendPromotions = snapshot.getBoolean("sendPromotions") ?: false,
                        sendNotifications = snapshot.getBoolean("sendNotifications") ?: false,
                        viewLastSeen = snapshot.getBoolean("viewLastSeen") ?: false,
                        viewAdminLogs = snapshot.getBoolean("viewAdminLogs") ?: false,
                        replyToQueries = snapshot.getBoolean("replyToQueries") ?: false,
                        allocateAdmins = snapshot.getBoolean("allocateAdmins") ?: false,
                        validUntil = snapshot.getLong("validUntil") ?: 0L
                    )
                } else {
                    AdminPermissions(isFixedOwner = isFixed)
                }

                currentPermissions = freshPerms
                saveToCache(context, lowercaseEmail, freshPerms)
                notifyListeners()
            }
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.invoke(currentPermissions)
        }
    }

    fun getPermissions(): AdminPermissions = currentPermissions

    fun isCurrentUserAdmin(): Boolean = currentPermissions.hasAnyAccess

    fun addListener(listener: (AdminPermissions) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.invoke(currentPermissions)
        }
    }

    fun removeListener(listener: (AdminPermissions) -> Unit) {
        listeners.remove(listener)
    }
}
