package com.khan366kos.lis.client.ktor.polynom.client

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

suspend fun HttpClient.getWithBearer(
    url: String,
    accessToken: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = get(url) {
    headers { append(HttpHeaders.Authorization, "Bearer $accessToken") }
    block()
}

suspend fun HttpClient.postWithBearer(
    url: String,
    accessToken: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = post(url) {
    headers { append(HttpHeaders.Authorization, "Bearer $accessToken") }
    contentType(ContentType.Application.Json)
    block()
}

suspend fun HttpClient.putWithBearer(
    url: String,
    accessToken: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = put(url) {
    headers { append(HttpHeaders.Authorization, "Bearer $accessToken") }
    contentType(ContentType.Application.Json)
    block()
}

suspend fun HttpClient.patchWithBearer(
    url: String,
    accessToken: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = patch(url) {
    headers { append(HttpHeaders.Authorization, "Bearer $accessToken") }
    block()
}

suspend fun HttpClient.deleteWithBearer(
    url: String,
    accessToken: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = delete(url) {
    headers { append(HttpHeaders.Authorization, "Bearer $accessToken") }
    block()
}
