package com.cash.dash

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore

/**
 * The admin permission editor, shared by AdminActivity and ManageAdminAccessActivity.
 *
 * This used to be a private method on AdminActivity. ManageAdminAccessActivity had its
 * own path -- EditAdminPermissionsActivity -- which showed the same controls and then
 * wrote nothing at all: Save and Revoke each popped a success toast and finished. So
 * adding an admin, editing permissions, approving a request and revoking access were
 * all silent no-ops from that screen, with revoke the worst of them, since it reported
 * success while the admin kept every grant.
 *
 * Rather than implement the writes a second time, both screens now call this. One place
 * where admin permissions are written means the two cannot drift apart again.
 */
object AdminPermissionsSheet {

    private fun Float.dpIn(activity: android.app.Activity): Float =
        this * activity.resources.displayMetrics.density

    // AdminActivity carried two logAdminAction overloads writing to different collections,
    // and the sheet's call sites used both. Kept as overloads here so that resolution stays
    // exactly what it was: three arguments -> audit_logs, four -> admin_logs.

    /** audit_logs: the tamper-resistant trail. Mirrors AdminActivity's 3-arg overload. */
    private fun logAuditAction(action: String, targetEmail: String, details: String) {
        val currentUserEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "unknown"
        val logData = hashMapOf(
            "action" to action,
            "target_email" to targetEmail.lowercase(),
            "actor_email" to currentUserEmail.lowercase(),
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "details" to details
        )
        FirebaseFirestore.getInstance().collection("audit_logs").add(logData)
    }

    /** admin_logs: the human-readable activity feed. Mirrors AdminActivity's 4-arg overload. */
    private fun logAuditAction(actionType: String, title: String, message: String, details: String?) {
        val adminEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "Unknown Admin"
        val timestamp = System.currentTimeMillis()
        val logData = hashMapOf(
            "adminEmail" to adminEmail,
            "actionType" to actionType,
            "title" to title,
            "message" to message,
            "timestamp" to timestamp
        )
        if (details != null) {
            logData["details"] = details
        }
        FirebaseFirestore.getInstance().collection("admin_logs").document(timestamp.toString()).set(logData)
            .addOnFailureListener { e ->
                android.util.Log.e("AdminPermissionsSheet", "Failed to write admin log: ${e.message}")
            }
    }

    /** Formal in-app notice in the target user's notifications subcollection. */
    private fun sendAdminInAppNotification(targetEmail: String, subject: String, body: String) {
        val db = FirebaseFirestore.getInstance()
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(timestamp)
        val notifData = hashMapOf<String, Any>(
            "subject"   to subject,
            "query"     to body,
            "reply"     to "[ANNOUNCEMENT]",
            "status"    to "resolved",
            "timestamp" to timestamp,
            "time"      to timeStr,
            "read"      to false,
            "promo_id"  to timestamp.toString()
        )
        db.collection("users").document(targetEmail.lowercase())
            .collection("notifications").document(timestamp.toString())
            .set(notifData)
            .addOnFailureListener { e ->
                android.util.Log.e("AdminPermissionsSheet", "Failed to send admin notification: ${e.message}")
            }
    }

    fun show(
        activity: androidx.appcompat.app.AppCompatActivity,
        email: String,
        name: String,
        currentPerms: AdminManager.AdminPermissions,
        isNewAdmin: Boolean = false,
        isReviewingRequest: Boolean = false,
        isExtensionRequest: Boolean = false
    ) {
        // BottomSheetDialogTheme makes the container transparent so our dark bg shows through.
        // Without this style, Material3 forces a white container background.
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)

