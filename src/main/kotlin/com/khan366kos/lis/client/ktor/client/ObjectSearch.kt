package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.FindObjectsSimpleInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.FindObjectsSimpleOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ObjectSearch(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    suspend fun findBySimpleSearch(sessionId: String, request: FindObjectsSimpleInputDto): List<FindObjectsSimpleOutputDto> =
        requestGate.withPermit {
            client.postWithSession("ObjectSearch/find-by-simple-search", sessionId) {
                setBody(request)
            }.body()
        }
}
