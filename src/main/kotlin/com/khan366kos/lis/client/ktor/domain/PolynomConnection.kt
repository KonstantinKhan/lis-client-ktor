package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class PolynomConnection(
    val url: String,
    val storageId: String? = null,
    val moduleName: String = "LIS-Client",
    val clientType: Int = 8,
    val maxConcurrentRequests: Int = 5,
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
) {
    companion object {
        val None = PolynomConnection(url = "")
    }
}
