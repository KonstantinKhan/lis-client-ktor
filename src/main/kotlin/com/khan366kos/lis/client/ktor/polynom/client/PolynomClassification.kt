package com.khan366kos.lis.client.ktor.polynom.client

import com.khan366kos.lis.client.ktor.polynom.api.dto.CreateElementRequestDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.ElementCatalogDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.ElementDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.ElementGroupDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.ReferenceDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Работа с деревом классификации ПОЛИНОМ:MDM: справочник → каталог → группа → элемент.
class PolynomClassification(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    suspend fun getAllReferences(accessToken: String): List<ReferenceDto> =
        requestGate.withPermit {
            client.postWithBearer("api/v1/reference/get-all", accessToken).body()
        }

    suspend fun getCatalogsByReference(accessToken: String, reference: IdentifiableObjectDto): List<ElementCatalogDto> =
        requestGate.withPermit {
            client.postWithBearer("api/v1/element-catalog/get-by-reference", accessToken) {
                setBody(reference)
            }.body()
        }

    suspend fun getGroupsByCatalog(accessToken: String, catalog: IdentifiableObjectDto): List<ElementGroupDto> =
        requestGate.withPermit {
            client.postWithBearer("api/v1/element-group/get-by-catalog", accessToken) {
                setBody(catalog)
            }.body()
        }

    suspend fun getElementsByGroup(accessToken: String, group: IdentifiableObjectDto): List<ElementDto> =
        requestGate.withPermit {
            client.postWithBearer("api/v1/element/get-by-group", accessToken) {
                setBody(group)
            }.body()
        }

    suspend fun createElement(accessToken: String, parentGroup: IdentifiableObjectDto, name: String): IdentifiableObjectDto =
        requestGate.withPermit {
            client.postWithBearer("api/v1/element/create", accessToken) {
                setBody(CreateElementRequestDto(parentGroup, name))
            }.body()
        }

    suspend fun getLocation(accessToken: String, target: IdentifiableObjectDto): String =
        requestGate.withPermit {
            client.postWithBearer("api/v1/classification-object/get-location", accessToken) {
                setBody(target)
            }.body()
        }
}
