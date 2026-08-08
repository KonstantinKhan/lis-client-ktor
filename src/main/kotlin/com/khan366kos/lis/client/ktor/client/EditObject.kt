package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.CreateBoObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.IdentifierDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValueByIdInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsOutputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpLinkAttrValuesInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpLinkAttrValuesOutputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpLinkInputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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

    // Не вызывается из MigrationEngine — миграция только создаёт связи (unitId передаётся сразу
    // через newLink), никогда не правит существующие. Задел под будущую донастройку unit на уже
    // созданных связях. delLink у вызывающего кода ОБЯЗАТЕЛЬНО false — иначе связь удаляется
    // (см. docs/link-measure-unit-migration.md).
    suspend fun upLink(sessionId: String, link: UpLinkInputDto): HttpResponse =
        requestGate.withPermit {
            client.postWithSession("EditObject/up-link", sessionId) {
                setBody(link)
            }
        }

    suspend fun setValues(sessionId: String, data: List<UpAttrValuesByIdsInputDto>): List<UpAttrValuesByIdsOutputDto> =
        requestGate.withPermit {
            client.postWithSession("EditObject/up-attr-values-by-ids", sessionId) {
                setBody(data)
            }.body()
        }

    // Атрибут ОБЪЕКТА, интегрированного с ПОЛИНОМ:MDM (создан через createBoObject) — Loodsman
    // отвечает 9009 "Метод AddAttrValues неприменим..." на setValues() выше для таких объектов.
    // Реальный инцидент (ДВЕ неудачные попытки до этой): батчевый up-attr-values-by-ids — 9009;
    // "for-bo"-вариант EditObject/up-attr-value-for-bo-by-id — принимает запрос (200, пустое
    // тело), но значение не проставляется вообще (похоже на баг/несостыковку самого Loodsman —
    // ПОЛИНОМ-проверка есть у батчевого метода, а у "for-bo" эндпоинта, судя по всему, объект в
    // итоге не резолвится корректно). Рабочий вариант, найденный вручную через Swagger UI —
    // обычный ПОШТУЧНЫЙ (не batch) EditObject/up-attr-value-by-id: та же операция, что у setValues
    // (батч), но без встроенной проверки на ПОЛИНОМ-интеграцию, поэтому работает и для BO-объектов.
    // Без документированного тела ответа в swagger.lapis (тот же приём, что upLink выше).
    suspend fun upAttrValueById(sessionId: String, data: UpAttrValueByIdInputDto): HttpResponse =
        requestGate.withPermit {
            client.postWithSession("EditObject/up-attr-value-by-id", sessionId) {
                setBody(data)
            }
        }

    // Атрибут СВЯЗИ (не объекта) — отдельный эндпоинт от up-attr-values-by-ids/setValues выше.
    // linkId — id связи (idLink), возвращается newLink()/новым свойством ObjectInfo.linkedFast, не
    // versionId объекта.
    suspend fun setLinkAttrValues(sessionId: String, data: List<UpLinkAttrValuesInputDto>): List<UpLinkAttrValuesOutputDto> =
        requestGate.withPermit {
            client.postWithSession("EditObject/up-link-attr-values", sessionId) {
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
    // Полином (по его location-строке) происходят за один нативный вызов Loodsman. С retry на
    // таймаут/5xx (см. retryOnTransientError) — реальный инцидент, сервер под нагрузкой иногда не
    // укладывается в requestTimeoutMillis; retry, а не withPermit, снаружи — permit не держится
    // на время задержки между попытками.
    suspend fun createBoObject(sessionId: String, data: CreateBoObjectInputDto): Int =
        retryOnTransientError {
            requestGate.withPermit {
                client.postWithSession("EditObject/create-bo-object", sessionId) {
                    setBody(data)
                }.body()
            }
        }
}