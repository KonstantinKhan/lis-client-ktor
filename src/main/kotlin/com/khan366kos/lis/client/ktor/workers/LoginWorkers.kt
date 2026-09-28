package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import java.util.Arrays

private val logger = LoggerFactory.getLogger("LoginWorkers")

fun ICorChainDsl<MigrationContext>.login() = worker {
    on { status == Status.LOGIN }
    handle {
        val console = System.console()
        if (console == null) {
            logger.error(
                "Нет консоли. Запустите distribution-скриптом из терминала " +
                        "(build/install/.../bin/...), не 'gradlew run' — он форкает JVM " +
                        "через pipe, и System.console() всегда null."
            )
            throw RuntimeException("Нет консоли")
        }
        login = console.readLine("Логин: ").trim()
        val pwd: CharArray = console.readPassword("Пароль: ")
        passwordChars = pwd

        withCleanup({
            Arrays.fill(pwd, '\u0000')
            passwordChars = null
        }) {
            apiScope.async {
                loodsmanClient.login.login(login, pwd)
            }.await()
        }.fold(
            onSuccess = {
                sessionId = it.sessionId ?: run {
                    status = Status.EMPTY_SESSION
                    return@handle
                }
                status = Status.LOGIN_SUCCESS
            },
            onFailure = {
                status = Status.API_ERROR
                throw it
            }
        )
    }
    except { e ->
        logger.error("login failed: ${e::class.simpleName}: ${e.message}")
        (e as? ClientRequestException)?.let {
            logger.error("HTTP ${it.response.status.value}")
        }
        logger.error("Неверный логин или пароль. Повторите ввод.")
        status = Status.LOGIN
    }
}

inline fun <T> withCleanup(
    cleanup: () -> Unit,
    block: () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: Throwable) {
        Result.failure(e)
    } finally {
        cleanup()
    }
}
