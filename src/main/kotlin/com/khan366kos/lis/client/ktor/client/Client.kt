package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.client.getWithSession
import com.khan366kos.lis.client.ktor.client.postWithSession
import com.khan366kos.lis.client.ktor.domain.Connection
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json

class Client(
    private val connection: Connection,
) {
    // Ограничивает конкурентные HTTP-запросы к Loodsman независимо от concurrency выше по стеку.
    private val requestGate = Semaphore(connection.maxConcurrentRequests)

    private val client: HttpClient = HttpClient(CIO) {
        // Без этого не-2xx ответы (Loodsman тоже отдаёт InternalServerError-style JSON на
        // ошибках) молча доходят до body<T>(), который пытается задесериализовать тело ошибки
        // как успешный DTO (например IdentifierDto — просто число) — отсюда невнятные
        // "Illegal input: Unexpected JSON token" вместо реальной причины сбоя.
        expectSuccess = true
        defaultRequest {
            contentType(ContentType.Application.Json)
            url(connection.url)
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    val login = Login(client, connection, requestGate)
    val confMetaData = ConfMetaData(client, requestGate)
    val editObject = EditObject(client, requestGate)
    val checkout = CheckOut(client, requestGate)
    val objectInfo = ObjectInfo(client, requestGate)

    suspend fun user(): HttpResponse = client.get("Auth/current-user")

    suspend fun users(): HttpResponse = client.get("DbAdministrator/get-activity")

    suspend fun tree(sessionId: String): HttpResponse = client.getWithSession("Pdm/get-tree", sessionId)

    suspend fun insertObject(sessionId: String, block: HttpRequestBuilder.() -> Unit): HttpResponse =
        client.postWithSession("EditObject/insert-object", sessionId) {
            block()
        }

}

