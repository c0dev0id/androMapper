package com.andromapper.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.andromapper.data.local.entity.LayerEntity
import com.andromapper.data.local.entity.LayerType
import com.andromapper.data.local.entity.OfflinePackageEntity
import com.andromapper.data.local.entity.OfflinePackageStatus

class Converters {
    @TypeConverter
    fun fromLayerType(value: LayerType): String = value.name
    @TypeConverter
    fun toLayerType(value: String): LayerType = LayerType.valueOf(value)
    @TypeConverter
    fun fromOfflineStatus(value: OfflinePackageStatus): String = value.name
    @TypeConverter
    fun toOfflineStatus(value: String): OfflinePackageStatus = OfflinePackageStatus.valueOf(value)
}

@Database(
    entities = [LayerEntity::class, OfflinePackageEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun layerDao(): LayerDao
    abstract fun offlinePackageDao(): OfflinePackageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "andromapper.db"
                ).build().also { INSTANCE = it }
            }
    }
}
