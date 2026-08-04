package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.ReferenceBoVersionInputDto
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class BoReference(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    // Сопоставление уже созданной версии объекта Loodsman со справочным объектом ПОЛИНОМ:MDM
    // по location — без типизированного тела ответа, swagger не описывает `<` для этого метода
    // (тот же приём, что EditObject.upLink).
    suspend fun referenceBoVersion(sessionId: String, data: ReferenceBoVersionInputDto): HttpResponse =
        requestGate.withPermit {
            client.postWithSession("BoReference/reference-bo-version", sessionId) {
                setBody(data)
            }
        }
}
