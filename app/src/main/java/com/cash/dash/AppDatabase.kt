package com.cash.dash

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TransactionEntity::class, NotificationEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `notifications` (`id` TEXT NOT NULL, `subject` TEXT NOT NULL, `query` TEXT NOT NULL, `reply` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `status` TEXT NOT NULL, `read` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Transactions table primary key change
                db.execSQL("CREATE TABLE IF NOT EXISTS `transactions_new` (`timestamp` INTEGER NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `amount` INTEGER NOT NULL, `week` INTEGER NOT NULL, `day` INTEGER NOT NULL, `month` INTEGER NOT NULL, `year` INTEGER NOT NULL, `rawEntry` TEXT NOT NULL, PRIMARY KEY(`timestamp`))")
                db.execSQL("INSERT INTO transactions_new (timestamp, title, category, amount, week, day, month, year, rawEntry) SELECT timestamp, title, category, amount, week, day, month, year, rawEntry FROM transactions")
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cashdash_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
