package com.andromapper.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class OfflinePackageStatus { PENDING, DOWNLOADING, READY }

@Entity(tableName = "offline_packages")
data class OfflinePackageEntity(
    @PrimaryKey val id: Int,
    val layerId: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val bbox: String,
    val localPath: String = "",
    val status: OfflinePackageStatus = OfflinePackageStatus.PENDING
)
