package com.cash.dash

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val query: String,
    val reply: String,
    val timestamp: Long,
    val status: String,
    val read: Boolean,
    val imageUrl: String? = null,
    val imageUrls: String? = null  // JSON array string stored in Room
)
