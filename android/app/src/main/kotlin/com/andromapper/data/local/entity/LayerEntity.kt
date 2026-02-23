package com.andromapper.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LayerType { RASTER, VECTOR }

@Entity(tableName = "layers")
data class LayerEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val type: LayerType,
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
    val isEnabled: Boolean = true,
    val isOfflineAvailable: Boolean = false
)
