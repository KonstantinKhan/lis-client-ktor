package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.IdentifierDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewChangeGroup2InputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewChangeVariant2InputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ObjectConfiguration(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    suspend fun newChangeGroup2(sessionId: String, group: NewChangeGroup2InputDto): IdentifierDto =
        requestGate.withPermit {
            client.postWithSession("ObjectConfiguration/new-change-group-2", sessionId) {
                setBody(group)
            }.body()
        }

    suspend fun newChangeVariant2(sessionId: String, variant: NewChangeVariant2InputDto): IdentifierDto =
        requestGate.withPermit {
            client.postWithSession("ObjectConfiguration/new-change-variant-2", sessionId) {
                setBody(variant)
            }.body()
        }
}
