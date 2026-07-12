package com.cash.dash

data class FinminderItem(
    val id: String,
    val type: String, // "CASH_IN" or "CASH_OUT"
    val title: String,
    val quantity: String,
    val frequency: String, // "One time", "Weekly", "Monthly"
    val dateInfo: String, // E.g., "15/06/2026", "Monday", "15"
    val isChecked: Boolean = false,
    val completedDates: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
