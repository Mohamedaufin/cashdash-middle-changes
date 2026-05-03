package com.cash.dash

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val title: String,
    val category: String,
    val amount: Int,
    val week: Int,
    val day: Int,
    val month: Int,
    val year: Int,
    val rawEntry: String // Keeping the original string format for backward compatibility/sync
)
