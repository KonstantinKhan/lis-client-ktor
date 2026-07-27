package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.logic.Validator
import com.khan366kos.lis.client.ktor.migration.runLinksMigration
import com.khan366kos.lis.client.ktor.migration.runObjectsMigration

fun ICorChainDsl<MigrationContext>.validateMapping() = worker {
    on { status == Status.CONNECT_CHECKOUT }
    handle {
        if (!Validator(settings, loodsmanTypes).isValidTarget()) {
            throw RuntimeException(
                "В settings.json указан target-тип из mapping.types, отсутствующий среди типов Loodsman"
            )
        }
    }
    except { e ->
        System.err.println("Проверка mapping не пройдена: ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateObjects() = worker {
    on { status == Status.CONNECT_CHECKOUT }
    handle {
        runObjectsMigration()
        status = Status.OBJECTS_MIGRATED
        println("Создано объектов: ${identifiers.size}")
    }
    except { e ->
        System.err.println("Ошибка миграции объектов: ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateLinks() = worker {
    on { status == Status.OBJECTS_MIGRATED }
    handle {
        runLinksMigration()
        status = Status.LINKS_MIGRATED
        println("Миграция связей завершена")
    }
    except { e ->
        System.err.println("Ошибка миграции связей: ${e.message}")
        throw e
    }
}
