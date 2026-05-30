package com.cash.dash

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp ASC")
    fun getTransactionsInRange(startMillis: Long, endMillis: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE category = :category AND timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp ASC")
    fun getTransactionsByCategoryInRange(category: String, startMillis: Long, endMillis: Long): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM transactions WHERE rawEntry = :rawEntry LIMIT 1")
    suspend fun findByRawEntry(rawEntry: String): TransactionEntity?

    @Query("DELETE FROM transactions WHERE timestamp = :timestamp")
    suspend fun deleteByTimestamp(timestamp: Long)

    @Query("UPDATE transactions SET amount = :newAmount, rawEntry = :newRawEntry WHERE timestamp = :timestamp")
    suspend fun updateAmountByTimestamp(timestamp: Long, newAmount: Int, newRawEntry: String)

    @Query("UPDATE transactions SET title = :newTitle, rawEntry = :newRawEntry WHERE timestamp = :timestamp")
    suspend fun updateTitleByTimestamp(timestamp: Long, newTitle: String, newRawEntry: String)

    @Query("UPDATE transactions SET category = :newCategory, rawEntry = :newRawEntry WHERE timestamp = :timestamp")
    suspend fun updateCategoryByTimestamp(timestamp: Long, newCategory: String, newRawEntry: String)

    @Query("SELECT SUM(amount) FROM transactions WHERE category = :category AND timestamp BETWEEN :startMillis AND :endMillis")
    fun getSumByCategoryInRange(category: String, startMillis: Long, endMillis: Long): Float?

    @Query("DELETE FROM transactions WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    @Query("SELECT category FROM transactions WHERE title = :title ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastCategoryForTitle(title: String): String?
}
