package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.logic.Validator
import com.khan366kos.lis.client.ktor.migration.runAuxMaterialsMigration
import com.khan366kos.lis.client.ktor.migration.runBlanksMigration
import com.khan366kos.lis.client.ktor.migration.runBomMaterialsMigration
import com.khan366kos.lis.client.ktor.migration.runCastingBlanksLinksMigration
import com.khan366kos.lis.client.ktor.migration.runLinksMigration
import com.khan366kos.lis.client.ktor.migration.runMaterialsMigration
import com.khan366kos.lis.client.ktor.migration.runObjectsMigration

fun ICorChainDsl<MigrationContext>.validateMapping() = worker {
    on { status == Status.CONNECT_CHECKOUT }
    handle {
        val validator = Validator(settings, loodsmanTypes)
        if (!validator.isValidTarget()) {
            throw RuntimeException(
                "В settings.json указан target-тип из mapping.types, отсутствующий среди типов Loodsman"
            )
        }
        if (!validator.isValidMaterialTarget()) {
            throw RuntimeException(
                "В settings.json mapping.materials.materialTarget указывает на тип, " +
                    "отсутствующий среди типов Loodsman"
            )
        }
        if (!validator.isValidBlankTargets()) {
            throw RuntimeException(
                "В settings.json mapping.blanks.target/materialTarget указывает на тип, " +
                    "отсутствующий среди типов Loodsman"
            )
        }
        if (!validator.isValidCastingBlankTargets()) {
            throw RuntimeException(
                "В settings.json mapping.castingBlanks.setTarget/materialTarget/auxMaterialTarget " +
                    "указывает на тип, отсутствующий среди типов Loodsman"
            )
        }
        if (!validator.isValidAuxMaterialTargets()) {
            throw RuntimeException(
                "В settings.json mapping.auxMaterials.setTarget/materialTarget указывает на тип, " +
                    "отсутствующий среди типов Loodsman"
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

fun ICorChainDsl<MigrationContext>.migrateMaterials() = worker {
    on { status == Status.LINKS_MIGRATED }
    handle {
        runMaterialsMigration()
        status = Status.MATERIALS_MIGRATED
        println("Миграция материалов ПОЛИНОМ завершена")
    }
    except { e ->
        // Статус+тело ответа Полином печатаются внутри runMaterialsMigration() (except{} тут не
        // suspend, а ResponseException.response.bodyAsText() — suspend-вызов).
        System.err.println("Ошибка миграции материалов ПОЛИНОМ: ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateBomMaterials() = worker {
    on { status == Status.MATERIALS_MIGRATED }
    handle {
        runBomMaterialsMigration()
        status = Status.BOM_MATERIALS_MIGRATED
        println("Миграция материалов по КД (DS) завершена")
    }
    except { e ->
        // Как и в migrateMaterials() — статус+тело ответа Полином печатаются внутри
        // runBomMaterialsMigration(), except{} тут не suspend.
        System.err.println("Ошибка миграции материалов по КД (DS): ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateBlanks() = worker {
    on { status == Status.BOM_MATERIALS_MIGRATED }
    handle {
        runBlanksMigration()
        status = Status.BLANKS_MIGRATED
        println("Миграция заготовок завершена")
    }
    except { e ->
        // Как и в migrateMaterials()/migrateBomMaterials() — статус+тело ответа Полином печатаются
        // внутри runBlanksMigration(), except{} тут не suspend.
        System.err.println("Ошибка миграции заготовок: ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateCastingBlanks() = worker {
    on { status == Status.BLANKS_MIGRATED }
    handle {
        runCastingBlanksLinksMigration()
        status = Status.CASTING_BLANKS_MIGRATED
        println("Миграция литейных заготовок (связи) завершена")
    }
    except { e ->
        // Как и в migrateBlanks() — статус+тело ответа печатаются внутри
        // runCastingBlanksLinksMigration(), except{} тут не suspend.
        System.err.println("Ошибка миграции литейных заготовок: ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateAuxMaterials() = worker {
    on { status == Status.CASTING_BLANKS_MIGRATED }
    handle {
        runAuxMaterialsMigration()
        status = Status.AUX_MATERIALS_MIGRATED
        println("Миграция вспомогательных материалов завершена")
    }
    except { e ->
        // Как и в migrateCastingBlanks() — статус+тело ответа печатаются внутри
        // runAuxMaterialsMigration(), except{} тут не suspend.
        System.err.println("Ошибка миграции вспомогательных материалов: ${e.message}")
        throw e
    }
}
