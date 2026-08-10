package com.cash.dash

import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedList

object GenerativeAiManager {

    private val requestTimestamps = LinkedList<Long>()
    private const val MAX_REQUESTS_PER_MINUTE = 10
    private const val TIME_WINDOW_MS = 60000L

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
            "Rewrite this title in a highly professional, corporate, and encouraging tone suitable for a fintech platform (CashDash) offering deals, cashback, and financial insights. Keep it short (max 5 words). Only return the rewritten text without quotes, do not include any other commentary.\n\nTitle:\n$text"
        } else {
            "Rewrite the following notification message in a highly professional, corporate, and encouraging tone suitable for a fintech platform (CashDash) offering deals, cashback, and financial insights. Only return the rewritten text, do not include any other commentary. Keep the {Validity} placeholder exactly as it is if it exists in the original text.\n\nMessage:\n$text"
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
