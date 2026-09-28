package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.mapping.toDomain
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("LoodsmanTypeWorkers")

fun ICorChainDsl<MigrationContext>.allTypes() = worker {
    on { status == Status.LOGIN_SUCCESS }
    handle {
        loodsmanTypes.addAll(
            runCatching {
                loodsmanClient.confMetaData.attributes(sessionId)
            }.fold(
                onSuccess = { types ->
                    types.map { it.toDomain() }
                },
                onFailure = {
                    throw it
                }
            )
        )
    }
    except {
        logger.info(it.message)
    }
}