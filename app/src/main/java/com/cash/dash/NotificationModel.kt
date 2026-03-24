package com.cash.dash

import android.text.Spanned

data class NotificationModel(
    val id: String,
    val queryFormatted: Spanned,
    val replyFormatted: Spanned?,
    val timestamp: Long,
    val title: String,
    val timeFormatted: String,
    val statusColor: Int,
    val isPending: Boolean,
    val isUnread: Boolean,
    val isResolved: Boolean = false,
    val originalSubject: String = "",
    val originalQuery: String = "",
    val originalReply: String = ""
)
