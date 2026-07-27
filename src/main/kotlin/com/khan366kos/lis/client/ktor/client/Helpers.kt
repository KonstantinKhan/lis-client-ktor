package com.khan366kos.lis.client.ktor.client

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

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