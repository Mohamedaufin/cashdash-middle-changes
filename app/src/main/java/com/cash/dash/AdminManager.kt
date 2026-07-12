package com.cash.dash

import com.google.firebase.auth.FirebaseAuth
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
        val allocateAdmins: Boolean = false
    ) {
        val isOwner: Boolean
            get() = isFixedOwner || isPromotedOwner

        val hasAnyAccess: Boolean
            get() = isOwner || fullAccess || sendAnnouncements || sendPromotions || sendNotifications || viewLastSeen || allocateAdmins

        fun canSendAnnouncements() = isOwner || fullAccess || sendAnnouncements
        fun canSendPromotions() = isOwner || fullAccess || sendPromotions
        fun canSendNotifications() = isOwner || fullAccess || sendNotifications
        fun canViewLastSeen() = isOwner || fullAccess || viewLastSeen
        fun canAllocateAdmins() = isOwner || fullAccess || allocateAdmins
    }

    private var currentPermissions = AdminPermissions()
    private var listenerRegistration: ListenerRegistration? = null
    private val listeners = mutableListOf<(AdminPermissions) -> Unit>()
    
    val superAdmins = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")

    fun init(email: String?) {
        listenerRegistration?.remove()
        listenerRegistration = null
        
        if (email.isNullOrEmpty()) {
            currentPermissions = AdminPermissions()
            notifyListeners()
            return
        }

        val lowercaseEmail = email.lowercase()
        val isFixed = superAdmins.contains(lowercaseEmail)

        val db = FirebaseFirestore.getInstance()
        listenerRegistration = db.collection("admins").document(lowercaseEmail)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    currentPermissions = AdminPermissions(isFixedOwner = isFixed)
                    notifyListeners()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    currentPermissions = AdminPermissions(
                        isFixedOwner = isFixed,
                        isPromotedOwner = snapshot.getBoolean("isOwner") ?: false,
                        fullAccess = snapshot.getBoolean("fullAccess") ?: false,
                        sendAnnouncements = snapshot.getBoolean("sendAnnouncements") ?: false,
                        sendPromotions = snapshot.getBoolean("sendPromotions") ?: false,
                        sendNotifications = snapshot.getBoolean("sendNotifications") ?: false,
                        viewLastSeen = snapshot.getBoolean("viewLastSeen") ?: false,
                        allocateAdmins = snapshot.getBoolean("allocateAdmins") ?: false
                    )
                } else {
                    currentPermissions = AdminPermissions(isFixedOwner = isFixed)
                }
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
