package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.LoodsmanObject
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.mapping.toApiDto
import kotlinx.coroutines.async

fun ICorChainDsl<MigrationContext>.createRoot() = worker {
    on { status == Status.LOGIN_SUCCESS }
    handle {
        runCatching {
            apiScope.async {
                when (val rootCandidate = root) {
                    is LoodsmanObject.NewObject ->
                        loodsmanClient.editObject.create(
                            sessionId = sessionId,
                            loodsmanObject = rootCandidate.toApiDto()
                        )

                    LoodsmanObject.Empty -> {
                        throw RuntimeException("Головной объект не создан")
                    }
                }
            }
        }.fold(
            onSuccess = {
                rootId = it.await().asInt()
            },
            onFailure = {
                throw it
            }
        )
    }
}