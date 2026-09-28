package com.khan366kos.lis.client.ktor.polynom.client

import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class PolynomElement(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    suspend fun getBoLocation(accessToken: String, data: IdentifiableObjectDto): String =
        requestGate.withPermit {
            client.postWithBearer("api/v1/element/get-bo-location", accessToken) {
                setBody(data)
            }.body<String>().removeSurrounding("\"")
        }
}