package com.andromapper.data.remote.model

import com.google.gson.annotations.SerializedName

data class CreateOfflinePackageRequest(
    @SerializedName("layerId")  val layerId: Int,
    @SerializedName("minZoom")  val minZoom: Int,
    @SerializedName("maxZoom")  val maxZoom: Int,
    @SerializedName("bbox")     val bbox: String
)

data class OfflinePackageResponse(
    @SerializedName("packageId") val packageId: Int,
    @SerializedName("status")    val status: String
)
