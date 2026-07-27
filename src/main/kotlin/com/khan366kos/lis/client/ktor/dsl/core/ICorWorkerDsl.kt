package com.khan366kos.lis.client.ktor.dsl.core

interface ICorWorkerDsl<T> {
    fun handle(function: suspend T.() -> Unit)
}