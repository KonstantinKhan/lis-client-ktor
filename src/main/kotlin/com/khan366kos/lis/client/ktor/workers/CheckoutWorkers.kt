package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.LoodsmanObject
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.loodsman.api.dto.CheckOutInDbInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.response.SaveFilesErrorOutputDto
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async

fun ICorChainDsl<MigrationContext>.checkout() = worker {
    on { status == Status.LOGIN_SUCCESS }
    handle {
        when (val rootCandidate = root) {
            is LoodsmanObject.NewObject ->
                runCatching {
                    apiScope.async {
                        loodsmanClient.checkout.checkout(
                            sessionId = sessionId,
                            typeName = rootCandidate.name,
                            name = rootCandidate.name,
                        )
                    }.await()
                }.fold(
                    onSuccess = {
                        this.checkout = it
                        println("Checkout $checkout")
                        status = Status.CHECKOUT
                    },
                    onFailure = {
                        throw it
                    }
                )

            LoodsmanObject.Empty -> throw RuntimeException("Головной объект отсутствует")
        }
    }
}

fun ICorChainDsl<MigrationContext>.connectCheckout() = worker {
    on { status == Status.CHECKOUT }
    handle {
        println("handle")
        println("checkout: $checkout")
        println("dbName: ${settings.connection.dbName}")
        runCatching {
            apiScope.async {
                loodsmanClient.checkout.connectToCheckout(
                    sessionId = sessionId,
                    checkout = checkout,
                    dbName = settings.connection.dbName,
                )
            }.await()
        }.fold(
            onSuccess = {
                println("success")
                val isAdmin = it == 1
                if (isAdmin) {
                    // replStatus остаётся AUTH: следующий шаг — вход в ПОЛИНОМ (PolynomInit,
                    // гейтится на status == CONNECT_CHECKOUT && !polynomLoggedIn), только после
                    // него ReplConsole переходит в COMMAND (см. workers/PolynomLoginWorkers.kt).
                    status = Status.CONNECT_CHECKOUT
                }
                println("Подключение от имени админа")
            },
            onFailure = {
                println("Ошибка: ${it.message}")
                status = Status.NOT_ENOUGH_RIGHTS
                println("Недостаточно прав для выполнения миграции")
                throw it
            }
        )
    }
}

fun ICorChainDsl<MigrationContext>.checkin() = worker {
    handle {
        runCatching {
            apiScope.async {
                loodsmanClient.checkout.checkIn(
                    sessionId, CheckOutInDbInputDto(
                        checkOutName = checkout,
                        dbName = settings.connection.dbName,
                    )
                )
            }.await()
        }.fold(
            onSuccess = {
                println(it)
            },
            onFailure = {
                println("Сохранение и возврат в базу прошли успешно")
            }
        )
    }
}

