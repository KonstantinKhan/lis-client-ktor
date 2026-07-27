package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker

fun ICorChainDsl<MigrationContext>.showSettings() = worker {
    handle {
        println("База данных: ${settings.connection.dbName}")
        println("Адрес сервера: ${settings.connection.url}")
        println("Исходный файл, ${settings.mapping.source.path}")
    }
}