        // Inflate with the activity's layoutInflater so ?attr colors resolve from the correct dark/light theme
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_admin_permissions, null, false)

        // Programmatically apply dark background with rounded top corners
        val bgColor = ThemeHelper.resolveColorAttr(activity, com.google.android.material.R.attr.colorSurface)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadii = floatArrayOf(20f.dpIn(activity), 20f.dpIn(activity), 20f.dpIn(activity), 20f.dpIn(activity), 0f, 0f, 0f, 0f)
        }
        view.background = bg

        bottomSheet.setContentView(view)
        
        bottomSheet.setOnShowListener { dialog ->
            val d = dialog as com.google.android.material.bottomsheet.BottomSheetDialog
            val bottomSheetInternal = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheetInternal?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val tvEmail = view.findViewById<TextView>(R.id.tvAdminEmail) ?: return
        tvEmail.text = "$name - $email"

        val layoutCheckboxes = view.findViewById<View>(R.id.layoutCheckboxes) ?: return
        
        val cbAnnouncements = view.findViewById<android.widget.CheckBox>(R.id.cbAnnouncements) ?: return
        val cbPromotions = view.findViewById<android.widget.CheckBox>(R.id.cbPromotions) ?: return
        val cbNotifications = view.findViewById<android.widget.CheckBox>(R.id.cbNotifications) ?: return
        val cbLastSeen = view.findViewById<android.widget.CheckBox>(R.id.cbLastSeen) ?: return
        val cbAdminLogs = view.findViewById<android.widget.CheckBox>(R.id.cbAdminLogs) ?: return
        val cbReplyQueries = view.findViewById<android.widget.CheckBox>(R.id.cbReplyQueries) ?: return
        val cbAddNewAdmin = view.findViewById<android.widget.CheckBox>(R.id.cbAddNewAdmin) ?: return
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePermissions) ?: return
        val btnRevoke = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRevokeAccess) ?: return

        var selectedValidUntil = currentPerms.validUntil
        
        val currentUserPerms = AdminManager.getPermissions()
        val isCurrentUserOwner = currentUserPerms.isOwner
        val isCurrentUserSA = !isCurrentUserOwner && currentUserPerms.fullAccess
        val isCurrentUserAdmin = !isCurrentUserOwner && !isCurrentUserSA
        val isCurrentUserLifetime = currentUserPerms.validUntil == 0L
        val canGiveForever = isCurrentUserOwner || (isCurrentUserSA && isCurrentUserLifetime)
        val tvValidityStatus = view.findViewById<TextView>(R.id.tvValidityStatus)
        val btnChangeValidity = view.findViewById<TextView>(R.id.btnChangeValidity)
        val btnRequestExtension = view.findViewById<TextView>(R.id.btnRequestExtension)
        
        val updateValidityUI = {
            if (selectedValidUntil == 0L) {
                tvValidityStatus?.text = "Forever (No expiry)"
            } else {
                val formatted = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                tvValidityStatus?.text = "Valid until: $formatted"
            }
        }
        updateValidityUI()

        val layoutAdminValidity = view.findViewById<View>(R.id.layoutAdminValidity)

        btnChangeValidity?.setOnClickListener {
            val options = if (canGiveForever) {
                arrayOf("Forever (No expiry)", "Choose Date")
            } else {
                arrayOf("Choose Date")
            }

            AlertDialogHelper.showListDialog(activity, "Admin Validity", options) { which ->
                    val selectedText = options[which]
                    if (selectedText == "Forever (No expiry)") {
                        selectedValidUntil = 0L
                        updateValidityUI()
                    } else if (selectedText == "Choose Date") {
                        val cal = java.util.Calendar.getInstance()
                        if (selectedValidUntil > 0) cal.timeInMillis = selectedValidUntil

                        val dpd = android.app.DatePickerDialog(activity, ThemeHelper.getDatePickerTheme(activity), { _, y, m, d ->
                            val newCal = java.util.Calendar.getInstance()
                            newCal.set(y, m, d, 23, 59, 59)
                            selectedValidUntil = newCal.timeInMillis
                            updateValidityUI()
                        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
                        dpd.datePicker.minDate = System.currentTimeMillis() - 1000
                        if (!isCurrentUserLifetime && currentUserPerms.validUntil > 0L) {
                            dpd.datePicker.maxDate = currentUserPerms.validUntil
                        }
                        dpd.show()
                    }
            }
        }

        btnRequestExtension?.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            if (currentUserPerms.validUntil > 0) cal.timeInMillis = currentUserPerms.validUntil

            val dpd = android.app.DatePickerDialog(activity, ThemeHelper.getDatePickerTheme(activity), { _, y, m, d ->
                val newCal = java.util.Calendar.getInstance()
                newCal.set(y, m, d, 23, 59, 59)
                val requestedTime = newCal.timeInMillis
                
                // Submit extension request
                val requestData = hashMapOf(
                    "isFixedOwner" to currentUserPerms.isFixedOwner,
                    "isPromotedOwner" to currentUserPerms.isPromotedOwner,
                    "fullAccess" to currentUserPerms.fullAccess,
                    "sendAnnouncements" to currentUserPerms.sendAnnouncements,
                    "sendPromotions" to currentUserPerms.sendPromotions,
                    "sendNotifications" to currentUserPerms.sendNotifications,
                    "viewLastSeen" to currentUserPerms.viewLastSeen,
                    "viewAdminLogs" to currentUserPerms.viewAdminLogs,
                    "replyToQueries" to currentUserPerms.replyToQueries,
                    "allocateAdmins" to currentUserPerms.allocateAdmins,
                    "validUntil" to requestedTime,
                    "isExtensionRequest" to true,
                    "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).set(requestData)
                    .addOnSuccessListener {
                        ToastHelper.showToast(activity, "Extension request submitted")
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener {
                        ToastHelper.showToast(activity, "Failed to submit request")
                    }
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
            dpd.datePicker.minDate = System.currentTimeMillis() - 1000
            dpd.show()
        }

        val normalColor = if (ThemeHelper.isWhiteTheme(activity)) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.WHITE
        val disabledColor = android.graphics.Color.parseColor("#44888888")

        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_enabled)
        )
        val colors = intArrayOf(
            disabledColor,
            normalColor
        )
        val checkboxTintList = android.content.res.ColorStateList(states, colors)
        cbAnnouncements.buttonTintList = checkboxTintList
        cbPromotions.buttonTintList = checkboxTintList
        cbNotifications.buttonTintList = checkboxTintList
        cbLastSeen.buttonTintList = checkboxTintList
        cbAdminLogs.buttonTintList = checkboxTintList
        cbReplyQueries.buttonTintList = checkboxTintList
        cbAddNewAdmin.buttonTintList = checkboxTintList

        val isWhite = ThemeHelper.isWhiteTheme(activity)
        val greyTextColor = if (isWhite) android.graphics.Color.parseColor("#475569") else android.graphics.Color.parseColor("#E2E8F0")
        val greyBgColor = android.content.res.ColorStateList.valueOf(
            if (isWhite) android.graphics.Color.parseColor("#F1F5F9") else android.graphics.Color.parseColor("#334155")
        )
        
        btnSave.setTextColor(greyTextColor)
        btnSave.backgroundTintList = greyBgColor
        // It's a MaterialButton, remove stroke just in case
        btnSave.strokeWidth = 0

        if (isNewAdmin) {
            btnRevoke.text = "Cancel"
            btnRevoke.setTextColor(greyTextColor)
            btnRevoke.backgroundTintList = greyBgColor
            btnRevoke.strokeWidth = 0
            btnRevoke.setOnClickListener { bottomSheet.dismiss() }
        } else if (isReviewingRequest) {
            btnSave.text = "Approve Request"
            btnRevoke.text = "Reject"
            btnRevoke.setTextColor(greyTextColor)
            btnRevoke.backgroundTintList = greyBgColor
            btnRevoke.strokeWidth = 0
        }

        val isFixed = currentPerms.isFixedOwner
        val isPromotedOwner = currentPerms.isPromotedOwner
        val isTargetOwner = isFixed || isPromotedOwner
        val isTargetSA = !isTargetOwner && (currentPerms.fullAccess || (currentPerms.sendAnnouncements && currentPerms.sendPromotions && currentPerms.sendNotifications && currentPerms.viewLastSeen && currentPerms.viewAdminLogs && currentPerms.replyToQueries && currentPerms.allocateAdmins))
        val isTargetAdmin = !isTargetOwner && !isTargetSA

        // (currentUserPerms, isCurrentUserOwner, isCurrentUserSA, isCurrentUserAdmin defined above)

        val currentRole = if (isTargetOwner) "Owner"
        else if (isTargetSA) "Super Administrator"
        else "Admin"

        val isSelf = email.lowercase() == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.lowercase()

        // Visibility logic
        val canSeeAnnouncements = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canSendAnnouncements()) || (!isNewAdmin && currentPerms.canSendAnnouncements())
        val canSeePromotions = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canSendPromotions()) || (!isNewAdmin && currentPerms.canSendPromotions())
        val canSeeNotifications = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canSendNotifications()) || (!isNewAdmin && currentPerms.canSendNotifications())
        val canSeeLastSeen = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canViewLastSeen()) || (!isNewAdmin && currentPerms.canViewLastSeen())
        val canSeeAdminLogs = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canViewAdminLogs()) || (!isNewAdmin && currentPerms.canViewAdminLogs())
        val canSeeReplyQueries = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canReplyToQueries()) || (!isNewAdmin && currentPerms.canReplyToQueries())
        val canSeeAddNewAdmin = isCurrentUserOwner || (isNewAdmin && currentUserPerms.canAllocateAdmins()) || (!isNewAdmin && currentPerms.canAllocateAdmins())

        cbAnnouncements.visibility = if (canSeeAnnouncements) View.VISIBLE else View.GONE
        cbPromotions.visibility = if (canSeePromotions) View.VISIBLE else View.GONE
        cbNotifications.visibility = if (canSeeNotifications) View.VISIBLE else View.GONE
        cbLastSeen.visibility = if (canSeeLastSeen) View.VISIBLE else View.GONE
        cbAdminLogs.visibility = if (canSeeAdminLogs) View.VISIBLE else View.GONE
        cbReplyQueries.visibility = if (canSeeReplyQueries) View.VISIBLE else View.GONE
        // cbAddNewAdmin visibility is handled dynamically based on other selections, but initial visibility depends on permissions too.
        cbAddNewAdmin.visibility = if (canSeeAddNewAdmin) View.VISIBLE else View.GONE

        // Checked logic
        if (isTargetOwner) {
            cbAnnouncements.isChecked = true
            cbPromotions.isChecked = true
            cbNotifications.isChecked = true
            cbLastSeen.isChecked = true
            cbAdminLogs.isChecked = true
            cbReplyQueries.isChecked = true
            cbAddNewAdmin.isChecked = true
        } else if (isNewAdmin) {
            cbAnnouncements.isChecked = false
            cbPromotions.isChecked = false
            cbNotifications.isChecked = false
            cbLastSeen.isChecked = false
            cbAdminLogs.isChecked = false
            cbReplyQueries.isChecked = false
            cbAddNewAdmin.isChecked = false
        } else {
            cbAnnouncements.isChecked = currentPerms.canSendAnnouncements()
            cbPromotions.isChecked = currentPerms.canSendPromotions()
            cbNotifications.isChecked = currentPerms.canSendNotifications()
            cbLastSeen.isChecked = currentPerms.canViewLastSeen()
            cbAdminLogs.isChecked = currentPerms.canViewAdminLogs()
            cbReplyQueries.isChecked = currentPerms.canReplyToQueries()
            cbAddNewAdmin.isChecked = currentPerms.canAllocateAdmins()
        }

        // Enable/Disable logic
        if (isTargetOwner) {
            cbAnnouncements.isEnabled = false
            cbPromotions.isEnabled = false
            cbNotifications.isEnabled = false
            cbLastSeen.isEnabled = false
            cbAdminLogs.isEnabled = false
            cbReplyQueries.isEnabled = false
            cbAddNewAdmin.isEnabled = false
            btnSave.isEnabled = true
            
            if (isSelf && !currentPerms.isFixedOwner) {
                btnRevoke.visibility = View.VISIBLE
                btnRevoke.text = "Resign as Admin"
            } else {
                btnRevoke.visibility = View.GONE
            }
            
            btnChangeValidity?.visibility = View.GONE
            btnRequestExtension?.visibility = View.GONE
            layoutAdminValidity?.visibility = View.GONE
        } else if (isSelf && !isCurrentUserOwner) {
            // Cannot enable or disable anything in their own permissions option
            cbAnnouncements.isEnabled = false
            cbPromotions.isEnabled = false
            cbNotifications.isEnabled = false
            cbLastSeen.isEnabled = false
            cbAdminLogs.isEnabled = false
            cbReplyQueries.isEnabled = false
            cbAddNewAdmin.isEnabled = false
            btnSave.isEnabled = true
            
            btnRevoke.visibility = View.VISIBLE
            btnRevoke.text = "Resign as Admin"
            btnChangeValidity?.visibility = View.GONE
            
            if (currentUserPerms.validUntil > 0L) {
                btnRequestExtension?.visibility = View.VISIBLE
            } else {
                btnRequestExtension?.visibility = View.GONE
            }
            layoutAdminValidity?.visibility = View.VISIBLE
        } else {
            // Can toggle the ones they can see.
            cbAnnouncements.isEnabled = true
            cbPromotions.isEnabled = true
            cbNotifications.isEnabled = true
            cbLastSeen.isEnabled = true
            cbAdminLogs.isEnabled = true
            cbReplyQueries.isEnabled = true
            cbAddNewAdmin.isEnabled = true
            btnChangeValidity?.visibility = View.VISIBLE
            btnRequestExtension?.visibility = View.GONE
            layoutAdminValidity?.visibility = View.VISIBLE
            if (isSelf) {
                if (!currentPerms.isFixedOwner) {
                    btnRevoke.visibility = View.VISIBLE
                    btnRevoke.text = "Resign as Admin"
                } else {
                    btnRevoke.visibility = View.GONE
                }
            } else {
                btnRevoke.visibility = View.VISIBLE
                btnRevoke.text = "Revoke Access"
            }
        }

        // Dynamic visibility for "Add new admin"
        val updateAddNewAdminVisibility = {
            if (cbAnnouncements.isChecked || cbPromotions.isChecked || cbNotifications.isChecked || cbLastSeen.isChecked || cbAdminLogs.isChecked || cbReplyQueries.isChecked) {
                if (canSeeAddNewAdmin) {
                    cbAddNewAdmin.visibility = View.VISIBLE
                }
            } else {
                cbAddNewAdmin.isChecked = false
                cbAddNewAdmin.visibility = View.GONE
            }
        }
        
        cbAnnouncements.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbPromotions.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbNotifications.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbLastSeen.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbAdminLogs.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }
        cbReplyQueries.setOnCheckedChangeListener { _, _ -> updateAddNewAdminVisibility() }

        // Trigger once to set initial state
        updateAddNewAdminVisibility()

        btnSave.setOnClickListener {
            if (isSelf) {
                ToastHelper.showToast(activity, "Permissions saved")
                bottomSheet.dismiss()
                return@setOnClickListener
            }

            val db = FirebaseFirestore.getInstance()
            
            // if only cbAddNewAdmin is checked, that's equivalent to 0 features (cannot add admin with just this feature)
            val hasFeaturePermissions = cbAnnouncements.isChecked || cbPromotions.isChecked || cbNotifications.isChecked || cbLastSeen.isChecked || cbAdminLogs.isChecked || cbReplyQueries.isChecked
            val hasZeroPermissions = !hasFeaturePermissions
            
            if (hasZeroPermissions) {
                if (isNewAdmin) {
                    ToastHelper.showToast(activity, "Select at least 1 permission.")
                    return@setOnClickListener
                } else if (!isReviewingRequest) {
                    FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                        .addOnSuccessListener {
                            ToastHelper.showToast(activity, "Access revoked")
                            bottomSheet.dismiss()
                        }
                    return@setOnClickListener
                }
            }
            
            val isSuperAdminSave = cbAnnouncements.isChecked && cbPromotions.isChecked && cbNotifications.isChecked && cbLastSeen.isChecked && cbAdminLogs.isChecked && cbReplyQueries.isChecked && cbAddNewAdmin.isChecked
            val isOwnerSave = currentPerms.isPromotedOwner // Can't change owner here anyway since it's removed
            
            var needsRequest = false
            if (!isCurrentUserOwner) {
                if (!currentUserPerms.canAllocateAdmins()) {
                    needsRequest = true
                } else if (isSuperAdminSave && (!currentPerms.fullAccess || isNewAdmin)) {
                    needsRequest = true
                }
            }
            
            if (needsRequest) {
                val requestData = hashMapOf<String, Any>(
                    "email" to email.lowercase(),
                    "name" to name,
                    "isOwner" to false,
                    "fullAccess" to isSuperAdminSave,
                    "sendAnnouncements" to cbAnnouncements.isChecked,
                    "sendPromotions" to cbPromotions.isChecked,
                    "sendNotifications" to cbNotifications.isChecked,
                    "viewLastSeen" to cbLastSeen.isChecked,
                    "viewAdminLogs" to cbAdminLogs.isChecked,
                    "replyToQueries" to cbReplyQueries.isChecked,
                    "allocateAdmins" to cbAddNewAdmin.isChecked,
                    "validUntil" to selectedValidUntil,
                    "requestedBy" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: ""),
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "pending"
                )
                db.collection("admin_requests").document(email.lowercase()).set(requestData)
                    .addOnSuccessListener {
                        ToastHelper.showToast(activity, "Request sent to owners for approval")
                        
                        // Notify owners
                        db.collection("admins").whereEqualTo("isOwner", true).get().addOnSuccessListener { snaps ->
                            val ownerEmails = snaps.documents.map { it.id }.toMutableSet()
                            ownerEmails.addAll(AdminManager.superAdmins) // Include fixed owners
                            
                            val requesterEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An admin"
                            for (owner in ownerEmails) {
                                sendAdminInAppNotification(
                                    targetEmail = owner,
                                    subject = "New Admin Request",
                                    body = "$requesterEmail requested to add/modify $email as an admin. Please review the request."
                                )
                            }
                        }
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener { e -> 
                        ToastHelper.showToast(activity, "Failed to send request. Firebase Rules might be blocking it: ${e.message}") 
                    }
                return@setOnClickListener
            }

            val data = hashMapOf<String, Any>(
                "name" to name,
                "isOwner" to isOwnerSave,
                "fullAccess" to isSuperAdminSave,
                "sendAnnouncements" to cbAnnouncements.isChecked,
                "sendPromotions" to cbPromotions.isChecked,
                "sendNotifications" to cbNotifications.isChecked,
                "viewLastSeen" to cbLastSeen.isChecked,
                "viewAdminLogs" to cbAdminLogs.isChecked,
                "replyToQueries" to cbReplyQueries.isChecked,
                "allocateAdmins" to cbAddNewAdmin.isChecked,
                "validUntil" to selectedValidUntil
            )

            if (isReviewingRequest) {
                db.collection("admins").document(email.lowercase()).set(data)
                    .addOnSuccessListener {
                        db.collection("admin_requests").document(email.lowercase()).delete()
                        val approverEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An administrator"
                        val approverName = approverEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                        val validityStr = if (selectedValidUntil == 0L) "Lifetime\n(Note: Your continued access is a reflection of the trust placed in you. It remains subject to review based on your ongoing performance and commitment to the CashDash platform.)" else java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                        val roleStr = if (isSuperAdminSave) "Super Administrator" else "Administrator"

                        if (isExtensionRequest) {
                            // Extension approved notification
                            val notifSubject = "Your Admin Validity Has Been Extended ✅"
                            val notifBody = "Congratulations, $name!\n\n" +
                                "Your unwavering dedication and service to CashDash have been truly recognized. $approverName has approved your extension request, honoring the invaluable contribution you continue to bring to our platform.\n\n" +
                                "New Validity: $validityStr\n\n" +
                                "Your commitment inspires the entire team, and we look forward to achieving greater milestones together. Thank you for being an integral part of the CashDash journey.\n\n" +
                                "Regards,\nTeam CashDash"
                            sendAdminInAppNotification(email, notifSubject, notifBody)
                            logAuditAction("Admin Extension Approved", notifSubject, "$approverEmail approved extension request for $email. New validity: $validityStr", email)
                        } else {
                            // New admin grant / permission update notification
                            val notifSubject = "Welcome to the CashDash Admin Team! 🎉"
                            val notifBody = "Congratulations, $name!\n\n" +
                                "We are thrilled to welcome you as a $roleStr of CashDash. Your passion and dedication have been recognized, and we are excited to have you as part of our trusted operations team.\n\n" +
                                "Validity: $validityStr\n\n" +
                                "We look forward to accomplishing great things together. Your role is vital in shaping a better CashDash experience for every user we serve.\n\n" +
                                "Regards,\nTeam CashDash"
                            sendAdminInAppNotification(email, notifSubject, notifBody)
                            logAuditAction("Admin Request Approved", notifSubject, "$approverEmail approved admin request for $email as $roleStr. Validity: $validityStr", email)
                        }

                        logAuditAction("APPROVED_REQUEST", email, "Approved request and granted/updated permissions.")
                        ToastHelper.showToast(activity, "Request approved")
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(activity, "Failed: ${e.message}")
                    }
                return@setOnClickListener
            }

            db.collection("admins").document(email.lowercase()).set(data)
                .addOnSuccessListener {
                    val action = if (isNewAdmin) "GRANTED_ACCESS" else "UPDATED_PERMISSIONS"
                    val msg = if (isNewAdmin) "Admin added: $name" else "Permissions updated"
                    logAuditAction(action, email, msg)

                    val actorEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An administrator"
                    val actorName = actorEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                    val validityStr = if (selectedValidUntil == 0L) "Lifetime\n(Note: Your continued access is a reflection of the trust placed in you. It remains subject to review based on your ongoing performance and commitment to the CashDash platform.)" else java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(selectedValidUntil)
                    val roleStr = if (isSuperAdminSave) "Super Administrator" else "Administrator"

                    if (isNewAdmin) {
                        val notifSubject = "Welcome to the CashDash Admin Team! 🎉"
                        val notifBody = "Congratulations, $name!\n\n" +
                            "We are thrilled to welcome you as a $roleStr of CashDash. Your passion and dedication have been recognized, and we are excited to have you as part of our trusted operations team.\n\n" +
                            "Validity: $validityStr\n\n" +
                            "We look forward to accomplishing great things together. Your role is vital in shaping a better CashDash experience for every user we serve.\n\n" +
                            "Regards,\nTeam CashDash"
                        sendAdminInAppNotification(email, notifSubject, notifBody)
                        logAuditAction("Admin Promotion", notifSubject, "$actorEmail granted $roleStr access to $email. Validity: $validityStr", email)
                    } else if (selectedValidUntil != currentPerms.validUntil) {
                        // Validity was manually changed by a higher-up without a formal request
                        val notifSubject = "Your Admin Validity Has Been Updated"
                        val notifBody = "Hi $name,\n\n" +
                            "$actorName has reviewed your admin profile and updated your validity, acknowledging your continued dedication to the CashDash platform.\n\n" +
                            "New Validity: $validityStr\n\n" +
                            "Thank you for the consistent service you bring to CashDash. We deeply value every contribution you make to our community.\n\n" +
                            "Regards,\nTeam CashDash"
                        sendAdminInAppNotification(email, notifSubject, notifBody)
                        logAuditAction("Admin Validity Update", notifSubject, "$actorEmail updated validity for $email to $validityStr", email)
                    }

                    ToastHelper.showToast(activity, msg)
                    activity.findViewById<EditText>(R.id.etSearchNewAdmin)?.setText("")
                    activity.findViewById<LinearLayout>(R.id.layoutAdminSearchResults)?.visibility = View.GONE
                    bottomSheet.dismiss()
                }
                .addOnFailureListener { e -> 
                    ToastHelper.showToast(activity, "Failed to save. Firebase Rules might be blocking it: ${e.message}") 
                }
        }

        if (isReviewingRequest) {
            btnRevoke.setOnClickListener {
                FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).delete()
                    .addOnSuccessListener {
                        logAuditAction("REJECTED_REQUEST", email, "Rejected admin extension/access request.")
                        ToastHelper.showToast(activity, "Request rejected")
                        bottomSheet.dismiss()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.showToast(activity, "Failed to reject: ${e.message}")
                    }
            }
        } else if (isSelf && !currentPerms.isFixedOwner) {
            btnRevoke.setOnClickListener {
                AlertDialogHelper.createFlatDialogBuilder(activity)
                    .setTitle("Resign as Admin")
                    .setMessage("Are you sure you want to resign? You will lose all administrative privileges immediately.")
                    .setPositiveButton("Resign") {
                        FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                            .addOnSuccessListener {
                                FirebaseFirestore.getInstance().collection("admin_requests").document(email.lowercase()).delete()
                                logAuditAction("RESIGNED", email, "User voluntarily resigned from admin privileges.")
                                ToastHelper.showToast(activity, "You have resigned as admin.")
                                bottomSheet.dismiss()
                                activity.finish()
                            }
                            .addOnFailureListener { e ->
                                ToastHelper.showToast(activity, "Failed to resign: ${e.message}")
                            }
                    }
                    .setNegativeButton("Cancel")
                    .show()
            }
        } else if (!isNewAdmin) {
            btnRevoke.setOnClickListener {
                AlertDialogHelper.createFlatDialogBuilder(activity)
                    .setTitle("Revoke Access")
                    .setMessage("Are you sure you want to revoke admin access for $email?")
                    .setPositiveButton("Revoke") {
                        FirebaseFirestore.getInstance().collection("admins").document(email.lowercase()).delete()
                            .addOnSuccessListener {
                                val actorEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: "An administrator"
                                val actorName = actorEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                                val notifSubject = "Admin Access Update"
                                val notifBody = "Hi $name,\n\n" +
                                    "Your administrative access to CashDash has been revoked by $actorName.\n\n" +
                                    "If you believe this was done in error, please reach out to the CashDash team.\n\n" +
                                    "Thank you for your service and contributions to CashDash. We wish you all the best.\n\n" +
                                    "Regards,\nTeam CashDash"
                                sendAdminInAppNotification(email, notifSubject, notifBody)
                                logAuditAction("REVOKED_ACCESS", email, "Revoked all admin privileges.")
                                logAuditAction("Admin Revocation", notifSubject, "$actorEmail revoked admin access for $email", email)
                                ToastHelper.showToast(activity, "Access revoked")
                                bottomSheet.dismiss()
                            }
                            .addOnFailureListener { e ->
                                ToastHelper.showToast(activity, "Failed to revoke: ${e.message}")
                            }
                    }
                    .setNegativeButton("Cancel")
                    .show()
            }
        }

        bottomSheet.show()
    }
}
