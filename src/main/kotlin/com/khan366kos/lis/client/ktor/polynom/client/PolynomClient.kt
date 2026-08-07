package com.khan366kos.lis.client.ktor.polynom.client

import com.khan366kos.lis.client.ktor.domain.PolynomConnection
import com.khan366kos.lis.client.ktor.logging.fileLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json

class PolynomClient(
    private val connection: PolynomConnection,
) {
    // Отдельный троттлинг для ПОЛИНОМ, независимый от Client.requestGate (Loodsman).
    private val requestGate = Semaphore(connection.maxConcurrentRequests)

    private val client: HttpClient = HttpClient(CIO) {
        // Без этого не-2xx ответы (400/404/...) молча доходят до body<T>(), который пытается
        // задесериализовать тело ошибки как успешный DTO — отсюда непонятные "missing fields"
        // вместо реальной причины. С expectSuccess = true такие ответы кидают ResponseException
        // с доступом к статусу и телу (см. except{} в workers/MigrationWorkers.kt).
        expectSuccess = true
        defaultRequest {
            url(connection.url)
        }
        install(ContentNegotiation) {
            // ignoreUnknownKeys обязателен: DTO Полином моделируют только используемые поля,
            // реальные ответы содержат много служебных полей (iconCode/iconColor/description и т.д.).
            json(Json {
                prettyPrint = true
                encodeDefaults = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        // Полный трейс вызовов ПОЛИНОМ API в polynom-api.log — см. ApiFileLogger, тот же приём,
        // что у Loodsman-клиента (Client.kt). Все эндпоинты login/* исключены из фильтра целиком —
        // sign-in шлёт пароль, update-token шлёт refresh_token, оба в открытом виде в теле.
        install(Logging) {
            logger = fileLogger("polynom-api.log")
            level = LogLevel.ALL
            sanitizeHeader { header -> header.equals("Authorization", ignoreCase = true) }
            filter { request -> !request.url.encodedPath.contains("login/") }
        }
    }

    val login = PolynomLogin(client, requestGate)
    val classification = PolynomClassification(client, requestGate)
    val search = PolynomSearch(client, requestGate)
    val concepts = PolynomConcepts(client, requestGate)
}
