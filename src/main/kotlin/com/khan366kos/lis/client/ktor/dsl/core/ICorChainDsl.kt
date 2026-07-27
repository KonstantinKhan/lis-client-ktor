package com.khan366kos.lis.client.ktor.dsl.core

interface ICorChainDsl<T> {
    fun add(worker: ICorExecDsl<T>)
}