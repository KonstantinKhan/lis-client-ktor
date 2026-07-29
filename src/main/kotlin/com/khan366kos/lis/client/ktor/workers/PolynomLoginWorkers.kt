package com.khan366kos.lis.client.ktor.workers

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.worker
import com.khan366kos.lis.client.ktor.polynom.api.dto.SignInRequestDto
import com.khan366kos.lis.client.ktor.repl.ReplStatus
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.async
import java.util.Arrays

// Аналог workers/LoginWorkers.kt для ПОЛИНОМ: логин/пароль всегда запрашиваются в консоли
// заново при каждом запуске, ничего не сохраняется на диск (по решению пользователя).
// Гейтится на status == CONNECT_CHECKOUT (Loodsman-чекаут уже открыт) && !polynomLoggedIn —
// именно polynomLoggedIn, а не отдельное значение Status, чтобы не мешать повторному
// использованию Status.CONNECT_CHECKOUT в MigrationPipeline (validateMapping/migrateObjects).
fun ICorChainDsl<MigrationContext>.polynomLogin() = worker {
    on { status == Status.CONNECT_CHECKOUT && !polynomLoggedIn }
    handle {
        val console = System.console()
        if (console == null) {
            System.err.println(
                "Нет консоли. Запустите distribution-скриптом из терминала " +
                    "(build/install/.../bin/...), не 'gradlew run' — он форкает JVM " +
                    "через pipe, и System.console() всегда null."
            )
            throw RuntimeException("Нет консоли")
        }

        // Логин/пароль запрашиваются ДО любого сетевого вызова (в т.ч. до резолва storageId,
        // который сам по себе делает HTTP-запрос storage-definitions) — так каждая повторная
        // попытка после сетевой ошибки требует нового блокирующего ввода и не уходит в
        // горячий цикл без пауз (см. login() в LoginWorkers.kt для Loodsman — тот же порядок).
        val login = console.readLine("Логин ПОЛИНОМ: ").trim()
        val pwd = console.readPassword("Пароль ПОЛИНОМ: ")

        withCleanup({ Arrays.fill(pwd, ' ') }) {
            apiScope.async {
                val storageId = settings.polynom.storageId ?: chooseStorageId(console)
                polynomClient.login.signIn(
                    SignInRequestDto(
                        storageId = storageId,
                        login = login,
                        password = pwd.concatToString(),
                        moduleName = settings.polynom.moduleName,
                        clientType = settings.polynom.clientType,
                    )
                )
            }.await()
        }.fold(
            onSuccess = {
                polynomAccessToken = it.accessToken
                polynomRefreshToken = it.refreshToken ?: ""
                polynomLoggedIn = true
                replStatus = ReplStatus.COMMAND
            },
            onFailure = { throw it }
        )
    }
    except { e ->
        System.err.println("Вход в ПОЛИНОМ не выполнен: ${e::class.simpleName}: ${e.message}")
        (e as? ClientRequestException)?.let {
            System.err.println("HTTP ${it.response.status.value}")
        }
        System.err.println("Неверный логин или пароль ПОЛИНОМ. Повторите ввод.")
    }
}

private suspend fun MigrationContext.chooseStorageId(console: java.io.Console): String {
    val storages = apiScope.async { polynomClient.login.storageDefinitions() }.await()
    if (storages.size == 1) return storages.first().storageId

    storages.forEachIndexed { index, storage ->
        println("$index: ${storage.displayName ?: storage.storageId}")
    }
    val choice = console.readLine("Выберите хранилище ПОЛИНОМ (номер): ").trim().toIntOrNull()
    return storages.getOrNull(choice ?: -1)?.storageId
        ?: throw RuntimeException("Некорректный выбор хранилища ПОЛИНОМ")
}
