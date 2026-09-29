package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.repl.ReplStatus
import com.khan366kos.lis.client.ktor.client.Client
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.fileExistsInWorkingDir
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.polynom.client.PolynomClient
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ConfigWorkers")

fun ICorChainDsl<MigrationContext>.checkConfig() = worker {
    on {
        status == Status.START
    }
    handle {
        if (fileExistsInWorkingDir(configFileName)) {
            status = Status.EXIST_CONFIG
            logger.info("Reading config ${File(configFileName).absolutePath}")
        } else {
            status = Status.NOT_CONFIG
            logger.info("Файл настройки отсутствует")
        }
    }
}

fun ICorChainDsl<MigrationContext>.readConfig() = worker {
    on {
        status == Status.EXIST_CONFIG
    }
    handle {
        settings = json.decodeFromString(File(configFileName).readText())
        loodsmanClient = Client(connection = settings.connection)
        polynomClient = PolynomClient(connection = settings.polynom)
        status = Status.LOGIN
        replStatus = ReplStatus.AUTH
        logger.info("Конфиг успешно прочитан")
    }
}
