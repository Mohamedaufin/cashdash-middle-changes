package com.cash.dash

import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedList
import android.widget.EditText
import android.widget.TextView
import android.view.View

object GenerativeAiManager {

    private val requestTimestamps = LinkedList<Long>()
    private const val MAX_REQUESTS_PER_MINUTE = 10
    private const val TIME_WINDOW_MS = 60000L

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

    fun canMakeRequest(): Boolean {
        cleanOldRequests()
        return requestTimestamps.size < MAX_REQUESTS_PER_MINUTE
    }

    fun getWaitTimeSeconds(): Long {
        cleanOldRequests()
        if (requestTimestamps.isEmpty()) return 0
        val oldest = requestTimestamps.first
        val now = System.currentTimeMillis()
        val timePassed = now - oldest
        val remainingMs = TIME_WINDOW_MS - timePassed
        return if (remainingMs > 0) remainingMs / 1000 else 0
    }

    private fun cleanOldRequests() {
        val now = System.currentTimeMillis()
        val threshold = now - TIME_WINDOW_MS
        while (requestTimestamps.isNotEmpty() && requestTimestamps.first < threshold) {
            requestTimestamps.removeFirst()
        }
    }

    private fun recordRequest() {
        requestTimestamps.addLast(System.currentTimeMillis())
    }

    suspend fun rephraseText(text: String, isTitle: Boolean, apiKey: String): String = withContext(Dispatchers.IO) {
        if (!canMakeRequest()) {
            throw Exception("Rate limit exceeded. Try again in ${getWaitTimeSeconds()} seconds.")
        }
        
        recordRequest()

        val model = GenerativeModel("gemini-flash-latest", apiKey)
        val prompt = if (isTitle) {
            "Rewrite this title in a highly professional, corporate, and encouraging tone suitable for a fintech platform (CashDash). CashDash offers legitimate deals, announcements, and admin communications. CRITICAL: Do not invent or add features like 'cashback', 'rewards', or 'discounts' unless they are explicitly mentioned in the original text. Keep it short (max 5 words). Only return the rewritten text without quotes, do not include any other commentary.\n\nTitle:\n$text"
        } else {
            "Rewrite the following notification message in a highly professional, corporate, and encouraging tone suitable for a fintech platform (CashDash). CashDash offers legitimate deals, announcements, and admin communications. CRITICAL: Do not invent or add features like 'cashback', 'rewards', or 'discounts' unless they are explicitly mentioned in the original text. Only return the rewritten text, do not include any other commentary. Keep the {Validity} placeholder exactly as it is if it exists in the original text.\n\nMessage:\n$text"
        }

        try {
            val response = model.generateContent(prompt)
            return@withContext response.text?.trim()?.removeSurrounding("\"") ?: text
        } catch (e: Exception) {
            val msg = e.message ?: ""
            throw Exception("API Error: $msg")
        }
    }
}
