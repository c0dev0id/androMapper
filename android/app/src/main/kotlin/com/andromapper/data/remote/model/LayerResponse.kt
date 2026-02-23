package com.andromapper.data.remote.model

import com.google.gson.annotations.SerializedName

data class LayerResponse(
    @SerializedName("id")         val id: Int,
    @SerializedName("name")       val name: String,
    @SerializedName("type")       val type: String,
    @SerializedName("min_zoom")   val minZoom: Int = 0,
    @SerializedName("max_zoom")   val maxZoom: Int = 18,
    @SerializedName("status")     val status: String = "pending",
    @SerializedName("source_url") val sourceUrl: String? = null
)
