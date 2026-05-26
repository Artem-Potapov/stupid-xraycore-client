package com.justme.xtls_core_proxy.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Profile::class, Subscription::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xraytun.db"
                )
                    .fallbackToDestructiveMigrationFrom(false, 1)
                    .build()
                    .also { INSTANCE = it }
            }

        @VisibleForTesting
        fun setInstanceForTests(db: AppDatabase?) {
            synchronized(this) {
                INSTANCE = db
            }
        }
    }
}
