package com.khan366kos.lis.client.ktor.client

import io.ktor.client.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay

suspend fun HttpClient.getWithSession(
    url: String,
    sessionId: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse =
    get(url) {
        headers {
            append("Web-Loodsman-Session", sessionId)
        }
        block()

    }

suspend fun HttpClient.postWithSession(
    url: String,
    sessionId: String,
//    loodsmanObject: LoodsmanObjectModel,
    block: HttpRequestBuilder.() -> Unit
): HttpResponse = post(url) {
    headers {
        append("Web-Loodsman-Session", sessionId)
    }
    block()
}

// Retry на транзиентных сбоях (таймаут, 5xx) — реальный инцидент: EditObject/create-bo-object
// иногда не укладывается в requestTimeoutMillis под нагрузкой сервера (Loodsman -> ПОЛИНОМ), без
// повтора кандидат молча пропускается. НЕ применять к new-object/new-link и другим
// неидемпотентным вызовам без уникального индекса — таймаут не гарантирует, что запрос не
// выполнился на сервере, повтор идемпотентен только там, где повторный вызов с теми же данными
// либо не создаёт дубль (create-bo-object падает на уникальном индексе по location), либо
// перезаписывает то же самое состояние (reference-bo-version).
suspend fun <T> retryOnTransientError(
    times: Int = 3,
    initialDelayMs: Long = 1_000,
    block: suspend () -> T
): T {
    var attempt = 0
    var delayMs = initialDelayMs
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            attempt++
            val transient = e is HttpRequestTimeoutException ||
                (e is ResponseException && e.response.status.value in 500..599)
            if (!transient || attempt >= times) throw e
            System.err.println("Повтор запроса после ошибки (попытка $attempt/$times): ${e.message}")
            delay(delayMs)
            delayMs *= 2
        }
    }
}