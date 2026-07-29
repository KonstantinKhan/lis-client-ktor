package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.repl.ReplStatus
import com.khan366kos.lis.client.ktor.client.Client
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.fileExistsInWorkingDir
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.polynom.client.PolynomClient
import java.io.File

fun ICorChainDsl<MigrationContext>.checkConfig() = worker {
    on {
        status == Status.START
    }
    handle {
        if (fileExistsInWorkingDir(configFileName)) {
            status = Status.EXIST_CONFIG
            println("Reading config ${File(configFileName).absolutePath}")
        } else {
            status = Status.NOT_CONFIG
            println("Файл настройки отсутствует")
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
        println("Конфиг успешно прочитан")
    }
}
