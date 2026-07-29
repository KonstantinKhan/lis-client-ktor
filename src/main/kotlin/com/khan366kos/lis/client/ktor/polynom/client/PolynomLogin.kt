package com.khan366kos.lis.client.ktor.polynom.client

import com.khan366kos.lis.client.ktor.polynom.api.dto.SignInRequestDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.StorageDefinitionDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.TokenPairDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class PolynomLogin(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    suspend fun storageDefinitions(): List<StorageDefinitionDto> =
        requestGate.withPermit {
            client.get("api/v1/login/storage-definitions").body()
        }

    suspend fun signIn(request: SignInRequestDto): TokenPairDto =
        requestGate.withPermit {
            client.post("api/v1/login/sign-in") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        }

    // Тело — «сырая» строка refresh_token (text/plain), не JSON.
    suspend fun updateToken(accessToken: String, refreshToken: String): TokenPairDto =
        requestGate.withPermit {
            client.patchWithBearer("api/v1/login/update-token", accessToken) {
                contentType(ContentType.Text.Plain)
                setBody(refreshToken)
            }.body()
        }

    suspend fun signOut(accessToken: String) {
        requestGate.withPermit {
            client.deleteWithBearer("api/v1/login/sign-out", accessToken)
        }
    }
}
