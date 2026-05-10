package com.aasa.eldercare.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aasa.eldercare.data.dao.ConversationDao
import com.aasa.eldercare.data.dao.MedicationDao
import com.aasa.eldercare.data.dao.MemoryDao
import com.aasa.eldercare.data.dao.TrustedContactDao
import com.aasa.eldercare.data.entity.ConversationEntity
import com.aasa.eldercare.data.entity.MedicationEntity
import com.aasa.eldercare.data.entity.MedicationLogEntity
import com.aasa.eldercare.data.entity.MemoryEntity
import com.aasa.eldercare.data.entity.TrustedContactEntity

@Database(
    entities = [
        MedicationEntity::class,
        MedicationLogEntity::class,
        MemoryEntity::class,
        TrustedContactEntity::class,
        ConversationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun medicationDao(): MedicationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun trustedContactDao(): TrustedContactDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val DATABASE_NAME = "aasa.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                // Phase 4 only: schema is fresh, so a destructive
                // fallback is fine. Replace with real Migrations the
                // moment we ship to real users.
                .fallbackToDestructiveMigration()
                .build()
    }
}
