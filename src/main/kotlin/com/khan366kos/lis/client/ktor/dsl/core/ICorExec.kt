package com.khan366kos.lis.client.ktor.dsl.core

interface ICorExec<T> {
    suspend fun execute(context: T)
}