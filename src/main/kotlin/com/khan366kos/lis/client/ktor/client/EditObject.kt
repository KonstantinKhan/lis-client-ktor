package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.CreateBoObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.IdentifierDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class EditObject(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    suspend fun newLink(sessionId: String, link: NewLinkInputDto): IdentifierDto =
        requestGate.withPermit {
            client.postWithSession("EditObject/new-link", sessionId) {
                setBody(link)
            }.body()
        }

    suspend fun setValues(sessionId: String, data: List<UpAttrValuesByIdsInputDto>): List<UpAttrValuesByIdsOutputDto> =
        requestGate.withPermit {
            client.postWithSession("EditObject/up-attr-values-by-ids", sessionId) {
                setBody(data)
            }.body()
        }

    suspend fun create(sessionId: String, loodsmanObject: NewObjectInputDto): IdentifierDto =
        requestGate.withPermit {
            client.postWithSession("EditObject/new-object", sessionId) {
                setBody(loodsmanObject)
            }.body()
        }

    // Создаёт объект интегрированного с ПОЛИНОМ:MDM типа: создание и связывание с элементом
    // Полином (по его location-строке) происходят за один нативный вызов Loodsman.
    suspend fun createBoObject(sessionId: String, data: CreateBoObjectInputDto): Int =
        requestGate.withPermit {
            client.postWithSession("EditObject/create-bo-object", sessionId) {
                setBody(data)
            }.body()
        }
}