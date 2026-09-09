package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PriceTickEntity::class, PredictionEntity::class, WalkForwardAuditEntity::class], version = 1, exportSchema = false)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
