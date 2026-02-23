package com.andromapper.data.repository

import android.util.Log
import com.andromapper.data.local.AppDatabase
import com.andromapper.data.local.entity.LayerEntity
import com.andromapper.data.local.entity.LayerType
import com.andromapper.data.remote.NetworkClient
import com.andromapper.data.remote.model.LayerResponse
import kotlinx.coroutines.flow.Flow

class LayerRepository(private val db: AppDatabase) {

    fun observeLayers(): Flow<List<LayerEntity>> = db.layerDao().observeAll()

    suspend fun refreshLayers(): Result<Unit> = runCatching {
        val remote = NetworkClient.apiService.getLayers()
        val entities = remote.map { it.toEntity() }
        db.layerDao().insertAll(entities)
    }.onFailure { Log.w("LayerRepository", "Failed to refresh layers", it) }

    suspend fun setLayerEnabled(layerId: Int, enabled: Boolean) {
        db.layerDao().getById(layerId)?.let { entity ->
            db.layerDao().update(entity.copy(isEnabled = enabled))
        }
    }

    suspend fun markOfflineAvailable(layerId: Int, available: Boolean) {
        db.layerDao().getById(layerId)?.let { entity ->
            db.layerDao().update(entity.copy(isOfflineAvailable = available))
        }
    }

    private fun LayerResponse.toEntity(): LayerEntity {
        val layerType = when (type.lowercase()) {
            "wms", "geotiff", "geopdf" -> LayerType.RASTER
            "wfs", "shapefile", "geojson" -> LayerType.VECTOR
            else -> LayerType.RASTER
        }
        return LayerEntity(
            id = id,
            name = name,
            type = layerType,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }
}
