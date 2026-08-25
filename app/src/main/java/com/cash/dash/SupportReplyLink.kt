package com.cash.dash

import android.app.Activity
import android.content.Intent

/**
 * The admin inbox used to build the reply URL itself:
 *
 *     "https://adminreply-...run.app?uid=$userEmail&id=$docId&email=$userEmail"
 *
 * That URL was the entire access control on the adminReply endpoint — an email
 * address plus a millisecond timestamp, both guessable. The endpoint now requires
 * an HMAC signature that only the server can produce, so the link has to be
 * requested through an authenticated callable that checks admin status first.
 */
object SupportReplyLink {

    fun openReplyPage(activity: Activity, userEmail: String, docId: String) {
        ToastHelper.showToast(activity, "Opening reply page…")

        val payload = hashMapOf(
            "userEmail" to userEmail,
            "docId" to docId
        )

        com.google.firebase.functions.FirebaseFunctions
            .getInstance("asia-south1")
            .getHttpsCallable("getSupportReplyLink")
            .call(payload)
            .addOnSuccessListener { result ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener

                @Suppress("UNCHECKED_CAST")
                val data = result.getData() as? Map<String, Any?>
                val url = data?.get("url") as? String

                if (url.isNullOrEmpty()) {
                    ToastHelper.showToast(activity, "Could not open reply page.")
                    return@addOnSuccessListener
                }

                activity.startActivity(
                    Intent(activity, WebViewActivity::class.java).apply {
                        putExtra("title", "Admin Reply")
                        putExtra("url", url)
                    }
                )
            }
            .addOnFailureListener { e ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnFailureListener
                val msg = when {
                    e is com.google.firebase.functions.FirebaseFunctionsException &&
                        e.code == com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                        "Your admin access does not allow this, or it has expired."
                    e is com.google.firebase.functions.FirebaseFunctionsException &&
                        e.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                        "Please sign in again."
                    else -> "Could not open reply page. Check your connection."
                }
                ToastHelper.showToast(activity, msg)
            }
    }
}
