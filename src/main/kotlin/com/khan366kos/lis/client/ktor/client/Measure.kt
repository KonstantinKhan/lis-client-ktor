package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.MeasureUnitOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Measure(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    suspend fun unitsByDesignation(sessionId: String, designation: String): List<MeasureUnitOutputDto> =
        requestGate.withPermit {
            client.getWithSession("Measure/units-by-designation", sessionId) {
                parameter("designation", designation)
            }.body()
        }
}
