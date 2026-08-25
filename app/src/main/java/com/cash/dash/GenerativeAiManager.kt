package com.cash.dash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.EditText
import android.widget.TextView
import android.view.View

/**
 * Rephrasing runs through the `rephraseSupportText` Cloud Function.
 *
 * The Gemini API key used to be passed in from the call sites as a string literal,
 * which put it in plaintext inside every shipped APK — R8 obfuscates identifiers,
 * not string constants. The key now lives in Firebase secrets and never reaches
 * the client. Rate limiting is enforced server-side per admin for the same reason:
 * the old in-process counter here was trivially bypassed.
 */
object GenerativeAiManager {

    /**
     * Which Gemini key the server should bill. The two scopes map to separate keys
     * so announcements/notifications and promotions/admin-access draw on separate
     * quotas — the same split the app used to have hardcoded. The key values
     * themselves stay in Firebase secrets; the client only names a scope.
     */
    object Scope {
        /** AdminMessagingActivity — announcements and push notifications. */
        const val MESSAGING = "messaging"

        /** AdminPromotionsActivity and ManageAdminAccessActivity. */
        const val ADMIN = "admin"
    }

    class RephraseState(
        val editText: EditText,
        val undoView: TextView,
        val redoView: TextView
    ) {
        private val history = mutableListOf<String>()
        private var currentIndex = -1

        init {
            undoView.setOnClickListener {
                if (currentIndex > 0) {
                    currentIndex--
                    editText.setText(history[currentIndex])
                    updateVisibility()
                } else {
                    ToastHelper.showToast(editText.context, "No previous state found.")
                }
            }

            redoView.setOnClickListener {
                if (currentIndex < history.size - 1) {
                    currentIndex++
                    editText.setText(history[currentIndex])
                    updateVisibility()
                } else {
                    ToastHelper.showToast(editText.context, "No newer state found.")
                }
            }
        }

        fun saveState(text: String) {
            if (history.isEmpty()) {
                history.add(text)
                currentIndex = 0
            } else if (currentIndex < history.size - 1) {
                history.subList(currentIndex + 1, history.size).clear()
                // Only add if it's different from current
                if (history[currentIndex] != text) {
                    history.add(text)
                    currentIndex++
                }
            } else {
                if (history.last() != text) {
                    history.add(text)
                    currentIndex++
                }
            }
            updateVisibility()
        }

        fun updateVisibility() {
            undoView.visibility = if (currentIndex > 0) View.VISIBLE else View.GONE
            redoView.visibility = if (currentIndex < history.size - 1) View.VISIBLE else View.GONE
        }
    }

    suspend fun rephraseText(
        text: String,
        isTitle: Boolean,
        scope: String = Scope.MESSAGING
    ): String = withContext(Dispatchers.IO) {
        val payload = hashMapOf(
            "text" to text,
            "isTitle" to isTitle,
            "scope" to scope
        )

        try {
            val task = com.google.firebase.functions.FirebaseFunctions
                .getInstance("us-central1")
                .getHttpsCallable("rephraseSupportText")
                .call(payload)

            // Already on Dispatchers.IO, so blocking on the Task is safe here and
            // avoids pulling in kotlinx-coroutines-play-services just for await().
            val result = com.google.android.gms.tasks.Tasks.await(task)

            @Suppress("UNCHECKED_CAST")
            val data = result.getData() as? Map<String, Any?>
            return@withContext (data?.get("text") as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: text
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Must propagate untouched. Tasks.await below is a blocking call, so when
            // the caller's lifecycleScope is cancelled (activity destroyed) the
            // cancellation surfaces here — repackaging it as a plain Exception made
            // call sites treat a dead screen as an API error and pop a dialog on it.
            throw e
        } catch (e: Exception) {
            val cause = e.cause ?: e
            val msg = when {
                cause is com.google.firebase.functions.FirebaseFunctionsException &&
                    cause.code == com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    "Rate limit reached. Please try again in a minute."
                cause is com.google.firebase.functions.FirebaseFunctionsException &&
                    cause.code == com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    "You do not have permission to use rephrase."
                cause is com.google.firebase.functions.FirebaseFunctionsException &&
                    cause.code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND ->
                    "Rephrase service is not deployed yet. Run: firebase deploy --only functions"
                cause is com.google.firebase.functions.FirebaseFunctionsException &&
                    cause.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "Please sign in again."
                cause is com.google.firebase.functions.FirebaseFunctionsException &&
                    cause.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE ->
                    "The rephrase model is busy right now. Please try again in a moment."
                cause is com.google.firebase.functions.FirebaseFunctionsException &&
                    cause.code == com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Rephrase took too long to respond. Please try again."
                else -> cause.message ?: "Rephrase failed."
            }
            throw Exception(msg)
        }
    }
}
