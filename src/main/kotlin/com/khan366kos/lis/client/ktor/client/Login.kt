package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.domain.Connection
import com.khan366kos.lis.client.ktor.loodsman.api.dto.LoginInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.SessionOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Login(
    private val client: HttpClient,
    private val connection: Connection,
    private val requestGate: Semaphore
) {
    suspend fun login(userName: String, userPassword: CharArray): SessionOutputDto =
        requestGate.withPermit {
            client.post("Auth/login") {
                setBody(
                    with(connection) {
                        LoginInputDto(
                            dbName = dbName,
                            username = userName,
                            password = userPassword.concatToString(),
                            rememberMe = remember
                        )
                    }
                )
            }.body()
        }
}