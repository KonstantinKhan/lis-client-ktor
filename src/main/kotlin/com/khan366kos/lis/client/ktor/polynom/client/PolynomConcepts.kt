package com.khan366kos.lis.client.ktor.polynom.client

import com.khan366kos.lis.client.ktor.polynom.api.dto.AppointedConceptsResponseDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.ConceptDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.ConceptPropertySourceDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.GetByAbsoluteCodeRequestDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class PolynomConcepts(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    // Резолв property-definition по полному коду (absoluteCode) — единственный из перепробованных
    // путей резолва свойств, который реально работает: concept/get-all зависает насмерть,
    // concept-property-source/get-by-code требует concept и всё равно даёт 404,
    // concept-property-source/get-by-id даёт 404 даже на id из get-property-sources того же
    // понятия. get-by-absolute-code не требует concept вообще — код берётся из админки Полином.
    suspend fun getPropertySourceByAbsoluteCode(accessToken: String, absoluteCode: String): ConceptPropertySourceDto =
        requestGate.withPermit {
            client.postWithBearer("api/v1/concept-property-source/get-by-absolute-code", accessToken) {
                setBody(GetByAbsoluteCodeRequestDto(absoluteCode))
            }.body()
        }

    // Понятия (concept), назначенные ("appointed") на каталог/группу — рабочий эндпоинт (в
    // отличие от concept/get-all), используется чтобы найти системное понятие "Элемент
    // классификации" для ownerScope в search/execute-property-search (см. MaterialsEngine.kt).
    // Верхнеуровневые id в ответе принадлежат записи назначения, а не понятию — реальная ссылка
    // лежит во вложенном AppointedConceptDto.concept.
    suspend fun getConceptsAppointedTo(accessToken: String, owner: IdentifiableObjectDto): List<ConceptDto> =
        requestGate.withPermit {
            client.postWithBearer("api/v1/concept/get-by-concept-appointer", accessToken) {
                setBody(owner)
            }.body<AppointedConceptsResponseDto>().appointedConcepts.map { it.concept }
        }
}
