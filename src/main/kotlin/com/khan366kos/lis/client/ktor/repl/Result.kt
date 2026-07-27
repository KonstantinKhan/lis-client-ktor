package com.khan366kos.lis.client.ktor.repl

sealed class Result {
    data class Show(val message: String) : Result()
    data class Exit(val message: String): Result()
}