package com.andromapper.data.local

import androidx.room.*
import com.andromapper.data.local.entity.LayerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LayerDao {
    @Query("SELECT * FROM layers ORDER BY name ASC")
    fun observeAll(): Flow<List<LayerEntity>>

    @Query("SELECT * FROM layers WHERE isEnabled = 1")
    suspend fun getEnabled(): List<LayerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(layers: List<LayerEntity>)

    @Update
    suspend fun update(layer: LayerEntity)

    @Query("DELETE FROM layers WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM layers WHERE id = :id")
    suspend fun getById(id: Int): LayerEntity?
}
