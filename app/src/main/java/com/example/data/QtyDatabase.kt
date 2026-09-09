package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PriceTickEntity::class, IngestionEventEntity::class, PredictionEntity::class, WalkForwardAuditEntity::class], version = 2, exportSchema = false)
abstract class QtyDatabase : RoomDatabase() {
    abstract fun qtyDao(): QtyDao

    companion object {
        @Volatile
        private var INSTANCE: QtyDatabase? = null

        fun getDatabase(context: Context): QtyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QtyDatabase::class.java,
                    "qty_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
