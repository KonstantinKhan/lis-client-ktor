package com.khan366kos.lis.client.ktor.client

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.append
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

// File/add — multipart/form-data (не JSON, в отличие от остального API). Не оборачивать
// retryOnTransientError: вызов не идемпотентен, повтор создаёт ещё одно вложение к тому же
// IdDocument, а не перезаписывает старое.
class FileEndpoint(
    private val client: HttpClient,
    private val requestGate: Semaphore,
) {
    suspend fun add(
        sessionId: String,
        idDocument: Int,
        fileName: String,
        directory: String = "",
        createdAt: Instant?,
        modifiedAt: Instant?,
        fileData: ByteArray,
    ): HttpResponse =
        requestGate.withPermit {
            client.postWithSession("File/add", sessionId) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("IdDocument", idDocument.toString())
                            append("FileName", fileName)
                            append("Directory", directory)
                            createdAt?.let { append("CreatedAt", it.toString()) }
                            modifiedAt?.let { append("ModifiedAt", it.toString()) }
                            append(
                                "FileData",
                                fileData,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                }
                            )
                        }
                    )
                )
            }
        }
}
