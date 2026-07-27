package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.client.getWithSession
import com.khan366kos.lis.client.ktor.loodsman.api.dto.GetOriginalAttributesOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ObjectInfo(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    suspend fun attrValue(sessionId: String, typeId: Int): List<GetOriginalAttributesOutputDto> =
        requestGate.withPermit {
            client.getWithSession("ObjectInfo/get-original-attributes", sessionId) {
                parameter("typeId", typeId)
            }.body()
        }
}