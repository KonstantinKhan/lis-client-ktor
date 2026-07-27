package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.client.getWithSession
import com.khan366kos.lis.client.ktor.loodsman.api.dto.GetTypeAttrsOutputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.GetTypesOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ConfMetaData(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    suspend fun typeAttributes(sessionId: String, typeId: Int): List<GetTypeAttrsOutputDto> =
        requestGate.withPermit {
            client.getWithSession("ConfMetaData/get-type-attrs", sessionId) {
                parameter("typeId", typeId)
            }.body()
        }

    suspend fun attributes(sessionId: String): List<GetTypesOutputDto> =
        requestGate.withPermit {
            client.getWithSession("ConfMetaData/get-types", sessionId).body()
        }
}