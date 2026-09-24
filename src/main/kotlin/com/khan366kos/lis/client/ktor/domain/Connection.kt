package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class Connection(
    val dbName: String,
    val remember: Boolean = false,
    val url: String,
    val maxConcurrentRequests: Int = 10,
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
){
    companion object{
        val None = Connection(
            dbName = "",
            url = ""
        )
    }
}
