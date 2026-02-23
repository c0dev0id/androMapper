package com.andromapper.data.local

import androidx.room.*
import com.andromapper.data.local.entity.OfflinePackageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflinePackageDao {
    @Query("SELECT * FROM offline_packages ORDER BY id DESC")
    fun observeAll(): Flow<List<OfflinePackageEntity>>

    @Query("SELECT * FROM offline_packages WHERE layerId = :layerId AND status = 'READY'")
    suspend fun getReadyByLayer(layerId: Int): List<OfflinePackageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pkg: OfflinePackageEntity)

    @Update
    suspend fun update(pkg: OfflinePackageEntity)

    @Query("DELETE FROM offline_packages WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM offline_packages WHERE id = :id")
    suspend fun getById(id: Int): OfflinePackageEntity?
}
