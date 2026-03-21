package com.yourname.wordtone

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class RewriteRequest(
    val text: String,
    val tone: String
)

data class RewriteResponse(
    val variations: List<String>
)

data class HealthResponse(
    val status: String,
    val model: String
)

interface ApiService {

    @POST("rewrite")
    suspend fun rewrite(@Body request: RewriteRequest): RewriteResponse

    @GET("health")
    suspend fun health(): HealthResponse
}
