package com.pcwake.app.data

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface RelayApi {
    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    @GET("api/v1/devices")
    suspend fun listDevices(@Header("Authorization") authHeader: String): DeviceListResponse

    @POST("api/v1/devices/{id}/wake")
    suspend fun wake(@Path("id") deviceId: String, @Header("Authorization") authHeader: String): WakeResponse

    @GET("api/v1/devices/{id}/status")
    suspend fun status(@Path("id") deviceId: String, @Header("Authorization") authHeader: String): StatusResponse

    @POST("api/v1/devices/{id}/shutdown")
    suspend fun shutdown(@Path("id") deviceId: String, @Header("Authorization") authHeader: String): ActionResponse

    @POST("api/v1/devices/{id}/restart")
    suspend fun restart(@Path("id") deviceId: String, @Header("Authorization") authHeader: String): ActionResponse
}
