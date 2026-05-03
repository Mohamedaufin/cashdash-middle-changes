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

    @Query("DELETE FROM transactions WHERE rawEntry = :rawEntry")
    suspend fun deleteByRawEntry(rawEntry: String)

    @Query("UPDATE transactions SET title = :newTitle, rawEntry = :newRawEntry WHERE rawEntry = :oldRawEntry")
    suspend fun updateTitle(oldRawEntry: String, newRawEntry: String, newTitle: String)

    @Query("UPDATE transactions SET amount = :newAmount, rawEntry = :newRawEntry WHERE rawEntry = :oldRawEntry")
    suspend fun updateAmount(oldRawEntry: String, newRawEntry: String, newAmount: Int)

    @Query("UPDATE transactions SET category = :newCategory, rawEntry = :newRawEntry WHERE rawEntry = :oldRawEntry")
    suspend fun updateCategory(oldRawEntry: String, newRawEntry: String, newCategory: String)

    @Query("SELECT SUM(amount) FROM transactions WHERE category = :category AND timestamp BETWEEN :startMillis AND :endMillis")
    fun getSumByCategoryInRange(category: String, startMillis: Long, endMillis: Long): Float?

    @Query("DELETE FROM transactions WHERE category = :category")
    suspend fun deleteByCategory(category: String)
}
