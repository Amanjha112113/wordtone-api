package com.yourname.wordtone

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class RewriteAllRequest(val text: String)

data class RewriteAllResponse(val rewrites: Map<String, List<String>>)

interface ApiService {
    @POST("rewrite-all")
    suspend fun rewriteAll(@Body request: RewriteAllRequest): RewriteAllResponse

    @GET("health")
    suspend fun health(): Map<String, String>
}
