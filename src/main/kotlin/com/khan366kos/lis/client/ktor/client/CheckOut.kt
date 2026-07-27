package com.khan366kos.lis.client.ktor.client

import com.khan366kos.lis.client.ktor.loodsman.api.dto.CheckOutInDbInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.response.SaveFilesErrorOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class CheckOut(
    private val client: HttpClient,
    private val requestGate: Semaphore
) {
    suspend fun checkout(sessionId: String, typeName: String, name: String): String =
        requestGate.withPermit {
            client.getWithSession("CheckOut/check-out", sessionId) {
                url {
                    parameters.append("typeName", typeName)
                    parameters.append("productName", name)
                    parameters.append("mode", "0")
                }
            }.bodyAsText().replace("\"", "")
        }

    suspend fun connectToCheckout(sessionId: String, checkout: String, dbName: String): Int =
        requestGate.withPermit {
            client.getWithSession("CheckOut/connect-to-check-out", sessionId) {
                url {
                    parameters.append("checkOutName", checkout)
                    parameters.append("dbName", dbName)
                }
            }.bodyAsText().trim().toInt()
        }

    suspend fun checkIn(sessionId: String, request: CheckOutInDbInputDto): SaveFilesErrorOutputDto =
        requestGate.withPermit {
            client.postWithSession("CheckOut/check-in-2", sessionId) {
                setBody(request)
            }.body()
        }
}