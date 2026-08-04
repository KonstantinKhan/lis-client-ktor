package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock

// ПОЛИНОМ access_token живёт 600с (см. TokenPairDto.expiresIn) — длинная миграция переживает
// протухание токена посреди прогона, сервер отвечает 401 на любой следующий вызов ПОЛИНОМ.
// Оборачивает один вызов ПОЛИНОМ: поймал 401 — обновляет токен через PolynomLogin.updateToken
// (refresh_token), повторяет вызов РОВНО один раз с новым токеном. Если 401 повторится и после
// обновления (например протух и сам refresh_token) — исключение пробрасывается как обычно, без
// второй попытки (тот же принцип, что retryOnTransientError в client/Helpers.kt — ограниченное
// число попыток, не бесконечный цикл).
suspend fun <T> MigrationContext.callPolynom(block: suspend (accessToken: String) -> T): T {
    val tokenUsed = polynomAccessToken
    return try {
        block(tokenUsed)
    } catch (e: ResponseException) {
        if (e.response.status != HttpStatusCode.Unauthorized) throw e
        val refreshedToken = refreshPolynomToken(tokenUsed)
        block(refreshedToken)
    }
}

// Под мьютексом: несколько конкурентных вызовов могут поймать 401 одновременно (все запросы,
// висевшие на протухшем токене разом) — без coalescing каждый послал бы свой update-token,
// затирая refresh_token друг друга. Если к моменту захвата лока токен уже не равен staleToken —
// значит другая корутина уже обновила его, просто используем актуальный, без сетевого запроса.
private suspend fun MigrationContext.refreshPolynomToken(staleToken: String): String =
    polynomTokenMutex.withLock {
        if (polynomAccessToken != staleToken) return@withLock polynomAccessToken

        val refreshed = polynomClient.login.updateToken(polynomAccessToken, polynomRefreshToken)
        polynomAccessToken = refreshed.accessToken
        polynomRefreshToken = refreshed.refreshToken ?: polynomRefreshToken
        println("ПОЛИНОМ: access_token обновлён по refresh_token (истёк во время миграции)")
        polynomAccessToken
    }
