package com.andromapper.data.remote

import com.andromapper.data.remote.model.CreateOfflinePackageRequest
import com.andromapper.data.remote.model.LayerResponse
import com.andromapper.data.remote.model.OfflinePackageResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("api/layers")
    suspend fun getLayers(): List<LayerResponse>

    @GET("api/layers/{id}")
    suspend fun getLayer(@Path("id") id: Int): LayerResponse

    @GET("tiles/{layerId}/{z}/{x}/{y}.png")
    suspend fun getTile(
        @Path("layerId") layerId: Int,
        @Path("z") z: Int,
        @Path("x") x: Int,
        @Path("y") y: Int
    ): Response<ResponseBody>

    @GET("geojson/{layerId}")
    suspend fun getGeoJson(
        @Path("layerId") layerId: Int,
        @Query("bbox") bbox: String? = null
    ): ResponseBody

    @POST("api/offline-package")
    suspend fun createOfflinePackage(
        @Body request: CreateOfflinePackageRequest
    ): OfflinePackageResponse

    @GET("api/offline-package/{id}")
    suspend fun getOfflinePackageStatus(
        @Path("id") id: Int
    ): OfflinePackageResponse

    @Streaming
    @GET("api/offline-package/{id}/download")
    suspend fun downloadOfflinePackage(
        @Path("id") id: Int
    ): Response<ResponseBody>
}
