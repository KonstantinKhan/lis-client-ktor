package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("InfoWorkers")

fun ICorChainDsl<MigrationContext>.showSettings() = worker {
    handle {
        logger.info("База данных: ${settings.connection.dbName}")
        logger.info("Адрес сервера: ${settings.connection.url}")
        logger.info("Исходный файл, ${settings.mapping.source.path}")
    }
}