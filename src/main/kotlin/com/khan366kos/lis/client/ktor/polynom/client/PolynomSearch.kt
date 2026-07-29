package com.khan366kos.lis.client.ktor.polynom.client

import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.PropertySearchConditionDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.PropertySearchItemDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.PropertySearchRequestDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.PropertySearchResponseDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.PropertySearchValuesDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.SimplePropertyConditionDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.StringPropertyValueDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// StringCompareOperation.Equal — числовые значения enum не задокументированы нигде в доках,
// 1 подтверждён рабочим запросом, снятым из devtools веб-клиента Полином.
private const val STRING_EQUAL_OPERATION = 1

// Фиксированный маркер для values.stringProperties[] — НЕ id свойства (подтверждено тем же
// рабочим запросом: там objectId=0, typeId=9 независимо от того, какое свойство ищется).
private const val STRING_VALUE_TYPE_ID = 9

class PolynomSearch(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    // Ищет элементы классификатора по значению строкового свойства (например «Код
    // классификатора»). propertyDefinition — идентификатор определения свойства в схеме Полином,
    // идёт в searchConditionTargetQualifier (не в definition — см. комментарий в
    // SimplePropertyConditionDto).
    suspend fun searchByStringProperty(
        accessToken: String,
        scope: IdentifiableObjectDto?,
        propertyDefinition: IdentifiableObjectDto,
        value: String,
    ): List<PropertySearchItemDto> = requestGate.withPermit {
        val request = PropertySearchRequestDto(
            ownerScope = scope,
            condition = PropertySearchConditionDto(
                simpleConditions = listOf(
                    SimplePropertyConditionDto(
                        searchConditionTargetQualifier = propertyDefinition,
                        operation = STRING_EQUAL_OPERATION,
                    )
                )
            ),
            values = PropertySearchValuesDto(
                stringProperties = listOf(
                    StringPropertyValueDto(objectId = 0, typeId = STRING_VALUE_TYPE_ID, value = value)
                )
            )
        )
        client.postWithBearer("api/v1/search/execute-property-search", accessToken) {
            setBody(request)
        }.body<PropertySearchResponseDto>().items
    }
}
