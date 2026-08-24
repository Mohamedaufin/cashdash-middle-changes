import os

path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\ManageAdminAccessActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove onBackPressedDispatcher block
content = content.replace('''        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findViewById<View>(R.id.layoutEditPermissionsContainer)?.visibility == View.VISIBLE) {
                    closeEditPermissionsView(withConfirmation = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })''', '')

# 2. Replace showEditAdminPermissionsDialog calls
content = content.replace('showEditAdminPermissionsDialog(email, name, requestedPerms, isNewAdmin = false, isReviewingRequest = true, isExtensionRequest = isExtension)', 'launchEditPermissionsActivity(email, name, requestedPerms, isNewAdmin = false, isReviewingRequest = true, isExtensionRequest = isExtension)')
content = content.replace('showEditAdminPermissionsDialog(email, name, perms, isNewAdmin = false)', 'launchEditPermissionsActivity(email, name, perms, isNewAdmin = false)')
content = content.replace('showEditAdminPermissionsDialog(user.email, user.name, AdminManager.AdminPermissions(), isNewAdmin = true)', 'launchEditPermissionsActivity(user.email, user.name, AdminManager.AdminPermissions(), isNewAdmin = true)')

# 3. Replace the block
start_idx = content.find('    private fun showCancelConfirmationDialog(')
end_idx = content.find('    override fun onSaveInstanceState(', start_idx)
if start_idx != -1 and end_idx != -1:
    replacement = '''    private fun launchEditPermissionsActivity(
        email: String,
        name: String,
        perms: AdminManager.AdminPermissions,
        isNewAdmin: Boolean = false,
        isReviewingRequest: Boolean = false,
        isExtensionRequest: Boolean = false
    ) {
        val intent = android.content.Intent(this, EditAdminPermissionsActivity::class.java).apply {
            putExtra("email", email)
            putExtra("name", name)
            putExtra("isNewAdmin", isNewAdmin)
            putExtra("isReviewingRequest", isReviewingRequest)
            putExtra("isExtensionRequest", isExtensionRequest)
            putExtra("isFixedOwner", perms.isFixedOwner)
            putExtra("isPromotedOwner", perms.isPromotedOwner)
            putExtra("fullAccess", perms.fullAccess)
            putExtra("sendAnnouncements", perms.sendAnnouncements)
            putExtra("sendPromotions", perms.sendPromotions)
            putExtra("sendNotifications", perms.sendNotifications)
            putExtra("viewLastSeen", perms.viewLastSeen)
            putExtra("viewAdminLogs", perms.viewAdminLogs)
            putExtra("replyToQueries", perms.replyToQueries)
            putExtra("allocateAdmins", perms.allocateAdmins)
            putExtra("validUntil", perms.validUntil)
        }
        startActivity(intent)
    }

'''
    content = content[:start_idx] + replacement + content[end_idx:]

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
